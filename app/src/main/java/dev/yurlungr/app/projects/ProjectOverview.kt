package dev.yurlungr.app.projects

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import dev.yurlungr.app.agents.Agent
import dev.yurlungr.app.agents.AgentBadge
import dev.yurlungr.app.agents.agentStatuses
import dev.yurlungr.app.chat.ChatSession
import dev.yurlungr.app.chat.ChatSessions
import dev.yurlungr.app.chat.ChatState
import dev.yurlungr.app.chat.NewChatPrefs
import dev.yurlungr.app.chat.PromptedChat
import dev.yurlungr.app.databases.ProjectDatabases
import dev.yurlungr.app.runtime.RuntimePaths
import dev.yurlungr.app.ui.AppColors
import dev.yurlungr.app.ui.LocalOpenChat
import dev.yurlungr.app.ui.Ph
import dev.yurlungr.app.ui.PhIcon
import dev.yurlungr.app.ui.Tile
import dev.yurlungr.app.web.WebRunSessions
import dev.yurlungr.app.web.WebRunState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * What the Overview remembers per project for this app run, so reopening a project shows the last
 * values at once while fresh ones load: the pull request (asks GitHub), the check results (parses
 * logs) and the size of regenerable folders (walks the tree).
 */
internal object OverviewCache {
    data class Pr(val branch: String, val pr: GitPullRequest?, val at: Long)
    data class Checks(val errors: Int, val warnings: Int, val checkedAt: Long?, val passed: Int, val failed: Int, val testedAt: Long?, val ranTests: Boolean, val ranChecks: Boolean)
    data class Disk(val bytes: Long, val complete: Boolean, val biggest: List<Pair<String, Long>>, val at: Long)

    val prs = ConcurrentHashMap<String, Pr>()
    val checks = ConcurrentHashMap<String, Checks>()
    val disk = ConcurrentHashMap<String, Disk>()
    val versions = ConcurrentHashMap<String, List<VersionWarning>>()

    const val PR_TTL = 5 * 60_000L
    const val DISK_TTL = 10 * 60_000L

    /** Reads the check and test logs: call off the main thread. */
    fun readChecks(root: File, stacks: List<DetectedStack>): Checks {
        val log = ProjectChecks.problemsLog(root)
        val problems = if (log.isFile) runCatching { ProjectChecks.parseProblems(readEnds(log, head = 0).second, root) }.getOrNull().orEmpty() else emptyList()
        val errors = problems.count { it.severity == Severity.Error }
        val testsLog = ProjectChecks.testsLog(root)
        val tests = if (testsLog.isFile) runCatching { ProjectChecks.loadTests(root, stacks) }.getOrNull().orEmpty() else emptyList()
        return Checks(
            errors, problems.size - errors, log.takeIf { it.isFile }?.lastModified(),
            tests.count { it.status == TestStatus.Passed }, tests.count { it.status == TestStatus.Failed }, testsLog.takeIf { it.isFile }?.lastModified(),
            ranTests = testsLog.isFile, ranChecks = log.isFile,
        )
    }

    /** Regenerable folders (node_modules, build output…) and their sizes: walks the tree, call off the main thread. */
    fun measure(root: File): Disk {
        val items = SpaceCleaner.items(root, SpaceCleaner.find(root))
        var complete = true
        val sized = items.map { item ->
            val bytes = item.dirs.sumOf { d -> SpaceCleaner.sizeOf(d, maxEntries = 400_000).also { if (!it.second) complete = false }.first }
            item.title to bytes
        }
        return Disk(sized.sumOf { it.second }, complete, sized.sortedByDescending { it.second }.take(3), System.currentTimeMillis())
    }
}

/**
 * The project at a glance: git, the last checks, servers, databases, recent chats, dependencies,
 * runtime warnings and disk use. Each card loads on its own, off the main thread, and links to
 * where the details are.
 */
