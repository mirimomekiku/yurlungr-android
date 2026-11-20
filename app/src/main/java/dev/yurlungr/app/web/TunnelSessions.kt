package dev.yurlungr.app.web

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.termux.terminal.TerminalSession
import dev.yurlungr.app.runtime.RuntimePaths
import dev.yurlungr.app.terminal.TerminalSessions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class TunnelState(val label: String) { Starting("Starting"), Connecting("Connecting"), Live("Live"), Failed("Failed"), Stopped("Stopped") }

/** How long a public link stays up before it stops by itself. */
enum class TunnelLifetime(val label: String, val millis: Long?) {
    Minutes15("15 min", 15 * 60_000L), Hour1("1 hour", 60 * 60_000L), Hours4("4 hours", 4 * 60 * 60_000L), UntilStopped("Until stopped", null),
}

/** A public link to a local port, made by cloudflared's account-free Quick Tunnel. */
class Tunnel(val port: Int, val label: String, val lifetime: TunnelLifetime = TunnelLifetime.Hour1, val key: String? = null) {
    var state by mutableStateOf(TunnelState.Starting)
    var url by mutableStateOf<String?>(null)
    var message by mutableStateOf("")
    var terminal: TerminalSession? = null
    /** When the link stops by itself (counted from when it went live); null until then, or for [TunnelLifetime.UntilStopped]. */
    var expiresAt by mutableStateOf<Long?>(null)
    /** Where the run's supervisor reads [key] from; null when the port isn't a Yurlungr run (no gate possible). */
    internal var accessFile: java.io.File? = null
    val active get() = state == TunnelState.Starting || state == TunnelState.Connecting || state == TunnelState.Live
    /** The link to hand out: with the access key when there is one, so sharing stays one tap. */
    val shareUrl get() = url?.let { TunnelLinks.withKey(it, key) }
}

/** The pure parts of public links: access keys, expiry and how much time is left. */
internal object TunnelLinks {
    private val random = java.security.SecureRandom()

    /** 24 random bytes, URL-safe: what the supervisor accepts (16–128 of A–Z a–z 0–9 _ -). */
    fun newKey(): String = urlSafe(ByteArray(24).also(random::nextBytes))

    private const val URL_SAFE = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    /**
     * Unpadded URL-safe base64. Written out because java.util.Base64 needs API 26 (minSdk is 24) and
     * android.util.Base64 is a stub in JVM unit tests.
     */
    internal fun urlSafe(bytes: ByteArray): String = buildString {
        var i = 0
        while (i < bytes.size) {
            val b0 = bytes[i].toInt() and 0xFF
            val b1 = if (i + 1 < bytes.size) bytes[i + 1].toInt() and 0xFF else 0
            val b2 = if (i + 2 < bytes.size) bytes[i + 2].toInt() and 0xFF else 0
            append(URL_SAFE[b0 shr 2])
            append(URL_SAFE[((b0 and 3) shl 4) or (b1 shr 4)])
            if (i + 1 < bytes.size) append(URL_SAFE[((b1 and 15) shl 2) or (b2 shr 6)])
            if (i + 2 < bytes.size) append(URL_SAFE[b2 and 63])
            i += 3
        }
    }

    fun withKey(url: String, key: String?): String = if (key == null) url else url.trimEnd('/') + "/?key=" + key

    fun expiresAt(liveAt: Long, lifetime: TunnelLifetime): Long? = lifetime.millis?.let { liveAt + it }

    fun expired(now: Long, expiresAt: Long?): Boolean = expiresAt != null && now >= expiresAt

    /** "45 min left", "1 h 5 min left", "Less than a minute left"; null when the link has no end. */
    fun remaining(now: Long, expiresAt: Long?): String? {
        expiresAt ?: return null
        val minutes = (expiresAt - now + 59_999) / 60_000 // rounded up: "1 min left" until it's gone
        return when {
            expiresAt - now < 60_000 -> "Less than a minute left"
            minutes < 60 -> "$minutes min left"
            minutes % 60 == 0L -> "${minutes / 60} h left"
            else -> "${minutes / 60} h ${minutes % 60} min left"
        }
    }
}

/**
 * One cloudflared tunnel per local port, run in a managed terminal (so it stays alive in the
 * background and its output is readable in Logs). The link is read from cloudflared's output.
 */
