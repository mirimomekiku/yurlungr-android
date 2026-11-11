package dev.yurlungr.app.web

import android.content.Context
import androidx.compose.runtime.*
import com.termux.terminal.TerminalSession
import dev.yurlungr.app.chat.ChatSessions
import dev.yurlungr.app.projects.Git
import dev.yurlungr.app.runtime.RuntimeBridge
import dev.yurlungr.app.runtime.RuntimePaths
import dev.yurlungr.app.terminal.TerminalSessions
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

internal enum class WebRunState { Installing, Starting, Running, Stopping, Stopped, Failed;
    val active get() = this in setOf(Installing, Starting, Running, Stopping)
}
internal class WebRun(val root: File, val config: WebRunConfig, val id: String, val directory: File) {
    var state by mutableStateOf(WebRunState.Starting)
    var message by mutableStateOf("")
    var terminal: TerminalSession? = null
    var closeAfterStop = false
    var restartAfterStop = false
    var finalized = false
    val url get() = "http://127.0.0.1:${config.port}"
}

/** One managed server per project. Status comes from its unique supervisor, never terminal text. */
internal object WebRunSessions {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val runs = mutableStateMapOf<String, WebRun>()
    private const val STALE_RUN_GRACE_MS = 15_000L
    fun get(root: File): WebRun? = runs[root.canonicalPath]

    /** Every run this session, active or finished, for the Servers screen. */
    fun all(): List<WebRun> = runs.values.toList()