@Composable
internal fun OverviewTab(
    dir: File,
    git: Git,
    status: GitStatus?,
    stacks: List<DetectedStack>?,
    refreshKey: Int,
    hasWeb: Boolean,
    setupNeeded: Boolean,
    runInTerminal: (String, String) -> Unit,
    onChanges: () -> Unit,
    onChecks: () -> Unit,
    onChats: () -> Unit,
    onOpenChat: (ChatSession) -> Unit,
    onNewChat: (Agent) -> Unit,
    onPreview: (url: String, title: String) -> Unit,
    onDatabases: () -> Unit,
    onSetup: () -> Unit,
    onDependencies: () -> Unit,
    onFreeSpace: () -> Unit,
    /** The untrusted project folder this is in, or null when it's trusted. */
    untrustedRoot: File? = null,
    onTrust: () -> Unit = {},
    /** The merge, rebase, cherry-pick or revert waiting to be finished. */
    operation: GitOperation? = null,
    busy: String? = null,
    onContinueOperation: (GitOperation) -> Unit = {},
    onAbortOperation: (GitOperation) -> Unit = {},
) {
    LazyColumn(
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        untrustedRoot?.let { root -> item(key = "trust") { UntrustedBanner(root, compact = false, onTrust = onTrust) } }
        if (operation != null && status != null) {
            item(key = "operation") { OperationBanner(operation, status, busy, onContinueOperation, onAbortOperation) }
        }
        item(key = "git") { GitCard(dir, git, status, onChanges) }
        item(key = "checks") { ChecksCard(dir, stacks, refreshKey, runInTerminal, onChecks) }
        if (hasWeb || WebRunSessions.get(dir) != null) item(key = "server") { ServerCard(dir, onPreview) }
        item(key = "db") { DatabasesCard(dir, onDatabases) }
        item(key = "chats") { ChatsCard(dir, onOpenChat, onNewChat, onChats) }
        if (!stacks.isNullOrEmpty()) item(key = "deps") { DependenciesCard(dir, stacks, refreshKey, setupNeeded, onSetup, onDependencies) }
        if (stacks != null) item(key = "versions") { VersionsCard(dir, stacks, refreshKey) }
        item(key = "disk") { DiskCard(dir, refreshKey, onFreeSpace) }
    }
}

@Composable
private fun OverviewCard(
    icon: ImageVector,
    tile: Tile,
    title: String,
    onClick: (() -> Unit)?,
    dot: Color? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(colors.surfaceContainer)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(30.dp).clip(RoundedCornerShape(10.dp)).background(tile.fill), contentAlignment = Alignment.Center) {
                PhIcon(icon, null, size = 16.dp, tint = tile.on)
            }
            Spacer(Modifier.width(10.dp))
            Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            dot?.let { Box(Modifier.size(9.dp).clip(CircleShape).background(it)) }
            if (onClick != null) {
                Spacer(Modifier.width(8.dp))
                PhIcon(Ph.CaretRight, null, size = 14.dp, tint = colors.outline)
            }
        }
        content()
    }
}

@Composable
private fun Line(text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines: Int = 2) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = color, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
}

private fun ago(t: Long?) = t?.let { android.text.format.DateUtils.getRelativeTimeSpanString(it).toString() }

private fun plural(n: Int, word: String) = "$n $word${if (n == 1) "" else "s"}"

// ---- Git ----