object TunnelSessions {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val tunnels = mutableStateMapOf<Int, Tunnel>()
    private val URL = Regex("https://[a-z0-9][a-z0-9-]*\\.trycloudflare\\.com")
    private const val GIVE_UP_MS = 60_000L
    private const val PORT_CHECK_MS = 5_000L
    // One at a time, in order: a stopped link removing its key must not race a new link writing its own.
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val keyFiles = Dispatchers.IO.limitedParallelism(1)

    private fun isListening(port: Int): Boolean = runCatching {
        java.net.Socket().use { it.connect(java.net.InetSocketAddress("127.0.0.1", port), 1_000) }
    }.isSuccess

    fun get(port: Int): Tunnel? = tunnels[port]
    fun live(): List<Tunnel> = tunnels.values.filter { it.active }

    fun isInstalled(context: Context): Boolean = File(RuntimePaths(context).bin, "cloudflared").canExecute()

    /** The command for a tunnel to [port] on the loopback address only. */
    internal fun command(port: Int): String {
        require(port in 1..65535) { "Bad port" }
        return "exec cloudflared tunnel --no-autoupdate --url http://127.0.0.1:$port"
    }

    /** The public link in cloudflared's [output], once it has printed one (not the API host its errors mention). */
    internal fun parseUrl(output: String): String? = URL.findAll(output).map { it.value }.firstOrNull { it != "https://api.trycloudflare.com" }

    /** cloudflared says this once the edge accepted the tunnel; before that the link doesn't work yet. */
    internal fun isRegistered(output: String): Boolean = output.contains("Registered tunnel connection")

    /** The most telling line of a failed run: an error, else the last line. */
    internal fun failureLine(output: String): String {
        val lines = output.lines().map { it.trim() }.filter { it.isNotEmpty() }
        return (lines.lastOrNull { Regex("\\b(ERR|error|failed)\\b", RegexOption.IGNORE_CASE).containsMatchIn(it) } ?: lines.lastOrNull() ?: "cloudflared exited")
            .replace(Regex("^\\S+\\s+(ERR|INF|WRN)\\s+"), "").take(240)
    }

    /** The run serving [port], whose supervisor can check an access key; null for other servers. */
    private fun runFor(port: Int): WebRun? = WebRunSessions.all().firstOrNull { it.config.port == port && !it.finalized }

    /** Whether a link to [port] can require an access key: only Yurlungr's own runs check one. */
    fun canProtect(port: Int): Boolean = runFor(port) != null

    /** What the user picked last time (this session): new links start from it. */
    var lastLifetime by mutableStateOf(TunnelLifetime.Hour1)
    var lastProtect by mutableStateOf(true)

