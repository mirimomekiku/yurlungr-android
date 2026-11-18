package dev.yurlungr.app.web

import android.system.Os
import android.system.OsConstants
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.yurlungr.app.runtime.CommandRunner
import dev.yurlungr.app.runtime.RuntimePaths
import dev.yurlungr.app.terminal.TerminalSessions
import dev.yurlungr.app.ui.AppColors
import dev.yurlungr.app.ui.AppSheet
import dev.yurlungr.app.ui.Group
import dev.yurlungr.app.ui.GroupRow
import dev.yurlungr.app.ui.Ph
import dev.yurlungr.app.ui.PhIcon
import dev.yurlungr.app.ui.ScreenHeader
import dev.yurlungr.app.ui.SectionLabel
import dev.yurlungr.app.ui.WaveDots
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

/** A process in the runtime (Android only shows the app's own). */
data class RuntimeProcess(val pid: Int, val ppid: Int, val seconds: Long, val rssKb: Long, val args: String) {
    val name: String get() = args.substringBefore(' ').substringAfterLast('/')
}

/**
 * Listening ports and processes. Android doesn't let apps read /proc/net, so ports are found by
 * connecting to the usual development ports on 127.0.0.1, plus the app's own web runs.
 */
object LocalServers {
    val KNOWN_PORTS: Map<Int, String> = buildMap {
        put(3000, "Node.js · Next.js · React"); put(3001, "Node.js"); put(4000, "Node.js"); put(4200, "Angular")
        put(4321, "Astro"); put(5000, "Flask"); for (p in 5173..5180) put(p, "Vite"); put(3306, "MariaDB / MySQL")
        put(5432, "PostgreSQL"); put(6379, "Redis"); put(8000, "Python · Django"); put(8080, "HTTP"); put(8081, "Metro · Expo")
        put(8888, "Jupyter"); put(9000, "HTTP"); put(11434, "Ollama"); put(1883, "MQTT (Mosquitto)"); put(2019, "Caddy admin")
        for (p in 19000..19006) put(p, "Expo"); put(24678, "Vite HMR"); put(8765, "Yurlungr remote"); put(8090, "HTTP"); put(1313, "Hugo")
        put(3030, "HTTP"); put(1111, "Zola"); put(4173, "Vite preview")
    }

    /** Ports that something on this phone accepts connections on. */
    suspend fun probe(extra: Collection<Int>): List<Int> = coroutineScope {
        (KNOWN_PORTS.keys + extra).distinct().sorted().map { port ->
            async(Dispatchers.IO) {
                port.takeIf {
                    runCatching { Socket().use { s -> s.connect(InetSocketAddress("127.0.0.1", port), 250); true } }.getOrDefault(false)
                }
            }
        }.awaitAll().filterNotNull()
    }

    /** `ps -eo pid=,ppid=,etimes=,rss=,args=` lines. */
    fun parsePs(output: String): List<RuntimeProcess> = output.lineSequence().mapNotNull { line ->
        val parts = line.trim().split(Regex("\\s+"), limit = 5)
        if (parts.size < 5) return@mapNotNull null
        RuntimeProcess(parts[0].toIntOrNull() ?: return@mapNotNull null, parts[1].toIntOrNull() ?: 0,
            parts[2].toLongOrNull() ?: 0, parts[3].toLongOrNull() ?: 0, parts[4])
    }.toList()

    fun uptime(seconds: Long): String = when {
        seconds < 60 -> "${seconds}s"
        seconds < 3600 -> "${seconds / 60}m"
        seconds < 86_400 -> "${seconds / 3600}h ${seconds % 3600 / 60}m"
        else -> "${seconds / 86_400}d"
    }
}

/**
 * Servers: the web apps the app runs (Preview, browser, Logs, Stop), other listening ports
 * (with Preview), and the runtime's processes (with Stop). Refreshes every few seconds.
 */