@Composable
private fun GitCard(dir: File, git: Git, status: GitStatus?, onChanges: () -> Unit) {
    val context = LocalContext.current
    val tones = AppColors.status
    val s = status
    val branch = s?.branch
    val prKey = "${dir.path}|$branch"
    // Asks GitHub (gh), so only now and then, and only for a branch with a remote.
    val pr by produceState(OverviewCache.prs[prKey], prKey, s?.hasOrigin, s?.upstream) {
        if (s == null || !s.hasOrigin || branch == null || s.unborn) return@produceState
        val cached = OverviewCache.prs[prKey]
        if (cached != null && System.currentTimeMillis() - cached.at < OverviewCache.PR_TTL) return@produceState
        git.pullRequest(dir).onSuccess { found ->
            val fresh = OverviewCache.Pr(branch, found, System.currentTimeMillis())
            OverviewCache.prs[prKey] = fresh
            value = fresh
        }
    }
    val dirty = (s?.changes?.size ?: 0) > 0
    OverviewCard(Ph.GitBranch, AppColors.tiles.lilac, "Git", onClick = onChanges,
        dot = when { s?.conflicts?.isNotEmpty() == true -> MaterialTheme.colorScheme.error; dirty -> tones.warning; else -> null }) {
        when {
            s == null -> Line("Reading the repository…")
            s.error != null -> Line(s.error.message, MaterialTheme.colorScheme.error)
            !s.isRepo -> Line("Not a git repository yet. Open Changes to start one.")
            else -> {
                Text(
                    (branch ?: "detached") + " · " + if (dirty) plural(s.changes.size, "changed file") else "no changes",
                    style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Line(when {
                    !s.hasOrigin -> "No remote"
                    s.upstream == null -> "Branch not pushed yet"
                    s.ahead == 0 && s.behind == 0 -> "In sync with ${s.upstream}"
                    else -> listOfNotNull(s.ahead.takeIf { it > 0 }?.let { "$it to push" }, s.behind.takeIf { it > 0 }?.let { "$it to pull" }).joinToString(" · ")
                })
                if (s.conflicts.isNotEmpty()) Line("${plural(s.conflicts.size, "conflict")} to resolve", MaterialTheme.colorScheme.error)
                pr?.pr?.let { p ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable {
                            runCatching {
                                context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, p.url.toUri())
                                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                            }
                        }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        PhIcon(Ph.GitMerge, null, size = 14.dp, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "PR #${p.number} · ${p.title} · ${p.state.lowercase()}${if (p.isDraft) " (draft)" else ""}",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

// ---- Checks ----

@Composable
private fun ChecksCard(dir: File, stacks: List<DetectedStack>?, refreshKey: Int, runInTerminal: (String, String) -> Unit, onChecks: () -> Unit) {
    val scope = rememberCoroutineScope()
    val c by produceState(OverviewCache.checks[dir.path], dir, stacks, refreshKey) {
        if (stacks == null) return@produceState
        val fresh = withContext(Dispatchers.IO) { runCatching { OverviewCache.readChecks(dir, stacks) }.getOrNull() } ?: return@produceState
        OverviewCache.checks[dir.path] = fresh
        value = fresh
    }
    val tones = AppColors.status
    val bad = c?.let { it.errors > 0 || it.failed > 0 } == true
    val good = c?.let { (it.ranChecks || it.ranTests) && it.errors == 0 && it.failed == 0 } == true
    OverviewCard(Ph.ShieldCheck, AppColors.tiles.lime, "Checks", onClick = onChecks,
        dot = when { bad -> MaterialTheme.colorScheme.error; good -> tones.success; else -> null }) {
        val v = c
        when {
            v == null -> Line("Reading the last results…")
            !v.ranChecks && !v.ranTests -> Line("Not run yet. Checks compile and lint the code; results show here.")
            else -> {
                if (v.ranChecks) Line(
                    (if (v.errors == 0 && v.warnings == 0) "No problems" else "${plural(v.errors, "error")}, ${plural(v.warnings, "warning")}") + " · ${ago(v.checkedAt)}",
                    if (v.errors > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (v.ranTests) Line(
                    (if (v.passed + v.failed == 0) "Tests ran" else "Tests: ${v.passed} passed, ${v.failed} failed") + " · ${ago(v.testedAt)}",
                    if (v.failed > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (!stacks.isNullOrEmpty()) {
            TextButton(onClick = {
                scope.launch {
                    // Reads package.json and friends: off the main thread.
                    val checks = withContext(Dispatchers.IO) { runCatching { ProjectChecks.checkCommands(stacks) }.getOrDefault(emptyList()) }
                    if (checks.isEmpty()) onChecks()
                    else runInTerminal(ProjectChecks.terminalCommand(dir, checks, ProjectChecks.problemsLog(dir), clearResults = false), "Check · ${dir.name}")
                }
            }) { PhIcon(Ph.ArrowClockwise, null, size = 14.dp); Spacer(Modifier.width(6.dp)); Text(if (c?.ranChecks == true) "Run checks again" else "Run checks") }
        }
    }
}

// ---- Servers and databases ----

@Composable
private fun ServerCard(dir: File, onPreview: (String, String) -> Unit) {
    val run = WebRunSessions.get(dir)
    val state = run?.state
    val tones = AppColors.status
    OverviewCard(Ph.Globe, AppColors.tiles.sky, "Web server", onClick = null,
        dot = when { state == WebRunState.Running -> tones.success; state?.active == true -> tones.warning; else -> null }) {
        if (run == null || state == null) {
            Line("Not running. Run (below) starts it.")
        } else {
            Line("${state.label} · ${run.config.project.framework} · port ${run.config.port}")
            if (state == WebRunState.Running) {
                FilledTonalButton(onClick = { onPreview(run.url, "${run.config.project.framework} · ${dir.name}") }) {
                    PhIcon(Ph.Eye, null, size = 14.dp); Spacer(Modifier.width(6.dp)); Text("Open preview")
                }
            }
        }
    }
}

@Composable
private fun DatabasesCard(dir: File, onDatabases: () -> Unit) {
    val context = LocalContext.current
    val paths = remember { RuntimePaths(context) }
    val keys = remember(dir) { dev.yurlungr.app.databases.DbKind.entries.associateBy { ProjectDatabases.key(dir, it) } }
    LaunchedEffect(dir) { runCatching { ProjectDatabases.refresh(paths, dir) } }
    val running = ProjectDatabases.running.filterKeys { it in keys }.values.sortedBy { it.kind.ordinal }
    OverviewCard(Ph.FloppyDisk, AppColors.tiles.sky, "Databases", onClick = onDatabases, dot = if (running.isNotEmpty()) AppColors.status.success else null) {
        Line(if (running.isEmpty()) "None running" else running.joinToString(" · ") { "${it.kind.title} :${it.port}" } + " running")
    }
}

// ---- Chats ----

@Composable
private fun ChatsCard(dir: File, onOpenChat: (ChatSession) -> Unit, onNewChat: (Agent) -> Unit, onChats: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val chats = ChatSessions.chats.filter { it.cwd.path == dir.path }.sortedByDescending { it.updatedAt }
    OverviewCard(Ph.ChatCircle, AppColors.tiles.lilac, "Chats", onClick = onChats) {
        if (chats.isEmpty()) Line("No chats in this project yet.")
        for (chat in chats.take(3)) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onOpenChat(chat) }.padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AgentBadge(chat.agent, size = 24.dp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(chat.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Line(listOfNotNull(
                        chat.agent.displayName,
                        when (chat.state) { ChatState.Running -> "working…"; ChatState.AuthRequired -> "needs sign-in"; else -> null },
                        ago(chat.updatedAt),
                    ).joinToString(" · "), maxLines = 1)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = {
                scope.launch {
                    // The project's agent, else the last used, else one that's signed in.
                    val agent = withContext(Dispatchers.IO) {
                        runCatching {
                            PromptedChat.pickAgent(ProjectAgentPrefs.defaultAgent(context, dir), NewChatPrefs.agent(context), agentStatuses(RuntimePaths(context)))
                        }.getOrNull()
                    }
                    if (agent == null) android.widget.Toast.makeText(context, "Install an agent first (Agents)", android.widget.Toast.LENGTH_LONG).show()
                    else onNewChat(agent)
                }
            }) { PhIcon(Ph.Plus, null, size = 14.dp); Spacer(Modifier.width(6.dp)); Text("New chat") }
            if (chats.size > 3) TextButton(onClick = onChats) { Text("All ${chats.size}") }
        }
    }
}

// ---- Dependencies and runtimes ----

@Composable
private fun DependenciesCard(
    dir: File, stacks: List<DetectedStack>, refreshKey: Int, setupNeeded: Boolean, onSetup: () -> Unit, onDependencies: () -> Unit,
) {
    val context = LocalContext.current
    LaunchedEffect(dir) { DependencyStore.load(context, dir) }
    val report = DependencyStore.reports[dir.path]
    val checking = DependencyStore.checking[dir.path]
    val canList = remember(stacks) { DependencyUpdates.toolsFor(stacks).isNotEmpty() }
    val tones = AppColors.status
    OverviewCard(Ph.Wrench, AppColors.tiles.peach, "Dependencies", onClick = if (canList) onDependencies else onSetup,
        dot = if (setupNeeded) tones.warning else null) {
        Line(stacks.joinToString(" · ") { it.name }, maxLines = 1)
        if (setupNeeded) {
            Line("Not installed yet", tones.warning)
        } else {
            Line(if (stacks.any { it.installed == true }) "Installed" else "Nothing to install detected")
        }
        if (canList) Line(when {
            checking != null -> checking
            report == null -> "Not checked for updates"
            report.outdated == 0 -> "Up to date · checked ${ago(report.checkedAt)}"
            else -> "${report.outdated} outdated" + (if (report.majors > 0) " (${report.majors} major)" else "") + " · checked ${ago(report.checkedAt)}"
        })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (setupNeeded) FilledTonalButton(onClick = onSetup) { Text("Set up") }
            if (canList) TextButton(onClick = onDependencies) { Text(if ((report?.outdated ?: 0) > 0) "Review updates" else "Updates") }
        }
        DependencyUpdateResult(dir, stacks, refreshKey)
    }
}

@Composable
private fun VersionsCard(dir: File, stacks: List<DetectedStack>, refreshKey: Int) {
    val context = LocalContext.current
    val paths = remember { RuntimePaths(context) }
    val openChat = LocalOpenChat.current
    val warnings by produceState(OverviewCache.versions[dir.path].orEmpty(), dir, stacks, refreshKey) {
        value = versionWarnings(paths, dir, stacks).also { OverviewCache.versions[dir.path] = it }
    }
    if (warnings.isEmpty()) return
    val tones = AppColors.status
    OverviewCard(Ph.Warning, AppColors.tiles.peach, "Runtime versions", onClick = null, dot = tones.warning) {
        for (w in warnings) Line(w.title, MaterialTheme.colorScheme.onSurface)
        if (openChat != null) TextButton(onClick = { openChat(dir, FixPrompt.versions(warnings)) }) {
            PhIcon(Ph.Sparkle, null, size = 14.dp); Spacer(Modifier.width(6.dp)); Text("Ask the agent")
        }
    }
}

// ---- Disk ----

@Composable
private fun DiskCard(dir: File, refreshKey: Int, onFreeSpace: () -> Unit) {
    // Walking node_modules can take a while: only when this card is on screen, and cached.
    val disk by produceState(OverviewCache.disk[dir.path], dir, refreshKey) {
        val cached = OverviewCache.disk[dir.path]
        if (cached != null && System.currentTimeMillis() - cached.at < OverviewCache.DISK_TTL) return@produceState
        val fresh = withContext(Dispatchers.IO) { runCatching { OverviewCache.measure(dir) }.getOrNull() } ?: return@produceState
        OverviewCache.disk[dir.path] = fresh
        value = fresh
    }
    OverviewCard(Ph.Trash, AppColors.tiles.lime, "Disk use", onClick = onFreeSpace) {
        val d = disk
        when {
            d == null -> Line("Measuring build output and installed packages…")
            d.bytes == 0L -> Line("No build output or installed packages taking space.")
            else -> {
                Line("${SpaceCleaner.formatBytes(d.bytes)}${if (d.complete) "" else "+"} in folders that can be made again", MaterialTheme.colorScheme.onSurface)
                Line(d.biggest.joinToString(" · ") { (name, bytes) -> "$name ${SpaceCleaner.formatBytes(bytes)}" }, maxLines = 1)
            }
        }
    }
}
