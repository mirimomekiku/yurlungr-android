package dev.yurlungr.app.setup

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.yurlungr.app.runtime.BootstrapInstaller
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

    fun refresh() {
        if (checking) return
        checking = true
        viewModelScope.launch {
            try {
                checks = DoctorChecks.runAll(getApplication())
                report = withContext(Dispatchers.IO) { DoctorChecks.readReport(getApplication()) }
                checkedAt = System.currentTimeMillis()
            } finally {
                checking = false
            }
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
            refresh()
        }
    }

    fun setPhantomFixDone(done: Boolean) {
        DoctorChecks.markPhantomFixDone(getApplication(), done)
        refresh()
    }
}
