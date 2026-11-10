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
import dev.yurlungr.app.runtime.RuntimePaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface InstallState {
    data object Idle : InstallState
    data class Running(val message: String, val fraction: Float?, val log: List<String>) : InstallState
    data class Failed(val error: String) : InstallState
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
    var install by mutableStateOf<InstallState>(InstallState.Idle)
        private set

    private var installJob: Job? = null

    val bootstrapInstalled: Boolean
        get() = RuntimePaths(getApplication()).isBootstrapInstalled

    init {
        refresh()
    }

    /** Last results of the (slower) tool probe; kept across quick refreshes. */
    private var toolChecks: List<Check> = emptyList()
    private var deepPending = false

    /**
     * Re-runs the checks. [deep] also runs every tool (git, node, agents…) to verify it works:
     * done when System checks is opened or refreshed, not on every app resume.
     */
    fun refresh(deep: Boolean = false) {
        if (checking) {
            if (deep) deepPending = true
            return
        }
        checking = true
        viewModelScope.launch {
            try {
                val base = DoctorChecks.runAll(getApplication())
                if (deep) toolChecks = DoctorChecks.tools(RuntimePaths(getApplication()))
                checks = base + toolChecks
                report = withContext(Dispatchers.IO) { DoctorChecks.readReport(getApplication()) }
                checkedAt = System.currentTimeMillis()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                // The screen that diagnoses a broken runtime must never crash because of it.
                android.util.Log.e("SetupViewModel", "System checks failed", e)
                checks = listOf(
                    Check("checks", "System checks", CheckStatus.Fail, "Couldn't run the checks: ${e.message ?: e}", fix = Fix.InstallBootstrap),
                )
            } finally {
                checking = false
                if (deepPending) {
                    deepPending = false
                    refresh(deep = true)
                }
            }
        }
    }

    /**
     * Replaces a broken base system. Everything running from it is stopped first (the prefix is
     * deleted underneath them); home, projects and chats are kept.
     */
    fun reinstallBootstrap() {
        val app = getApplication<Application>()
        dev.yurlungr.app.chat.ChatSessions.stopAll()
        dev.yurlungr.app.web.WebRunSessions.stopAll(app)
        dev.yurlungr.app.terminal.TerminalSessions.closeAll(app)
        installBootstrap()
    }

    /** Rewrites the app's scripts and shims in the runtime (after a failed launch-time refresh). */
    fun repairRuntime() {
        val app = getApplication<Application>()
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { RuntimeAssets.install(app, RuntimePaths(app)) } }
            refresh(deep = true)
        }
    }

    fun installBootstrap() {
        if (installJob?.isActive == true) return
        val app = getApplication<Application>()
        install = InstallState.Running("Starting…", null, emptyList())
        installJob = viewModelScope.launch {
            try {
                BootstrapInstaller(app, RuntimePaths(app)).install { message, fraction ->
                    viewModelScope.launch(Dispatchers.Main) {
                        // Updates still queued after completion/failure must not resurrect the progress UI.
                        val running = install as? InstallState.Running ?: return@launch
                        val log = if (running.log.lastOrNull() == message) running.log else (running.log + message).takeLast(200)
                        install = InstallState.Running(message, fraction, log)
                    }
                }
                install = InstallState.Idle
                (app as? dev.yurlungr.app.YurlungrApp)?.onRuntimeReady()
            } catch (e: Exception) {
                install = InstallState.Failed(e.message ?: e.toString())
            }
            refresh(deep = true)
        }
    }

    fun setPhantomFixDone(done: Boolean) {
        DoctorChecks.markPhantomFixDone(getApplication(), done)
        refresh()
    }
}