    /** For refreshing the home-screen widget and the servers tile when a run changes state. */
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        // Capture old run directories before the UI can start a new run. Never adopt stale PIDs.
        val previous = File(context.cacheDir,"web-runs").listFiles().orEmpty()
            .filter { it.isDirectory && runCatching { UUID.fromString(it.name) }.isSuccess }
        scope.launch(Dispatchers.IO) {
            previous.forEach { directory -> runCatching { File(directory,"stop").writeText(directory.name) } }
            // Give any surviving supervisor time to see "stop", then remove the folders: they only
            // hold config, status and the stop file (logs live in the terminal), and pile up otherwise.
            delay(STALE_RUN_GRACE_MS)
            previous.forEach { directory -> runCatching { directory.deleteRecursively() } }
        }
    }

    fun start(context: Context, root: File, config: WebRunConfig): WebRun {
        val app = context.applicationContext
        get(root)?.takeIf { it.state.active }?.let { return it }
        val id = UUID.randomUUID().toString()
        val run = WebRun(root.canonicalFile, config, id, File(app.cacheDir,"web-runs/$id"))
        runs[root.canonicalPath] = run // reserve before any asynchronous work
        scope.launch {
            try {
                val paths = RuntimePaths(app)
                withContext(Dispatchers.IO) {
                    config.validate()
                    check(File(paths.bin,"node").canExecute()) { "Node.js isn't installed. Use Install Node.js in Run setup." }
                    // canExecute, like node: a file that's there but can't run is just as unusable.
                    if (config.project.kind != WebKind.Static) check(File(paths.bin,config.manager.executable).canExecute()) {
                        "${config.manager.executable} isn't installed. Use the package-manager setup action."
                    }
                    run.directory.mkdirs()
                    app.assets.open("web-runner.cjs").use { input -> File(run.directory,"runner.cjs").outputStream().use { input.copyTo(it) } }
                    File(run.directory,"config.json").writeText(config.toJson(id,File(paths.bin,"node"),paths.bash).toString())
                }
                if (run.state == WebRunState.Stopping) { finish(app,run); return@launch }
                WebRunPreferences.save(app,root,config)
                val command = "exec ${Git.shellQuote(File(paths.bin,"node").path)} ${Git.shellQuote(File(run.directory,"runner.cjs").path)} ${Git.shellQuote(File(run.directory,"config.json").path)}"
                val terminal = TerminalSessions.create(app,command,"Web · ${config.project.directory.name}", keepShell = false, workingDirectory = config.project.directory)
                run.terminal = terminal
                TerminalSessions.manage(terminal, TerminalSessions.ManagedLifecycle(
                    end = { stop(app,run) },
                    close = { run.closeAfterStop = true; stop(app,run) },
                    restart = { restart(app,run) },
                    exited = { scope.launch { update(run); finish(app,run) } },
                ))
                // PTYs otherwise start only when attached to TerminalView. Web runs also start off-screen.
                try {
                    terminal.updateSize(80,24,8,16)
                } catch (e: Exception) {
                    // No process exists (e.g. no free file descriptors): drop the tab rather than leave
                    // one that looks running and can't be stopped.
                    run.terminal = null
                    TerminalSessions.closeDirect(app, terminal)
                    throw IllegalStateException("Couldn't start the server's terminal: ${e.message ?: e}", e)
                }
                var browserOpened = false
                val startTime = System.currentTimeMillis()
                while (!run.finalized) {
                    update(run)
                    if (run.state == WebRunState.Running && !browserOpened) {
                        val owned = withContext(Dispatchers.IO) { ownsListener(run) }
                        if (owned) {
                            browserOpened = true
                            if (config.openBrowser && ChatSessions.appVisible) RuntimeBridge.open(app,run.url)
                        }
                    }
                    if (!terminal.isRunning) { finish(app,run); break }
                    if (System.currentTimeMillis()-startTime > 20*60_000 && run.state in setOf(WebRunState.Starting, WebRunState.Installing)) {
                        run.message = "Setup took too long. Check Logs and retry."
                        run.closeAfterStop = false
                        stop(app,run)
                    }
                    delay(500)
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                run.state = WebRunState.Failed
                run.message = error.message ?: "Couldn't start local server."
                run.finalized = true
            }
        }
        return run
    }

    fun stop(context: Context, run: WebRun) {
        if (run.finalized) {
            if (run.closeAfterStop) run.terminal?.let { TerminalSessions.closeDirect(context,it) }
            return
        }
        run.state = WebRunState.Stopping
        scope.launch(Dispatchers.IO) {
            run.directory.mkdirs()
            File(run.directory,"stop").writeText(run.id)
        }
    }

    fun restart(context: Context, run: WebRun) {
        get(run.root)?.takeIf { it !== run && it.state.active }?.let { existing ->
            existing.terminal?.let(TerminalSessions::select)
            return
        }
        run.restartAfterStop = true
        run.closeAfterStop = true
        if (run.finalized) {
            run.terminal?.let { TerminalSessions.closeDirect(context,it) }
            start(context,run.root,run.config.copy(install = false))
        } else stop(context,run)
    }

    fun stopAll(context: Context) {
        runs.values.toList().filter { it.state.active }.forEach { run -> run.closeAfterStop = true; stop(context,run) }
    }

    fun logs(run: WebRun) { run.terminal?.let(TerminalSessions::select) }

    fun open(context: Context, run: WebRun) {
        scope.launch {
            if (run.state == WebRunState.Running && withContext(Dispatchers.IO) { ownsListener(run) }) RuntimeBridge.open(context,run.url)
            else { run.message = "Server isn't ready. Check Logs." }
        }
    }

    private suspend fun update(run: WebRun) {
        val status = withContext(Dispatchers.IO) { runCatching {
            val file = File(run.directory,"status.json")
            if (file.length() > 8192) null else Json.parseToJsonElement(file.readText()) as? JsonObject
        }.getOrNull() } ?: return
        if (status["id"]?.jsonPrimitive?.contentOrNull != run.id) return
        val state = runCatching { WebRunState.valueOf(status["state"]!!.jsonPrimitive.content) }.getOrNull() ?: return
        // A stale status update must not undo a user-requested stop.
        val before = run.state
        if (run.state != WebRunState.Stopping || !state.active) run.state = state
        if (run.state != before) appContext?.let(dev.yurlungr.app.widgets.OpenIn::refresh)
        run.message = status["message"]?.jsonPrimitive?.contentOrNull.orEmpty()
    }

    private fun finish(context: Context, run: WebRun) {
        if (run.finalized) return
        run.finalized = true
        dev.yurlungr.app.widgets.OpenIn.refresh(context)
        if (run.state.active) {
            run.state = if (run.state == WebRunState.Stopping) WebRunState.Stopped else WebRunState.Failed
            if (run.state == WebRunState.Failed && run.message.isBlank()) run.message = "Server process exited. Check Logs."
        }
        if (run.closeAfterStop) run.terminal?.let { TerminalSessions.closeDirect(context,it) }
        if (run.restartAfterStop) start(context,run.root,run.config.copy(install = false))
    }

    private fun ownsListener(run: WebRun): Boolean = runCatching {
        val connection = URL("${run.url}/.yurlungr/health/${run.id}").openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 1500; connection.readTimeout = 1500
            connection.instanceFollowRedirects = false
            if (connection.responseCode != 200) false else {
                val status = Json.parseToJsonElement(connection.inputStream.bufferedReader().use { it.readText() }).jsonObject
                status["id"]?.jsonPrimitive?.contentOrNull == run.id && status["state"]?.jsonPrimitive?.contentOrNull == "Running"
            }
        } finally { connection.disconnect() }
    }.getOrDefault(false)
}
