package dev.yurlungr.app.web

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.yurlungr.app.projects.FailureHelpCard
import dev.yurlungr.app.projects.FailureReport

/** What a web run ran, as a person would type it. */
internal fun webRunCommand(config: WebRunConfig): String = when (config.mode) {
    WebLaunchMode.Custom -> config.customCommand
    WebLaunchMode.Script -> "${config.manager.executable} run ${config.script}"
    WebLaunchMode.Framework -> "${config.project.framework} dev server (started by the app on port ${config.port})"
}

/** Under a failed web run: known phone fixes from its log, and Ask the agent. */
@Composable
internal fun WebRunFailureHelp(run: WebRun) {
    val failed = !run.busy && run.state in setOf(WebRunState.Failed, WebRunState.NeedsSetup, WebRunState.CompatibilityFailure)
    if (!failed) return
    // The log is the run's terminal; the emulator is only touched on the main thread.
    val log = remember(run.id, run.state, run.finalized) {
        runCatching { run.terminal?.emulator?.screen?.transcriptText }.getOrNull().orEmpty()
    }
    val report = remember(run.id, log, run.message) {
        FailureReport(
            "Running the web app", webRunCommand(run.config),
            listOf(run.config.project.framework, run.config.manager.executable), log, run.message,
        )
    }
    FailureHelpCard(run.config.project.directory, "Help with this failure", report, run.config.manager.executable,
        Modifier.padding(start = 14.dp, end = 14.dp, bottom = 12.dp))
}
