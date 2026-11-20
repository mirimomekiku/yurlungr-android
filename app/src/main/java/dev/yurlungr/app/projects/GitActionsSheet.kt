package dev.yurlungr.app.projects

import androidx.core.net.toUri
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import dev.yurlungr.app.agents.Agent
import dev.yurlungr.app.ui.AppColors
import dev.yurlungr.app.ui.AppSheet
import dev.yurlungr.app.ui.Chip
import dev.yurlungr.app.ui.FlowBar
import dev.yurlungr.app.ui.Ph
import dev.yurlungr.app.ui.PhIcon
import dev.yurlungr.app.ui.SectionLabel
import dev.yurlungr.app.ui.Tile
import kotlinx.coroutines.launch
import java.io.File

/** Which agent the AI actions use; the last one picked is remembered. */
private object GitAiAgent {
    private const val PREFS = "git_actions"

    fun get(context: Context, installed: List<Agent>): Agent? {
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("agent", null)
        return installed.firstOrNull { it.name == saved } ?: installed.firstOrNull()
    }

    fun set(context: Context, agent: Agent) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putString("agent", agent.name) }
}

/**
 * Git routines for the project, one tap each: get the latest changes (stash → pull → stash pop),
 * sync, push, undo the last commit; and the same with an agent finishing the job (resolving
 * merge conflicts, writing commit messages, reviewing changes).
 */
