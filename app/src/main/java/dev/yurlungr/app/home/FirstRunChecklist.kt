package dev.yurlungr.app.home

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import dev.yurlungr.app.agents.Agent
import dev.yurlungr.app.agents.AgentStatus
import dev.yurlungr.app.agents.agentStatuses
import dev.yurlungr.app.chat.ChatSessions
import dev.yurlungr.app.projects.LastProject
import dev.yurlungr.app.runtime.RuntimePaths
import dev.yurlungr.app.ui.Ph
import dev.yurlungr.app.ui.PhIcon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** The first-run steps, in the order they're done. */
enum class FirstRunStep(val title: String, val hint: String) {
    Linux("Install the Linux environment", "The base system everything else runs in"),
    Runtime("Install Node.js", "A language runtime; agents and most web projects need it"),
    Agent("Set up an agent", "Install a coding agent and sign in"),
    Project("Create or clone a project", "Start from a template, or bring a repository"),
    FirstRun("Run it, or send a first chat", "Tap Run in the project, or ask an agent something"),
}

/** Where a step's button goes. */
sealed interface FirstRunAction {
    data object SystemChecks : FirstRunAction
    data object InstallNode : FirstRunAction
    data object Agents : FirstRunAction
    data class SignIn(val agent: Agent) : FirstRunAction
    data object Projects : FirstRunAction
    data class OpenProject(val path: String) : FirstRunAction
    data object Tour : FirstRunAction
}

/** What's true on disk and in the app; read off the main thread except [chatted]. */
data class FirstRunFacts(
    val linux: Boolean,
    val node: Boolean,
    val agents: List<AgentStatus>,
    /** Folders in ~/projects. */
    val projectFolders: List<String>,
    val lastProject: String?,
    val ran: Boolean,
    val chatted: Boolean,
)

data class FirstRunStepState(val step: FirstRunStep, val done: Boolean, val action: FirstRunAction, val label: String)

/** The pure part: which steps are done and what each button does. */
object FirstRunSteps {
    /** "scratch" is where quick chats land; it isn't a project the user made. */
    private val NOT_PROJECTS = setOf("scratch")

    fun hasProject(folders: List<String>): Boolean = folders.any { !it.startsWith(".") && it !in NOT_PROJECTS }

    /** An agent counts once it's installed and not known to be signed out (some can't be checked). */
    fun agentReady(agents: List<AgentStatus>): Boolean = agents.any { it.installed && it.signedIn != false }

    fun agentAction(agents: List<AgentStatus>): Pair<FirstRunAction, String> =
        agents.firstOrNull { it.installed && it.signedIn == false }
            ?.let { FirstRunAction.SignIn(it.agent) to "Sign in to ${it.agent.displayName}" }
            ?: (FirstRunAction.Agents to "Choose an agent")

    fun states(f: FirstRunFacts): List<FirstRunStepState> {
        val (agentAction, agentLabel) = agentAction(f.agents)
        val project = hasProject(f.projectFolders)
        val open = f.lastProject?.let { FirstRunAction.OpenProject(it) to "Open project" }
            ?: (FirstRunAction.Projects to "Open projects")
        return listOf(
            FirstRunStepState(FirstRunStep.Linux, f.linux, FirstRunAction.SystemChecks, "Install"),
            FirstRunStepState(FirstRunStep.Runtime, f.node, FirstRunAction.InstallNode, "Install Node.js"),
            FirstRunStepState(FirstRunStep.Agent, agentReady(f.agents), agentAction, agentLabel),
            FirstRunStepState(FirstRunStep.Project, project, FirstRunAction.Projects, "New project"),
            FirstRunStepState(FirstRunStep.FirstRun, f.ran || f.chatted, open.first, open.second),
        )
    }

    fun allDone(states: List<FirstRunStepState>) = states.isNotEmpty() && states.all { it.done }

    /** The step to do next: the first one not done (later ones usually depend on it). */
    fun next(states: List<FirstRunStepState>): FirstRunStepState? = states.firstOrNull { !it.done }

    /** Shown until dismissed or finished once; finishing is remembered so it never comes back. */
    fun visible(dismissed: Boolean, finished: Boolean, states: List<FirstRunStepState>?): Boolean =
        !dismissed && !finished && states != null && !allDone(states)
}

object FirstRunPrefs {
    private const val PREFS = "first_run"
    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun dismissed(context: Context) = prefs(context).getBoolean("dismissed", false)
    fun finished(context: Context) = prefs(context).getBoolean("finished", false)
    fun ran(context: Context) = prefs(context).getBoolean("ran", false)
    fun collapsed(context: Context) = prefs(context).getBoolean("collapsed", false)

