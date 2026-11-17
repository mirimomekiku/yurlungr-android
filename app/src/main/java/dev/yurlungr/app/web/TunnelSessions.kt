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

/** A public link to a local port, made by cloudflared's account-free Quick Tunnel. */
class Tunnel(val port: Int, val label: String) {
    var state by mutableStateOf(TunnelState.Starting)
    var url by mutableStateOf<String?>(null)
    var message by mutableStateOf("")
    var terminal: TerminalSession? = null
    val active get() = state == TunnelState.Starting || state == TunnelState.Connecting || state == TunnelState.Live
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

    fun start(context: Context, port: Int, label: String): Tunnel {
        val app = context.applicationContext
        tunnels[port]?.takeIf { it.active }?.let { return it }
        val tunnel = Tunnel(port, label)
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
                restart = { stop(app, tunnel); start(app, port, label) },
                exited = { if (tunnel.active) fail(tunnel, terminal) },
            ))
            // Wide, so the link never wraps across lines.
            terminal.updateSize(160, 24, 8, 16)
        } catch (e: Exception) {
            tunnel.state = TunnelState.Failed
            tunnel.message = e.message ?: "Couldn't start the tunnel."
            return tunnel
        }
        val terminal = tunnel.terminal ?: return tunnel
        scope.launch {
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
                    }
                }
                if (!terminal.isRunning) { if (tunnel.active) fail(tunnel, terminal); break }
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

    private fun fail(tunnel: Tunnel, terminal: TerminalSession) {
        tunnel.message = failureLine(terminal.emulator?.screen?.transcriptText.orEmpty())
        tunnel.state = TunnelState.Failed
        tunnel.url = null
    }

    fun stop(context: Context, tunnel: Tunnel, failed: Boolean = false) {
        if (tunnel.state != TunnelState.Failed) tunnel.state = if (failed) TunnelState.Failed else TunnelState.Stopped
        tunnel.url = null
        tunnel.terminal?.let { TerminalSessions.closeDirect(context.applicationContext, it) }
        tunnel.terminal = null
    }

    /** A server went away: its public link must not outlive it. */
    fun stopForPort(context: Context, port: Int) { tunnels[port]?.takeIf { it.active }?.let { stop(context, it) } }

    fun stopAll(context: Context) = live().forEach { stop(context, it) }
}