@Composable
fun GitActionsSheet(
    dir: File,
    git: Git,
    status: GitStatus,
    installedAgents: List<Agent>,
    onDismiss: () -> Unit,
    /** Something changed the repository: refresh the project's status. */
    onChanged: () -> Unit,
    /** Starts a chat in this project with [agent] and sends [prompt] as soon as it's ready. */
    onAgentTask: (agent: Agent, prompt: String) -> Unit,
    onOpenBranches: () -> Unit,
    /** Opens the pull requests screen (a number: that one); null when the remote isn't on GitHub. */
    onPullRequests: ((Int?) -> Unit)? = null,
    /** A merge, rebase, cherry-pick or revert waiting to be finished: routines wait until it's done. */
    operation: GitOperation? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val routines = remember(git) { GitRoutines(git, AppStashTracker(context.applicationContext)) }
    // Routines run in GitJobs (closing the project mustn't stop one between stash and pop); only an open sheet goes on to the agent.
    var open by remember { mutableStateOf(true) }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { open = false } }
    var agent by remember { mutableStateOf(GitAiAgent.get(context, installedAgents)) }
    var running by remember { mutableStateOf<String?>(null) }
    val steps = remember { mutableStateListOf<String>() }
    var outcome by remember { mutableStateOf<RoutineOutcome?>(null) }

    val conflicts = status.conflicts
    val hasChanges = status.changes.isNotEmpty()
    val canPull = status.hasOrigin && status.upstream != null
    // Nothing else while one is half done: a pull or an undo in the middle of a rebase makes it worse.
    val idle = operation == null

    fun run(label: String, aiFollowUp: Boolean = false, block: suspend ((String) -> Unit) -> RoutineOutcome) {
        if (running != null) return
        running = label
        steps.clear()
        outcome = null
        GitJobs.launch {
            val result = try {
                block { step -> steps += step }
            } finally {
                running = null
                if (open) onChanged()
            }
            outcome = result
            // The AI variant goes straight on to the agent when there's something to resolve.
            val a = agent
            if (open && aiFollowUp && result is RoutineOutcome.Conflicts && a != null) {
                onAgentTask(a, GitRoutines.conflictPrompt(result, status.branch, status.upstream))
            }
        }
    }

    fun ask(prompt: String) {
        val a = agent ?: return
        onAgentTask(a, prompt)
    }

    AppSheet(
        "Git actions",
        onDismiss = onDismiss,
        subtitle = listOfNotNull(
            status.branch?.let { "On $it" },
            status.upstream?.let { "tracking $it" },
            if (hasChanges) "${status.changes.size} uncommitted" else "clean",
            if (status.ahead > 0) "${status.ahead} to push" else null,
            if (status.behind > 0) "${status.behind} to pull" else null,
        ).joinToString(" · "),
        icon = Ph.GitBranch,
        tile = AppColors.tiles.lilac,
        dismissable = running == null,
    ) {
        // Progress and result of the last routine.
        if (running != null || outcome != null) {
            RoutineCard(running, steps, outcome, agent, onFixWithAi = { c ->
                agent?.let { onAgentTask(it, GitRoutines.conflictPrompt(c, status.branch, status.upstream)) }
            })
        }

        if (conflicts.isNotEmpty() || operation != null) {
            Text(
                listOfNotNull(
                    operation?.let { "A ${it.label} is in progress: finish or abort it from the Changes tab first." },
                    conflicts.takeIf { it.isNotEmpty() }?.let {
                        "${it.size} file${if (it.size == 1) " has" else "s have"} unresolved conflicts: ${it.take(3).joinToString()}${if (it.size > 3) "…" else ""}"
                    },
                ).joinToString("\n"),
                style = MaterialTheme.typography.bodySmall,
                color = AppColors.status.onWarningContainer,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(AppColors.status.warningContainer).padding(12.dp),
            )
        }
        if (status.detached) {
            Text(
                "Detached HEAD: you're on a commit, not a branch, so there's nothing to push or sync. Create a branch (Branches & stashes) to keep commits made here.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionLabel("Quick actions")
        ActionRow(
            Ph.CloudArrowDown, "Get latest changes",
            if (hasChanges) "Saves your changes, pulls, then puts them back (stash → pull → stash pop)" else "Pulls new commits from ${status.upstream ?: "the remote"}",
            enabled = running == null && canPull && conflicts.isEmpty() && idle, tile = AppColors.tiles.sky,
        ) { run("Getting the latest changes") { routines.getLatest(dir, it) } }
        ActionRow(
            Ph.ArrowsClockwise, "Sync",
            "Gets the latest changes, then pushes your commits",
            enabled = running == null && canPull && conflicts.isEmpty() && idle, tile = AppColors.tiles.sky,
        ) { run("Syncing") { routines.sync(dir, it) } }
        if (!status.detached && (status.ahead > 0 || (status.hasOrigin && status.upstream == null))) {
            ActionRow(
                Ph.CloudArrowUp, if (status.upstream == null) "Publish branch" else "Push ${status.ahead} commit${if (status.ahead == 1) "" else "s"}",
                if (status.upstream == null) "Sends this branch to the remote for the first time" else "Sends your commits to ${status.upstream}",
                enabled = running == null && conflicts.isEmpty() && idle, tile = AppColors.tiles.lime,
            ) {
                run("Pushing") { step ->
                    step("Sending your commits (git push)…")
                    val r = git.push(dir, status)
                    if (r.ok) RoutineOutcome.Done("Pushed.") else RoutineOutcome.Failed(r.describe("git push"))
                }
            }
        }
        if (!status.unborn) {
            ActionRow(
                Ph.ArrowCounterClockwise, "Undo last commit",
                "Takes back the last commit (if not pushed) and keeps its changes",
                enabled = running == null && conflicts.isEmpty() && idle, tile = AppColors.tiles.peach,
            ) { run("Undoing the last commit") { routines.undoLastCommit(dir) } }
        }
        // Pull request of this branch: open the existing one, or create it once the branch is pushed.
        var pr by remember(status.branch, status.upstream) { mutableStateOf<GitPullRequest?>(null) }
        var prError by remember(status.branch, status.upstream) { mutableStateOf<String?>(null) }
        var prLoaded by remember(status.branch, status.upstream) { mutableStateOf(false) }
        androidx.compose.runtime.LaunchedEffect(status.branch, status.upstream, status.ahead) {
            if (!status.hasOrigin || status.branch == null || status.unborn) return@LaunchedEffect
            git.pullRequest(dir).onSuccess { pr = it; prError = null }.onFailure { prError = it.message }
            prLoaded = true
        }
        if (status.hasOrigin && !status.unborn) {
            val current = pr
            fun openUrl(url: String) = runCatching {
                context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, url.toUri()).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            when {
                current != null -> ActionRow(
                    Ph.GitMerge, "Open pull request #${current.number}",
                    "${current.title} · ${current.state.lowercase()}${if (current.isDraft) " (draft)" else ""}",
                    enabled = running == null, tile = AppColors.tiles.lime,
                ) { if (onPullRequests != null) onPullRequests(current.number) else openUrl(current.url) }
                else -> ActionRow(
                    Ph.GitMerge, "Create pull request",
                    when {
                        status.upstream == null || status.ahead > 0 -> "Push this branch first, then create it"
                        prError != null -> prError!!
                        !prLoaded -> "Checking GitHub…"
                        else -> "Opens a pull request for ${status.branch} with a title and description from its commits"
                    },
                    enabled = running == null && prLoaded && prError == null && status.upstream != null && status.ahead == 0 && conflicts.isEmpty(),
                    tile = AppColors.tiles.lime,
                ) {
                    run("Creating the pull request") { step ->
                        step("Asking GitHub (gh pr create)…")
                        val r = git.createPullRequest(dir)
                        r.fold(
                            onSuccess = { url -> pr = parsePrFromUrl(url); openUrl(url); RoutineOutcome.Done("Pull request created: $url") },
                            onFailure = { RoutineOutcome.Failed(it.message ?: "Couldn't create the pull request.") },
                        )
                    }
                }
            }
        }
        if (onPullRequests != null) {
            ActionRow(Ph.GitMerge, "Pull requests", "Review, comment on, check out and merge open pull requests", enabled = running == null, tile = AppColors.tiles.lime) {
                onPullRequests(null)
            }
        }
        ActionRow(Ph.GitBranch, "Branches & stashes", "Switch or create branches, restore saved changes", enabled = running == null) {
            onOpenBranches()
        }

        SectionLabel("With AI")
        if (installedAgents.isEmpty()) {
            Text("Install an agent (☰ → Agents) to have it resolve conflicts, write commit messages or review changes.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            // Which agent does the work.
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (a in installedAgents) {
                    Chip(a.displayName, onClick = { agent = a; GitAiAgent.set(context, a) }, selected = a == agent)
                }
            }
            ActionRow(
                Ph.GitMerge, "Get latest changes & fix conflicts",
                "Same as Get latest changes; if anything conflicts, ${agent?.displayName ?: "the agent"} resolves it in a new chat",
                enabled = running == null && canPull && conflicts.isEmpty() && idle, tile = AppColors.tiles.lilac, ai = true,
            ) { run("Getting the latest changes", aiFollowUp = true) { routines.getLatest(dir, it) } }
            if (conflicts.isNotEmpty()) {
                ActionRow(
                    Ph.MagicWand, "Fix ${operation?.let { "${it.label} " } ?: ""}conflicts",
                    "${agent?.displayName ?: "The agent"} resolves the ${conflicts.size} conflicted file${if (conflicts.size == 1) "" else "s"}" +
                        (operation?.let { if (it == GitOperation.Merge) " and concludes the merge" else " and continues the ${it.label}" } ?: ""),
                    enabled = running == null, tile = AppColors.tiles.lilac, ai = true,
                ) {
                    scope.launch {
                        // Read now, not from when the sheet opened: what's in progress decides how it's finished.
                        val op = git.operationInProgress(dir)
                        val appStash = RoutineStashes.get(context, dir)?.takeIf { it.applied }
                        ask(GitRoutines.fixConflictsPrompt(conflicts, op, appStash))
                    }
                }
            }
            ActionRow(
                Ph.GitCommit, "Write commit message & commit",
                "Reads your changes and commits them with a clear message (doesn't push)",
                enabled = running == null && hasChanges && conflicts.isEmpty(), tile = AppColors.tiles.lilac, ai = true,
            ) { ask(GitRoutines.commitPrompt()) }
            ActionRow(
                Ph.Eye, "Review my changes",
                "Looks for bugs and problems in your uncommitted changes, without editing",
                enabled = running == null && hasChanges, tile = AppColors.tiles.lilac, ai = true,
            ) { ask(GitRoutines.reviewPrompt()) }
        }
    }
}

/** Remembers the stash a routine makes, per project ([RoutineStashes]), for the Changes tab's Restore. */
private class AppStashTracker(private val context: Context) : StashTracker {
    override fun saved(dir: File, stash: TrackedStash) = RoutineStashes.set(context, dir, stash)
    override fun restored(dir: File) = RoutineStashes.clear(context, dir)
    override fun appliedWithConflicts(dir: File, stash: TrackedStash) = RoutineStashes.set(context, dir, stash)
}

/** A just-created pull request, known only by its URL (…/pull/N); the title loads on the next open. */
private fun parsePrFromUrl(url: String): GitPullRequest? =
    url.substringAfterLast("/pull/", "").toIntOrNull()?.let { GitPullRequest(it, "Pull request", "open", url) }

@Composable
private fun RoutineCard(
    running: String?,
    steps: List<String>,
    outcome: RoutineOutcome?,
    agent: Agent?,
    onFixWithAi: (RoutineOutcome.Conflicts) -> Unit,
) {
    val tones = AppColors.status
    val (bg, fg) = when (outcome) {
        null -> MaterialTheme.colorScheme.surfaceContainerHigh to MaterialTheme.colorScheme.onSurface
        is RoutineOutcome.Done -> tones.successContainer to tones.onSuccessContainer
        is RoutineOutcome.Conflicts -> tones.warningContainer to tones.onWarningContainer
        is RoutineOutcome.Failed -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
    }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(bg).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (running != null) {
            Text(running, style = MaterialTheme.typography.titleSmall, color = fg)
            FlowBar(Modifier.fillMaxWidth())
            steps.lastOrNull()?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = fg) }
        } else if (outcome != null) {
            Text(outcome.message, style = MaterialTheme.typography.bodyMedium, color = fg, maxLines = 8, overflow = TextOverflow.Ellipsis)
            if (outcome is RoutineOutcome.Conflicts) {
                Text(outcome.files.take(5).joinToString("\n") { "• $it" } + if (outcome.files.size > 5) "\n• …" else "",
                    style = MaterialTheme.typography.bodySmall, color = fg)
                if (agent != null) {
                    Button(onClick = { onFixWithAi(outcome) }) {
                        PhIcon(Ph.MagicWand, null, size = 16.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Fix with ${agent.displayName}")
                    }
                } else {
                    Text("Resolve them in the Changes tab, or install an agent to have it done.", style = MaterialTheme.typography.bodySmall, color = fg)
                }
            }
        }
    }
}

@Composable
private fun ActionRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    enabled: Boolean,
    tile: Tile? = null,
    ai: Boolean = false,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(colors.surfaceContainerHigh.copy(alpha = if (enabled) 1f else 0.5f))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val t = tile ?: AppColors.tiles.sky
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(13.dp)).background(if (enabled) t.fill else colors.surfaceContainer), contentAlignment = Alignment.Center) {
            PhIcon(icon, null, size = 20.dp, tint = if (enabled) t.on else colors.outline)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = if (enabled) colors.onSurface else colors.outline)
                if (ai) {
                    Spacer(Modifier.width(6.dp))
                    PhIcon(Ph.Sparkle, "AI", size = 13.dp, tint = if (enabled) colors.primary else colors.outline)
                }
            }
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}