    fun start(context: Context, port: Int, label: String, lifetime: TunnelLifetime = lastLifetime, protect: Boolean = lastProtect): Tunnel {
        val app = context.applicationContext
        tunnels[port]?.takeIf { it.active }?.let { return it }
        val run = if (protect) runFor(port) else null
        val tunnel = Tunnel(port, label, lifetime, key = run?.let { TunnelLinks.newKey() })
        tunnel.accessFile = run?.let { File(it.directory, "access") }
        tunnels[port] = tunnel
        if (!isInstalled(app)) {
            tunnel.state = TunnelState.Failed
            tunnel.message = "cloudflared isn't installed. Install Servers & tunnels first."
            return tunnel
        }
        try {
            val terminal = TerminalSessions.create(app, command(port), "Tunnel · :$port", keepShell = false)
            tunnel.terminal = terminal
            TerminalSessions.manage(terminal, TerminalSessions.ManagedLifecycle(
                end = { stop(app, tunnel) },
                close = { stop(app, tunnel) },
                restart = { stop(app, tunnel); start(app, port, label, lifetime, protect) },
                exited = { if (tunnel.active) fail(app, tunnel, terminal) },
            ))
            // Wide, so the link never wraps across lines.
            terminal.updateSize(160, 24, 8, 16)
        } catch (e: Exception) {
            tunnel.state = TunnelState.Failed
            tunnel.message = e.message ?: "Couldn't start the tunnel."
            return tunnel
        }
        val terminal = tunnel.terminal ?: return tunnel
        TunnelNotifications.update(app)
        scope.launch {
            // The supervisor must know the key before the link can go live (this runs before the loop that
            // marks it live). If it can't be written, no link rather than an open one.
            val key = tunnel.key
            val file = tunnel.accessFile
            if (key != null && file != null) {
                val written = withContext(keyFiles) { runCatching { writePrivate(file, key) }.isSuccess }
                if (!written) {
                    tunnel.message = "Couldn't set the link's access key, so it wasn't shared."
                    stop(app, tunnel, failed = true)
                    return@launch
                }
            }
            val started = System.currentTimeMillis()
            var portMisses = 0
            var lastPortCheck = 0L
            while (tunnel.active) {
                // The transcript is only read until the link is live: rebuilding it every tick for hours costs battery.
                if (tunnel.state != TunnelState.Live) {
                    val text = terminal.emulator?.screen?.transcriptText.orEmpty()
                    val url = parseUrl(text)
                    if (url != null) {
                        tunnel.url = url
                        tunnel.state = if (isRegistered(text)) TunnelState.Live else TunnelState.Connecting
                        if (tunnel.state == TunnelState.Live) {
                            tunnel.expiresAt = TunnelLinks.expiresAt(System.currentTimeMillis(), tunnel.lifetime)
                            TunnelNotifications.update(app)
                        }
                    }
                }
                if (!terminal.isRunning) { if (tunnel.active) fail(app, tunnel, terminal); break }
                // A forgotten link shouldn't stay open for days.
                if (tunnel.state == TunnelState.Live && TunnelLinks.expired(System.currentTimeMillis(), tunnel.expiresAt)) {
                    tunnel.message = "The link stopped after ${tunnel.lifetime.label}, as chosen."
                    stop(app, tunnel)
                    break
                }
                if (tunnel.state != TunnelState.Live && System.currentTimeMillis() - started > GIVE_UP_MS) {
                    tunnel.message = if (tunnel.state == TunnelState.Starting) "No link after a minute. Check the network, then try again."
                        else "Cloudflare gave a link but it never went live. Try again."
                    stop(app, tunnel, failed = true)
                    break
                }
                // Not every shared port belongs to a web run (whose stop ends the link): when nothing listens
                // any more, the public link goes too.
                val now = System.currentTimeMillis()
                if (tunnel.state == TunnelState.Live && now - lastPortCheck >= PORT_CHECK_MS) {
                    lastPortCheck = now
                    portMisses = if (withContext(Dispatchers.IO) { isListening(port) }) 0 else portMisses + 1
                    if (portMisses >= 3) {
                        tunnel.message = "The server on port $port stopped, so its public link was closed."
                        stop(app, tunnel, failed = true)
                        break
                    }
                }
                delay(if (tunnel.state == TunnelState.Live) 2_000 else 500)
            }
        }
        return tunnel
    }

    private fun fail(context: Context, tunnel: Tunnel, terminal: TerminalSession) {
        tunnel.message = failureLine(terminal.emulator?.screen?.transcriptText.orEmpty())
        tunnel.state = TunnelState.Failed
        tunnel.url = null
        ended(context.applicationContext, tunnel)
    }

    fun stop(context: Context, tunnel: Tunnel, failed: Boolean = false) {
        if (tunnel.state != TunnelState.Failed) tunnel.state = if (failed) TunnelState.Failed else TunnelState.Stopped
        tunnel.url = null
        tunnel.terminal?.let { TerminalSessions.closeDirect(context.applicationContext, it) }
        tunnel.terminal = null
        ended(context.applicationContext, tunnel)
    }

    /** A link is gone: its key goes with it (a later link gets a new one), and the notification catches up. */
    private fun ended(app: Context, tunnel: Tunnel) {
        tunnel.expiresAt = null
        val key = tunnel.key
        tunnel.accessFile?.let { file -> scope.launch(keyFiles) { if (runCatching { file.readText() }.getOrNull() == key) file.delete() } }
        TunnelNotifications.update(app)
    }

    /** Writes [text] readable by this app only, replacing the file in one step. */
    private fun writePrivate(file: File, text: String) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(text)
        tmp.setReadable(false, false); tmp.setReadable(true, true)
        tmp.setWritable(false, false); tmp.setWritable(true, true)
        check(tmp.renameTo(file)) { "Couldn't write ${file.name}" }
    }

    /** A server went away: its public link must not outlive it. */
    fun stopForPort(context: Context, port: Int) { tunnels[port]?.takeIf { it.active }?.let { stop(context, it) } }

    fun stopAll(context: Context) = live().forEach { stop(context, it) }
}