    fun dismiss(context: Context) = prefs(context).edit { putBoolean("dismissed", true) }
    fun finish(context: Context) = prefs(context).edit { putBoolean("finished", true) }
    fun setCollapsed(context: Context, value: Boolean) = prefs(context).edit { putBoolean("collapsed", value) }

    /** A project was run once (Run in a project); called from the web-run starter. */
    fun markRan(context: Context) {
        if (!ran(context)) prefs(context).edit { putBoolean("ran", true) }
    }
}

private class Loaded(val states: List<FirstRunStepState>, val dismissed: Boolean, val finished: Boolean, val collapsed: Boolean)

/**
 * Getting started, on the start screen: what's left before the first real piece of work, each
 * with a button to the exact place to do it. Recomputed whenever the app resumes ([refreshTick]).
 */
@Composable
fun FirstRunChecklist(refreshTick: Int, onAction: (FirstRunAction) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val chatted = ChatSessions.chats.isNotEmpty()
    var loaded by remember { mutableStateOf<Loaded?>(null) }
    var hidden by remember { mutableStateOf(false) }
    LaunchedEffect(refreshTick, chatted) {
        loaded = withContext(Dispatchers.IO) {
            val paths = RuntimePaths(context)
            val linux = paths.isBootstrapInstalled
            val facts = FirstRunFacts(
                linux = linux,
                node = linux && File(paths.bin, "node").exists(),
                agents = if (linux) agentStatuses(paths) else emptyList(),
                projectFolders = paths.projects.listFiles { f -> f.isDirectory }?.map { it.name }.orEmpty(),
                lastProject = LastProject.get(context)?.path,
                ran = FirstRunPrefs.ran(context),
                chatted = chatted,
            )
            val states = FirstRunSteps.states(facts)
            // Once everything has been done, it's done for good (deleting a chat doesn't bring it back).
            if (FirstRunSteps.allDone(states)) FirstRunPrefs.finish(context)
            Loaded(states, FirstRunPrefs.dismissed(context), FirstRunPrefs.finished(context), FirstRunPrefs.collapsed(context))
        }
    }
    val data = loaded ?: return
    AnimatedVisibility(!hidden && FirstRunSteps.visible(data.dismissed, data.finished, data.states), modifier) {
        ChecklistCard(
            data.states,
            initiallyCollapsed = data.collapsed,
            onAction = onAction,
            onCollapse = { FirstRunPrefs.setCollapsed(context, it) },
            onDismiss = {
                hidden = true
                FirstRunPrefs.dismiss(context)
            },
        )
    }
}

@Composable
private fun ChecklistCard(
    states: List<FirstRunStepState>,
    initiallyCollapsed: Boolean,
    onAction: (FirstRunAction) -> Unit,
    onCollapse: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    var collapsed by remember { mutableStateOf(initiallyCollapsed) }
    val done = states.count { it.done }
    val next = FirstRunSteps.next(states)
    Column(
        Modifier
            .widthIn(max = 520.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(colors.surfaceContainer)
            .padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 10.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable { collapsed = !collapsed; onCollapse(collapsed) }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PhIcon(Ph.ListChecks, null, size = 20.dp, tint = colors.primary)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("Getting started", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (collapsed && next != null) "$done of ${states.size} done · next: ${next.step.title.lowercase()}" else "$done of ${states.size} done",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            PhIcon(if (collapsed) Ph.CaretDown else Ph.CaretUp, if (collapsed) "Show steps" else "Hide steps", size = 16.dp, tint = colors.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
        }
        LinearProgressIndicator(
            progress = { done.toFloat() / states.size },
            modifier = Modifier.fillMaxWidth().padding(end = 8.dp, bottom = 4.dp),
        )
        if (!collapsed) {
            for (s in states) StepRow(s, isNext = s == next, onAction = onAction)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { onAction(FirstRunAction.Tour) }) { Text("Quick tour") }
                TextButton(onClick = onDismiss) { Text("I know my way around") }
            }
        }
    }
}

@Composable
private fun StepRow(s: FirstRunStepState, isNext: Boolean, onAction: (FirstRunAction) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            if (s.done) PhIcon(Ph.CheckCircle, "Done", size = 22.dp, tint = colors.primary)
            else PhIcon(Ph.Circle, "Not done", size = 22.dp, tint = colors.outline)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                s.step.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isNext) FontWeight.SemiBold else FontWeight.Normal,
                color = if (s.done) colors.onSurfaceVariant else colors.onSurface,
                textDecoration = if (s.done) TextDecoration.LineThrough else null,
            )
            if (!s.done) Text(s.step.hint, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
        if (!s.done) {
            Spacer(Modifier.width(8.dp))
            if (isNext) FilledTonalButton(onClick = { onAction(s.action) }) { Text(s.label) }
            else TextButton(onClick = { onAction(s.action) }) { Text(s.label) }
        }
    }
}
