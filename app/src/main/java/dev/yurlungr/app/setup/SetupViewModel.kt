package dev.yurlungr.app.setup

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.yurlungr.app.runtime.BootstrapInstaller
import dev.yurlungr.app.runtime.RuntimeAssets
import dev.yurlungr.app.runtime.RestoreList
import dev.yurlungr.app.runtime.RuntimePaths
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface InstallState {
    data object Idle : InstallState
    data class Running(val message: String, val fraction: Float?, val log: List<String>) : InstallState
    data class Failed(val error: String) : InstallState
}

/**
 * The base-system install, owned by the app rather than a screen: leaving the screen (or the
 * app) mid-install must not cancel it halfway through replacing the prefix.
 */
object BootstrapJob {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    /** How long a reinstall waits for database servers to stop (their own stop allows 60 s). */
    private const val DB_STOP_WAIT_MS = 75_000L

    var state by mutableStateOf<InstallState>(InstallState.Idle)
        private set

    val isRunning: Boolean get() = job?.isActive == true

    /**
     * Installs (or reinstalls) the base system. With an existing one, everything running from it
     * is stopped first: the prefix is replaced underneath them. Home, projects and chats are kept.
     * New terminals, chats and setups can't start meanwhile (the install marker blocks them).
     */
    fun start(app: Application, onFinished: () -> Unit) {
        if (isRunning) return
        val stopping = RuntimePaths(app).hasBaseSystem
        if (stopping) {
            dev.yurlungr.app.chat.ChatSessions.stopAll()
            dev.yurlungr.app.web.WebRunSessions.stopAll(app)
            // Public links (cloudflared) run in terminal tabs; stopped as links, so none reads as live.
            dev.yurlungr.app.web.TunnelSessions.stopAll(app)
            dev.yurlungr.app.terminal.TerminalSessions.closeAll(app)
            // Database servers are daemons (no terminal): stopped cleanly with their own script,
            // which needs the old system, so the install waits for them below.
            dev.yurlungr.app.databases.ProjectDatabases.stopAll(app)
        }
        state = InstallState.Running(if (stopping) "Stopping what runs from the old system…" else "Starting…", null, emptyList())
        job = scope.launch {
            try {
                if (stopping) {
                    // Bounded: a server that won't stop must not block the repair (the old system
                    // is moved aside, not deleted from under it while the install runs).
                    kotlinx.coroutines.withTimeoutOrNull(DB_STOP_WAIT_MS) {
                        while (dev.yurlungr.app.databases.ProjectDatabases.busy.isNotEmpty()) kotlinx.coroutines.delay(250)
                    }
                }
                BootstrapInstaller(app, RuntimePaths(app)).install { message, fraction ->
                    scope.launch {
                        // Updates still queued after completion/failure must not resurrect the progress UI.
                        val running = state as? InstallState.Running ?: return@launch
                        val log = if (running.log.lastOrNull() == message) running.log else (running.log + message).takeLast(200)
                        state = InstallState.Running(message, fraction, log)
                    }
                }
                state = InstallState.Idle
                (app as? dev.yurlungr.app.YurlungrApp)?.onRuntimeReady()
            } catch (e: Exception) {
                state = InstallState.Failed(e.message ?: e.toString())
            }
            onFinished()
        }
    }
}

class SetupViewModel(app: Application) : AndroidViewModel(app) {
    var checks by mutableStateOf<List<Check>>(emptyList())
        private set
    var report by mutableStateOf<List<ReportLine>>(emptyList())
        private set
    var checking by mutableStateOf(false)
        private set
    /** When the checks last finished (epoch millis), 0 before the first run. */
    var checkedAt by mutableLongStateOf(0L)
        private set
    val install: InstallState get() = BootstrapJob.state

    val bootstrapInstalled: Boolean
        get() = RuntimePaths(getApplication()).isBootstrapInstalled

    /** What a base-system reinstall removed and can be reinstalled ([RestoreList]), or null. */
    var restore by mutableStateOf<RestoreList.Plan?>(null)
        private set

    init {
        // Old systems a reinstall set aside but the app closed before deleting: free that space.
        if (!BootstrapJob.isRunning) BootstrapInstaller.deleteOldPrefixes(RuntimePaths(app))
        refresh()
    }

    /** Last results of the (slower) tool probe; kept across quick refreshes. */
    private var toolChecks: List<Check> = emptyList()
    private var deepPending = false
    private var lastDeepAt = 0L

    /**
     * Re-runs the checks. [deep] also runs every tool (git, node, agents…) to verify it works:
     * done when System checks is opened or refreshed, not on every app resume. A deep check
     * within the last 30 s is reused unless [force] (the refresh button).
     */
    fun refresh(deep: Boolean = false, force: Boolean = false) {
        val wantDeep = deep && (force || System.currentTimeMillis() - lastDeepAt > 30_000)
        if (checking) {
            if (wantDeep) deepPending = true
            return
        }
        checking = true
        viewModelScope.launch {
            try {
                // Off the main thread: file reads, StatFs and system-service calls.
                // Quick refreshes (every return to the app) leave the package update count to the cache.
                val base = withContext(Dispatchers.IO) { DoctorChecks.runAll(getApplication(), deep = wantDeep) }
                restore = withContext(Dispatchers.IO) {
                    val paths = RuntimePaths(getApplication())
                    if (paths.isBootstrapInstalled && !BootstrapJob.isRunning) RestoreList.pending(paths) else null
                }
                if (wantDeep) {
                    toolChecks = DoctorChecks.tools(RuntimePaths(getApplication()))
                    lastDeepAt = System.currentTimeMillis()
                } else if (!RuntimePaths(getApplication()).isBootstrapInstalled) {
                    toolChecks = emptyList() // stale after a reinstall started
                }
                checks = base + toolChecks
                report = withContext(Dispatchers.IO) { DoctorChecks.readReport(getApplication()) }
                checkedAt = System.currentTimeMillis()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                // The screen that diagnoses a broken runtime must never crash because of it.
                android.util.Log.e("SetupViewModel", "System checks failed", e)
                // No fix offered: a failure here says nothing about the base system being broken.
                checks = listOf(
                    Check("checks", "System checks", CheckStatus.Fail, "Couldn't run the checks: ${e.message ?: e}. Tap refresh to retry."),
                )
            } finally {
                checking = false
                if (deepPending) {
                    deepPending = false
                    refresh(deep = true, force = true)
                }
            }
        }
    }

    /** Replaces a broken base system (see [BootstrapJob.start]: running sessions are stopped first). */
    fun reinstallBootstrap() = installBootstrap()

    /** Rewrites the app's scripts and shims in the runtime (after a failed launch-time refresh). */
    fun repairRuntime() {
        val app = getApplication<Application>()
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { RuntimeAssets.install(app, RuntimePaths(app)) } }
            refresh(deep = true, force = true)
        }
    }

    fun installBootstrap() {
        BootstrapJob.start(getApplication()) { refresh(deep = true, force = true) }
        refresh()
    }

    /** The terminal command that reinstalls [restore], or null when there's nothing to. */
    fun restoreCommand(): String? = restore?.let { RestoreList.command(RestoreList.file(RuntimePaths(getApplication())), it) }

    /** Forgets what was installed before the reinstall: the card goes away. */
    fun dismissRestore() {
        restore = null
        val app = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) { RestoreList.dismiss(RuntimePaths(app)) }
    }

    fun setPhantomFixDone(done: Boolean) {
        DoctorChecks.markPhantomFixDone(getApplication(), done)
        refresh()
    }
}