@Composable
fun ServersScreen(onBack: () -> Unit, onPreview: (url: String, title: String) -> Unit, onOpenTerminal: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val paths = remember { RuntimePaths(context) }
    var ports by remember { mutableStateOf<List<Int>?>(null) }
    var processes by remember { mutableStateOf<List<RuntimeProcess>?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var stopping by remember { mutableStateOf<RuntimeProcess?>(null) }
    var sharePort by remember { mutableStateOf<Pair<Int, String>?>(null) }
    sharePort?.let { (port, label) -> TunnelSheet(port, label, onDismiss = { sharePort = null }, onOpenTerminal = onOpenTerminal) }
    val runs = WebRunSessions.all()
    LaunchedEffect(reload) {
        while (true) {
            // Read each round, so a run started while this screen is open gets probed too.
            ports = LocalServers.probe(WebRunSessions.all().filter { it.state.active }.map { it.config.port })
            if (paths.isBootstrapInstalled) {
                val r = CommandRunner.run(paths, listOf(File(paths.bin, "ps").path, "-eo", "pid=,ppid=,etimes=,rss=,args="), forParsing = true, timeoutMs = 10_000)
                val me = android.os.Process.myPid()
                processes = LocalServers.parsePs(r.output).filter { it.pid != me && it.ppid != 0 && !it.args.contains("etimes=,rss=") }
                    .sortedByDescending { it.rssKb }
            } else processes = emptyList()
            delay(4_000)
        }
    }
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize().background(colors.background).systemBarsPadding()) {
        ScreenHeader("Servers", onBack) {
            IconButton(onClick = { reload++ }) { PhIcon(Ph.ArrowClockwise, "Refresh", size = 20.dp) }
        }
        LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { SectionLabel("Web apps") }
            item {
                Group {
                    if (runs.isEmpty()) GroupRow("No web apps running", subtitle = "Start one with Run on a project.", icon = Ph.Globe, trailing = {})
                    for (run in runs.sortedByDescending { it.state.active }) {
                        val title = "${run.config.project.framework} · ${run.config.project.directory.name}"
                        GroupRow(
                            title,
                            subtitle = run.state.label + if (run.state == WebRunState.Running) " · ${run.url}" else run.message.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty(),
                            subtitleLines = 2,
                            icon = Ph.Globe,
                            tile = if (run.state == WebRunState.Running) AppColors.tiles.lime else null,
                            trailing = {
                                if (run.state == WebRunState.Running) TextButton(onClick = { onPreview(run.url, title) }) { Text("Preview") }
                                if (run.terminal != null) IconButton(onClick = { WebRunSessions.logs(run); onOpenTerminal() }) {
                                    PhIcon(Ph.TerminalWindow, "Logs", size = 18.dp)
                                }
                                if (run.state.active && run.state != WebRunState.Stopping) IconButton(onClick = { WebRunSessions.stop(context, run) }) {
                                    PhIcon(Ph.Stop, "Stop", size = 18.dp, tint = colors.error)
                                }
                            },
                        )
                    }
                }
            }
            // Projects' own PostgreSQL, Redis and MariaDB (shows nothing when none run).
            item { dev.yurlungr.app.databases.DatabaseServersSection() }

            val links = TunnelSessions.tunnels.values.filter { it.active }
            if (links.isNotEmpty()) {
                item { SectionLabel("Public links") }
                item {
                    Group {
                        for (t in links) GroupRow(
                            t.url ?: "Starting…",
                            subtitle = "${t.label} · port ${t.port} · ${t.state.label}",
                            icon = Ph.Globe,
                            tile = if (t.state == TunnelState.Live) AppColors.tiles.sky else null,
                            trailing = {
                                TextButton(onClick = { sharePort = t.port to t.label }) { Text("Share") }
                                IconButton(onClick = { TunnelSessions.stop(context, t) }) { PhIcon(Ph.Stop, "Stop sharing", size = 18.dp, tint = colors.error) }
                            },
                        )
                    }
                }
            }
            item { SectionLabel("Listening ports") }
            item {
                Group {
                    val list = ports
                    when {
                        list == null -> GroupRow("Checking ports…", leading = { WaveDots(dotSize = 4.dp) }, trailing = {})
                        list.isEmpty() -> GroupRow("Nothing listening on the usual ports", subtitle = "Checked: Vite, Next.js, Astro, Angular, Django, Flask, Expo, PostgreSQL, Redis, MariaDB, Ollama and more.", trailing = {})
                        else -> for (port in list) {
                            val run = runs.firstOrNull { it.state.active && it.config.port == port }
                            val label = run?.let { "Web run · ${it.config.project.directory.name}" } ?: LocalServers.KNOWN_PORTS[port] ?: "Port $port"
                            val url = "http://127.0.0.1:$port"
                            GroupRow(
                                ":$port",
                                subtitle = label,
                                leading = { Box(Modifier.size(9.dp).clip(CircleShape).background(AppColors.status.success)) },
                                trailing = {
                                    IconButton(onClick = { sharePort = port to label }) {
                                        PhIcon(Ph.Globe, "Share public link", size = 16.dp, tint = if (TunnelSessions.get(port)?.active == true) AppColors.status.success else colors.onSurfaceVariant)
                                    }
                                    TextButton(onClick = { onPreview(url, label) }) { Text("Preview") }
                                    IconButton(onClick = { dev.yurlungr.app.runtime.RuntimeBridge.open(context, url) }) { PhIcon(Ph.ArrowUp, "Open in browser", size = 16.dp) }
                                },
                            )
                        }
                    }
                }
            }
            item {
                Text(
                    "Android doesn't let apps list every open port, so the usual development ports are checked. A server on another port still opens with Preview from its project.",
                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, modifier = Modifier.padding(horizontal = 6.dp),
                )
            }

            item { SectionLabel("Processes" + (processes?.let { " · ${it.size}" } ?: "")) }
            item {
                Group {
                    val list = processes
                    when {
                        list == null -> GroupRow("Reading processes…", leading = { WaveDots(dotSize = 4.dp) }, trailing = {})
                        list.isEmpty() -> GroupRow("No processes running in the runtime", trailing = {})
                        else -> for (p in list.take(60)) {
                            GroupRow(
                                p.name,
                                subtitle = "pid ${p.pid} · ${p.rssKb / 1024} MB · ${LocalServers.uptime(p.seconds)} · ${p.args}",
                                subtitleLines = 2,
                                trailing = { TextButton(onClick = { stopping = p }) { Text("Stop") } },
                            )
                        }
                    }
                }
            }
        }
    }

    stopping?.let { p ->
        AppSheet(
            "Stop ${p.name}?", onDismiss = { stopping = null }, subtitle = "pid ${p.pid}", icon = Ph.Stop,
            actions = {
                OutlinedButton(onClick = {
                    val ok = runCatching { Os.kill(p.pid, OsConstants.SIGKILL) }.isSuccess
                    Toast.makeText(context, if (ok) "Killed" else "Couldn't stop it", Toast.LENGTH_SHORT).show()
                    stopping = null; reload++
                }, modifier = Modifier.weight(1f)) { Text("Force kill") }
                Button(
                    onClick = {
                        val ok = runCatching { Os.kill(p.pid, OsConstants.SIGTERM) }.isSuccess
                        Toast.makeText(context, if (ok) "Asked it to stop" else "Couldn't stop it", Toast.LENGTH_SHORT).show()
                        stopping = null; reload++
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = colors.error, contentColor = colors.onError),
                    modifier = Modifier.weight(1f),
                ) { Text("Stop") }
            },
        ) {
            Text(p.args, fontFamily = FontFamily.Monospace, fontSize = 12.sp, maxLines = 6, overflow = TextOverflow.Ellipsis)
            Text(
                "Stop asks it to exit (SIGTERM); Force kill ends it at once. Stopping a shell closes its terminal, and stopping an agent ends its chat's session.",
                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
            )
        }
    }
}
