package dev.yurlungr.app.projects

import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import dev.yurlungr.app.ui.PhIcon
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import dev.yurlungr.app.ui.NavTile
import dev.yurlungr.app.ui.StatTile
import dev.yurlungr.app.terminal.TerminalSessions
import com.termux.terminal.TerminalSession
import dev.yurlungr.app.chat.cwdLabel
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import dev.yurlungr.app.agents.AgentBadge
import dev.yurlungr.app.chat.ChatSession
import dev.yurlungr.app.chat.ChatSessions
import dev.yurlungr.app.chat.ChatState
import dev.yurlungr.app.ui.AppColors
import dev.yurlungr.app.ui.Motion
import dev.yurlungr.app.ui.WarningBanner
import dev.yurlungr.app.ui.Ph
import dev.yurlungr.app.ui.SkeletonCode
import dev.yurlungr.app.ui.WaveDots
import dev.yurlungr.app.ui.SkeletonListItem
import dev.yurlungr.app.ui.NavButton
import dev.yurlungr.app.ui.Keyboards
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import dev.yurlungr.app.ui.Chip
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.yurlungr.app.agents.Agent
import dev.yurlungr.app.agents.agentStatuses
import dev.yurlungr.app.projects.Git.Companion.shellQuote
import dev.yurlungr.app.runtime.RuntimePaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private enum class ProjectTab(val label: String) { Overview("Overview"), Files("Files"), Changes("Changes"), History("History"), Checks("Checks"), Chats("Chats") }

/**
 * A project's workspace: name (tap to switch projects) and branch on top, Files / Changes /
 * History / Chats below, a status strip when there's something to commit or push, and Chat ·
 * Build · Terminal along the bottom.
 */
@Composable
fun ProjectScreen(
    dir: File,
    /** "overview" / "files" / "changes" / "history" / "chats"; Overview when null. */
    initialTab: String? = null,
    refreshTick: Int,
    onBack: () -> Unit,
    onOpenFile: (File) -> Unit,
    onNewChat: (Agent) -> Unit,
    onOpenChat: (ChatSession) -> Unit,
    onSwitchProject: (File) -> Unit,
    onAllProjects: () -> Unit,
    runInTerminal: (command: String, name: String) -> Unit,
    /** Shows the terminal screen with whatever session is selected. */
    onOpenTerminal: () -> Unit,
    /** Opens a running web app in the in-app preview. */
    onPreview: (url: String, title: String) -> Unit = { _, _ -> },
    /** This project's agent settings (default agent, instructions, MCP servers). */
    onAgentSettings: () -> Unit = {},
    /** Opens a file at a line (search results, problems, test failures). */
    onOpenFileAt: (File, Int?) -> Unit = { f, _ -> onOpenFile(f) },
    /** The merge view for a conflicted file. */
    onResolveConflict: (File) -> Unit = onOpenFile,
    /** This project's PostgreSQL / Redis / MariaDB servers. */
    onDatabases: () -> Unit = {},
    /** The .env editor. */
    onEnvFile: () -> Unit = {},
    /** The API tester for this project's server; the port when opened from a running server. */
    onApiTester: (port: Int?) -> Unit = {},
    /** The GitHub pull requests screen; a number opens that one. */
    onPullRequests: (number: Int?) -> Unit = {},
    /** Free space (regenerable folders), from the Overview's disk card. */
    onFreeSpace: () -> Unit = {},
) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val paths = remember { RuntimePaths(context) }
    val git = remember { Git(paths) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(dir) { LastProject.set(context, dir) }

    var tab by rememberSaveable { mutableStateOf(ProjectTab.entries.firstOrNull { it.name.equals(initialTab, ignoreCase = true) } ?: ProjectTab.Overview) }
    var status by remember { mutableStateOf<GitStatus?>(null) }
    // Git writes run in GitJobs, not here: they finish (and are reported) even if this screen closes meanwhile.
    val busy: String? = GitJobs.running[GitJobs.key(dir)]
    var message by remember { mutableStateOf<Pair<String, Boolean>?>(null) } // text, isError
    /** Everything: the tree, stacks, tasks, checks, git. Bumped by explicit refreshes and by what changes files (pull, switch). */
    var reload by remember { mutableIntStateOf(0) }
    /** Only git's state (status, history, operation, stash): bumped after stage, commit, discard and the like. */
    var statusTick by remember { mutableIntStateOf(0) }
    /** The merge, rebase, cherry-pick or revert waiting to be finished. */
    var operation by remember(dir.path) { mutableStateOf<GitOperation?>(null) }
    /** The stash a routine made with the user's work, while it's still in the stash list. */
    var routineStash by remember(dir.path) { mutableStateOf<TrackedStash?>(null) }
    /** The last git write failed on index.lock: offer to remove a stale one. */
    var lockedIndex by remember(dir.path) { mutableStateOf(false) }
    var trustTick by remember { mutableIntStateOf(0) }
    /** The untrusted project folder this is in (itself or a parent), or null when it's trusted. */
    val untrustedRoot = remember(dir.path, trustTick) { ProjectTrust.untrustedRoot(context, dir) }
    var askTrust by remember { mutableStateOf(false) }
    var askUntrust by remember { mutableStateOf(false) }
    /** Changes with any refresh, git-only ones included: for what shows git state (history, the Overview). */
    val gitKey = refreshTick * 1_000_000 + reload * 1_000 + statusTick
    var askIdentity by remember { mutableStateOf<String?>(null) } // pending commit message
    // Kept until a commit succeeds, so cancelling the identity prompt doesn't lose it.
    var commitMessage by rememberSaveable(dir.path) { mutableStateOf("") }
    var capabilities by remember(dir.path) { mutableStateOf<dev.yurlungr.app.web.ProjectCapabilities?>(null) }
    val isAndroid = capabilities?.android ?: false
    var showWebRun by remember(dir.path) { mutableStateOf(false) }
    var showBranches by remember { mutableStateOf(false) }
    var showGitActions by remember { mutableStateOf(false) }
    /** Tech stacks found in the project (for dependency installation); null while detecting. */
    var stacks by remember(dir.path) { mutableStateOf<List<DetectedStack>?>(null) }
    /** The stacks that still need runtimes or dependencies (computed when the stacks change). */
    var needs by remember(dir.path) { mutableStateOf(emptyList<DetectedStack>()) }
    var showSetup by remember { mutableStateOf(false) }
    /** Runnable tasks (package.json scripts, make, Gradle…), found with the stacks. */
    var tasks by remember(dir.path) { mutableStateOf(emptyList<ProjectTask>()) }
    var showTasks by remember { mutableStateOf(false) }
    var showDeploy by remember { mutableStateOf(false) }
    var showDependencyUpdates by remember { mutableStateOf(false) }
    var setupBannerDismissed by remember(dir.path) { mutableStateOf(SetupBannerPrefs.dismissed(context, dir)) }
    var headerVisible by remember { mutableStateOf(true) }
    LaunchedEffect(tab) { headerVisible = true }
    // Any list in the tabs reports its scrolling here: down hides the header, up shows it.
    val headerScroll = remember {
        object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
            override fun onPreScroll(available: androidx.compose.ui.geometry.Offset, source: androidx.compose.ui.input.nestedscroll.NestedScrollSource): androidx.compose.ui.geometry.Offset {
                if (available.y < -8f) headerVisible = false else if (available.y > 8f) headerVisible = true
                return androidx.compose.ui.geometry.Offset.Zero
            }
        }
    }
    /** The origin remote is on GitHub: pull requests can be shown. */
    val onGitHub by produceState(false, dir, status?.hasOrigin) { value = status?.hasOrigin == true && git.originSlug(dir) != null }
    /** A git command running in the terminal sheet: its title and arguments. */
    var gitTerminal by remember { mutableStateOf<Pair<String, List<String>>?>(null) }

    // Automatic setup (opt-in, in the Setup sheet): once the stacks are known, install what's
    // missing in a terminal, once per need, so reopening the project doesn't repeat it.
    LaunchedEffect(stacks) {
        // Read once per refresh, off the main thread: it checks dpkg's files for every package.
        val found = withContext(Dispatchers.IO) { stacks.orEmpty().filter { it.needsSetup(paths.prefix) } }
        needs = found
        if (found.isEmpty() || !SetupBannerPrefs.auto(context)) return@LaunchedEffect
        // Installing runs the project's own scripts (npm lifecycle scripts…): never by itself for an untrusted one.
        if (!ProjectTrust.isTrusted(context, dir)) return@LaunchedEffect
        val signature = autoSetupSignature(found)
        if (SetupBannerPrefs.autoRan(context, dir, signature) || SetupBannerPrefs.startedNow(dir)) return@LaunchedEffect
        SetupBannerPrefs.markAutoRan(context, dir, signature)
        runInTerminal(installCommand(dir, found), "Set up ${dir.name}")
    }

    // Git's own state: cheap, so refreshed after every git operation without re-reading the whole project.
    LaunchedEffect(dir, refreshTick, reload, statusTick) {
        val s = git.status(dir)
        status = s
        if (!s.isRepo) { operation = null; routineStash = null; return@LaunchedEffect }
        operation = git.operationInProgress(dir)
        routineStash = RoutineStashes.get(context, dir)?.let { tracked ->
            // Popped or dropped elsewhere (terminal, an agent): stop offering it.
            when (git.hasStash(dir, tracked.sha)) {
                false -> { RoutineStashes.clear(context, dir); null }
                else -> tracked
            }
        }
    }
    LaunchedEffect(dir, refreshTick, reload) {
        launch {
            val found = withContext(Dispatchers.IO) { runCatching { StackDetector.detect(dir) }.getOrDefault(emptyList()) }
            stacks = found
            tasks = withContext(Dispatchers.IO) { runCatching { ProjectTasks.detect(dir, found) }.getOrDefault(emptyList()) }
        }
        launch { capabilities = withContext(Dispatchers.IO) {
            runCatching { dev.yurlungr.app.web.CustomServers.withSaved(context, dir, dev.yurlungr.app.web.ProjectDetector.detect(dir)) }.getOrElse {
                dev.yurlungr.app.web.ProjectCapabilities(File(dir,"gradlew").isFile,emptyList())
            }
        } }
    }

    /**
     * Runs a git write in [GitJobs] (it outlives this screen). [quiet]: say nothing when it works
     * (stage, discard…); failures are always shown. Only git's state is re-read afterwards.
     */
    fun runGit(label: String, quiet: Boolean = false, committing: Boolean = false, block: suspend () -> dev.yurlungr.app.runtime.CommandResult) {
        if (busy != null) return
        message = null
        GitJobs.start(dir, label, quiet, committing, block)
    }
    // A write finished (maybe while this screen was closed): show it and re-read git's state.
    val finished = GitJobs.results[GitJobs.key(dir)]
    LaunchedEffect(finished) {
        val done = GitJobs.take(dir) ?: return@LaunchedEffect
        val r = done.result
        if (done.committed) commitMessage = ""
        // On failure, say why in plain words (not installed, timed out, exit code) before git's own lines.
        message = if (r.ok && done.quiet) null
            else (if (r.ok) r.output.trim().lines().takeLast(6).joinToString("\n").ifBlank { "${done.label}: done" } else r.describe(done.label)) to !r.ok
        lockedIndex = !r.ok && Git.mentionsIndexLock(r.errorOutput + "\n" + r.output)
        statusTick++
    }

    var commitStagedOnly by remember { mutableStateOf(false) }
    fun doCommit(text: String) =
        runGit("Commit", committing = true) { if (commitStagedOnly) git.commitStaged(dir, text) else git.commitAll(dir, text) }
    fun commit(text: String, stagedOnly: Boolean) = scope.launch {
        commitStagedOnly = stagedOnly
        // What a commit here would use: the repository's own identity counts, not only the global one.
        val (name, email) = git.identity(dir)
        if (name.isBlank() || email.isBlank()) askIdentity = text else doCommit(text)
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding()
            .imePadding(),
    ) {
        Header(
            dir, paths.projects, onBack, onSwitchProject, onAllProjects,
            trusted = untrustedRoot == null,
            onTrustChange = { if (untrustedRoot == null) askUntrust = true else askTrust = true },
        )
        // The stat tiles and tabs slide away while scrolling down and come back when scrolling up.
        val collapse = androidx.compose.animation.fadeOut(tween(Motion.SHORT)) +
            androidx.compose.animation.slideOutVertically(tween(Motion.MEDIUM, easing = Motion.Emphasized)) { -it / 2 } +
            androidx.compose.animation.shrinkVertically(tween(Motion.MEDIUM, easing = Motion.Emphasized), shrinkTowards = Alignment.Top)
        val reveal = androidx.compose.animation.fadeIn(tween(Motion.MEDIUM)) +
            androidx.compose.animation.slideInVertically(tween(Motion.MEDIUM, easing = Motion.Emphasized)) { -it / 2 } +
            androidx.compose.animation.expandVertically(tween(Motion.MEDIUM, easing = Motion.Emphasized), expandFrom = Alignment.Top)
        AnimatedVisibility(headerVisible, enter = reveal, exit = collapse) {
        status?.takeIf { it.isRepo }?.let { s ->
            // Bento stat tiles: where you are and what's waiting.
            val tiles = AppColors.tiles
            val toPush = if (s.hasOrigin) s.ahead else 0
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile("Branch", (s.branch ?: "detached") + " ⌄", tiles.lilac, Modifier.weight(1f), valueSize = 17.sp) { showBranches = true }
                StatTile("Changes", s.changes.size.toString(), tiles.peach, Modifier.weight(1f)) { tab = ProjectTab.Changes }
                StatTile(
                    if (s.hasOrigin) "To push" else "Remote",
                    if (s.hasOrigin) toPush.toString() else "none",
                    tiles.sky,
                    Modifier.weight(1f),
                    valueSize = if (s.hasOrigin) 26.sp else 17.sp,
                ) {
                    // Detached HEAD has no branch to push: the Changes tab explains.
                    if (toPush > 0 && busy == null && !s.detached) runGit("Push") { git.push(dir, s) } else tab = ProjectTab.Changes
                }
            }
        }
        }
        // Someone else's code: what's held back, and Trust. The Overview has the full explanation.
        untrustedRoot?.let { root ->
            if (tab != ProjectTab.Overview) {
                UntrustedBanner(root, compact = true, onTrust = { askTrust = true }, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
            }
        }
        // Right after cloning (or creating) a project: offer to install what it needs.
        val missing = needs
        if (missing.isNotEmpty() && !setupBannerDismissed) {
            SetupBanner(
                missing, paths.prefix,
                onInstall = { runInTerminal(installCommand(dir, missing), "Install dependencies · ${dir.name}") },
                onDetails = { showSetup = true },
                onDismiss = { setupBannerDismissed = true; SetupBannerPrefs.dismiss(context, dir) },
            )
        }
        ProjectHealthCards(dir, stacks, refreshKey = refreshTick * 1000 + reload)
        status?.error?.let { e ->
            GitErrorCard(
                e,
                onInstall = { runInTerminal(dev.yurlungr.app.runtime.setupScriptCommand("dev-tools-setup.sh"), "Developer tools") },
                onRetry = { reload++ },
            )
        }
        dev.yurlungr.app.web.WebRunCard(dir, onLogs = onOpenTerminal, onOptions = { showWebRun = true }, onPreview = onPreview, onApiTester = onApiTester)
        dev.yurlungr.app.databases.ProjectDatabasesStrip(dir, onOpen = onDatabases)
        AnimatedVisibility(headerVisible, enter = reveal, exit = collapse) {
            SectionTabs(tab, changeCount = status?.changes?.size ?: 0) { tab = it }
        }
        Box(Modifier.weight(1f).nestedScroll(headerScroll)) {
            when (tab) {
                ProjectTab.Overview -> OverviewTab(
                    dir, git, status, stacks, refreshKey = refreshTick * 1000 + reload,
                    untrustedRoot = untrustedRoot, onTrust = { askTrust = true },
                    operation = operation, busy = busy,
                    onContinueOperation = { op -> runGit(continueLabel(op)) { git.continueOperation(dir, op) } },
                    onAbortOperation = { op -> runGit("Abort the ${op.label}") { git.abortOperation(dir, op) } },
                    hasWeb = capabilities?.web?.isNotEmpty() == true, setupNeeded = needs.isNotEmpty(), runInTerminal = runInTerminal,
                    onChanges = { tab = ProjectTab.Changes }, onChecks = { tab = ProjectTab.Checks }, onChats = { tab = ProjectTab.Chats },
                    onOpenChat = onOpenChat, onNewChat = onNewChat, onPreview = onPreview, onDatabases = onDatabases,
                    onSetup = { showSetup = true }, onDependencies = { showDependencyUpdates = true }, onFreeSpace = onFreeSpace,
                )
                ProjectTab.Changes -> ChangesTab(
                    dir = dir,
                    git = git,
                    status = status,
                    busy = busy,
                    message = message,
                    onDismissMessage = { message = null; lockedIndex = false },
                    operation = operation,
                    onContinueOperation = { op -> runGit(continueLabel(op)) { git.continueOperation(dir, op) } },
                    onAbortOperation = { op -> runGit("Abort the ${op.label}") { git.abortOperation(dir, op) } },
                    routineStash = routineStash,
                    onRestoreStash = { s ->
                        runGit("Restore your changes") {
                            val r = git.stashPopSha(dir, s.sha)
                            if (r.ok) RoutineStashes.clear(context, dir)
                            r
                        }
                    },
                    onDropStash = { s ->
                        runGit("Drop the stash") {
                            val r = git.stashDropSha(dir, s.sha)
                            if (r.ok) RoutineStashes.clear(context, dir)
                            r
                        }
                    },
                    onRemoveLock = if (lockedIndex) ({ runGit("Remove the stale lock") { git.removeStaleLock(dir) } }) else null,
                    onInit = { runGit("Initialize") { git.init(dir) } },
                    commitMessage = commitMessage,
                    onCommitMessageChange = { commitMessage = it },
                    onCommit = ::commit,
                    onOpenFile = onOpenFile,
                    onResolveConflict = onResolveConflict,
                    onGit = { label, block -> runGit(label, quiet = true, block = block) },
                    // Shown live in a terminal sheet: progress, prompts and git's own messages.
                    onPull = { gitTerminal = "Pull" to listOf("pull", "--ff-only") },
                    onPush = {
                        status?.takeIf { !it.detached }?.let { s ->
                            gitTerminal = "Push" to if (s.upstream == null) listOf("push", "-u", "origin", "HEAD") else listOf("push")
                        }
                    },
                    onAddGitignore = {
                        scope.launch {
                            git.addDefaultGitignore(dir)
                            statusTick++
                        }
                    },
                    onPublish = {
                        // In a terminal: gh may need to install, sign in, or ask questions.
                        runInTerminal(
                            "cd ${shellQuote(dir.path)} && " +
                                "{ command -v gh >/dev/null || pkg install -y gh; } && " +
                                "{ gh auth status >/dev/null 2>&1 || { gh auth login --hostname github.com --git-protocol https --web && gh auth setup-git; }; } && " +
                                // Options first, then `--`: a folder name starting with "-" must not read as a flag.
                                "gh repo create --private --source=. --remote=origin --push -- ${shellQuote(dir.name)}",
                            "Publish ${dir.name}",
                        )
                    },
                )
                ProjectTab.Files -> FilesTab(dir, status, git, refreshTick to reload, onOpenFile, onOpenFileAt)
                ProjectTab.Checks -> ChecksTab(
                    dir, stacks.orEmpty(), refreshKey = refreshTick * 1000 + reload, runInTerminal = runInTerminal,
                    onOpenAt = onOpenFileAt,
                    onAskAgent = { prompt ->
                        // The project's chosen agent, else the first installed one, in a new chat.
                        scope.launch {
                            val installed = withContext(Dispatchers.IO) { agentStatuses(paths).filter { it.installed }.map { it.agent } }
                            val agent = ProjectAgentPrefs.defaultAgent(context, dir)?.takeIf { it in installed } ?: installed.firstOrNull()
                            if (agent == null) {
                                android.widget.Toast.makeText(context, "Install an agent first (Agents)", android.widget.Toast.LENGTH_LONG).show()
                            } else {
                                val chat = dev.yurlungr.app.chat.ChatSessions.create(context, agent, dir)
                                chat.queue(prompt, emptyList())
                                onOpenChat(chat)
                            }
                        }
                    },
                )
                ProjectTab.History -> HistoryView(dir, git, status, refreshKey = gitKey)
                ProjectTab.Chats -> ChatsTab(dir, onOpenChat)
            }
        }
        ActionBar(dir, isAndroid, onNewChat, runInTerminal, onOpenTerminal,
            hasWeb = capabilities?.web?.isNotEmpty() == true,
            onRunWeb = {
                val run = dev.yurlungr.app.web.WebRunSessions.get(dir)
                when {
                    run?.state == dev.yurlungr.app.web.WebRunState.Running -> onPreview(run.url, "${run.config.project.framework} · ${dir.name}")
                    run?.state?.active == true -> { dev.yurlungr.app.web.WebRunSessions.logs(run); onOpenTerminal() }
                    else -> capabilities?.web?.takeIf { it.isNotEmpty() }?.let { projects ->
                        scope.launch {
                            // Anything it can't decide alone opens the options; a missing runtime (Node.js,
                            // pnpm, Bun…) is installed first, with the dependencies, then Run works.
                            if (dev.yurlungr.app.web.WebQuickRun.start(context, dir, projects) != null) {
                                val web = stacks.orEmpty().filter { (it.id == "node" || it.id == "deno") && it.missingPackages(paths.prefix).isNotEmpty() }
                                if (web.isNotEmpty()) runInTerminal(installCommand(dir, web), "Set up ${dir.name}") else showWebRun = true
                            }
                        }
                    }
                }
            },
            onRunWebOptions = { showWebRun = true },
            hasGit = status?.isRepo == true, onGitActions = { showGitActions = true },
            hasStacks = stacks.orEmpty().isNotEmpty(), onSetup = { showSetup = true },
            setupNeeded = needs.isNotEmpty(),
            extraTools = listOfNotNull(
                DockTool("Scripts", Ph.Play, AppColors.tiles.lime, "Run package.json scripts, make, just, Gradle, Cargo…") { showTasks = true }.takeIf { tasks.isNotEmpty() },
                DockTool("Build & deploy", Ph.CloudArrowUp, AppColors.tiles.sky, "Build the site, preview it, save it as a zip or publish it") { showDeploy = true }
                    .takeIf { capabilities?.web?.isNotEmpty() == true },
                DockTool("Updates", Ph.ArrowsClockwise, AppColors.tiles.peach, "Outdated dependencies: update some or all safe ones, then run the checks") { showDependencyUpdates = true }
                    .takeIf { DependencyUpdates.toolsFor(stacks.orEmpty()).isNotEmpty() },
                DockTool("Agent settings", Ph.Sparkle, AppColors.tiles.lilac, "Default agent, instruction files and MCP servers for this project", onClick = onAgentSettings),
                DockTool("Databases", Ph.FloppyDisk, AppColors.tiles.sky, "PostgreSQL, Redis and MariaDB for this project, with connection strings", onClick = onDatabases),
                DockTool(".env", Ph.LockSimple, AppColors.tiles.peach, "Edit environment variables, values hidden until tapped", onClick = onEnvFile),
                DockTool("API tester", Ph.Lightning, AppColors.tiles.sky, "Send HTTP requests to this project's local server") { onApiTester(null) },
                DockTool("Pull requests", Ph.GitMerge, AppColors.tiles.lime, "Review, comment on and merge this repository's GitHub pull requests") { onPullRequests(null) }
                    .takeIf { onGitHub },
            ))
    }

    if (showTasks) {
        ProjectTasksSheet(dir, tasks, onDismiss = { showTasks = false }) { task ->
            showTasks = false
            runInTerminal("cd ${shellQuote(task.dir.path)} && ${task.command}", "${task.name} · ${dir.name}")
        }
    }

    if (showDeploy) capabilities?.web?.takeIf { it.isNotEmpty() }?.let { webProjects ->
        dev.yurlungr.app.web.BuildDeploySheet(dir, webProjects, onDismiss = { showDeploy = false },
            runInTerminal = runInTerminal, onPreview = onPreview, onOpenTerminal = onOpenTerminal)
    }

    if (showDependencyUpdates) {
        DependencyUpdatesSheet(dir, stacks.orEmpty(), onDismiss = { showDependencyUpdates = false }, runInTerminal = runInTerminal)
    }

    if (showSetup) {
        ProjectSetupSheet(dir, stacks.orEmpty(), onDismiss = { showSetup = false }) { command, name ->
            showSetup = false
            runInTerminal(command, name)
        }
    }

    gitTerminal?.let { (title, args) ->
        GitTerminalSheet(dir, title, args, onDismiss = { gitTerminal = null; reload++ }, onFinished = { reload++ })
    }

    untrustedRoot?.let { root ->
        if (askTrust) TrustSheet(root, onDismiss = { askTrust = false }) { askTrust = false; trustTick++ }
    }
    if (askUntrust && untrustedRoot == null) UntrustSheet(dir, onDismiss = { askUntrust = false }) { askUntrust = false; trustTick++ }

    if (showGitActions) status?.takeIf { it.isRepo }?.let { s ->
        val agents by androidx.compose.runtime.produceState(emptyList<Agent>()) {
            value = withContext(Dispatchers.IO) { agentStatuses(paths).filter { it.installed }.map { it.agent } }
        }
        GitActionsSheet(
            dir, git, s, agents,
            operation = operation,
            onDismiss = { showGitActions = false },
            onChanged = { reload++ },
            onAgentTask = { agent, prompt ->
                // A new chat in this project; the prompt goes out as soon as the agent is ready.
                showGitActions = false
                val chat = dev.yurlungr.app.chat.ChatSessions.create(context, agent, dir)
                chat.queue(prompt, emptyList())
                onOpenChat(chat)
            },
            onOpenBranches = { showGitActions = false; showBranches = true },
            onPullRequests = if (onGitHub) { number -> showGitActions = false; onPullRequests(number) } else null,
        )
    }

    if (showWebRun) capabilities?.web?.takeIf { it.isNotEmpty() }?.let { webProjects ->
        dev.yurlungr.app.web.WebRunSheet(dir, webProjects, onDismiss = { showWebRun = false },
            onStarted = { /* Keep the project visible while the server starts; Logs opens its terminal. */ },
            runInTerminal = runInTerminal)
    }

    if (showBranches) status?.takeIf { it.isRepo }?.let { s ->
        BranchSheet(dir, git, s, onDismiss = { showBranches = false }, onChanged = { reload++ })
    }

    askIdentity?.let { pending ->
        IdentityDialog(
            onDismiss = { askIdentity = null },
            onSave = { name, email ->
                askIdentity = null
                scope.launch {
                    val saved = git.setIdentity(name, email)
                    if (!saved.ok) {
                        message = saved.describe("Saving your git name and email") to true
                    } else {
                        doCommit(pending)
                    }
                }
            },
        )
    }
}

/** ☰ tile · project name card (tap to switch projects). */
@Composable
private fun Header(
    dir: File,
    projectsDir: File,
    onBack: () -> Unit,
    onSwitchProject: (File) -> Unit,
    onAllProjects: () -> Unit,
    trusted: Boolean,
    /** Trust the project (when it isn't), or mark it untrusted again. */
    onTrustChange: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        NavTile(onBack)
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(46.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
                    .clickable { menu = true }
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    dir.name,
                    style = MaterialTheme.typography.titleLarge,
                    fontSize = 19.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                PhIcon(Ph.CaretDown, "Switch project", size = 14.dp, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                val others by androidx.compose.runtime.produceState(emptyList<File>(), menu) {
                    value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        projectsDir.listFiles { f -> f.isDirectory && !f.name.startsWith(".") && f != dir }.orEmpty()
                            .sortedByDescending { it.lastModified() }.take(8)
                    }
                }
                for (p in others) {
                    DropdownMenuItem(
                        leadingIcon = { PhIcon(Ph.FolderSimple, null, size = 18.dp, tint = MaterialTheme.colorScheme.primary) },
                        text = { Text(p.name) },
                        onClick = {
                            menu = false
                            onSwitchProject(p)
                        },
                    )
                }
                if (others.isNotEmpty()) HorizontalDivider()
                DropdownMenuItem(
                    leadingIcon = { PhIcon(Ph.ListChecks, null, size = 18.dp) },
                    text = { Text("All projects") },
                    onClick = {
                        menu = false
                        onAllProjects()
                    },
                )
                HorizontalDivider()
                DropdownMenuItem(
                    leadingIcon = { PhIcon(if (trusted) Ph.Warning else Ph.ShieldCheck, null, size = 18.dp) },
                    text = { Text(if (trusted) "Mark ${dir.name} untrusted…" else "Trust ${dir.name}…") },
                    onClick = {
                        menu = false
                        onTrustChange()
                    },
                )
            }
        }
    }
}

/** Segmented Files / Changes (count) / History / Chats. */
@Composable
private fun SectionTabs(selected: ProjectTab, changeCount: Int, onSelect: (ProjectTab) -> Unit) {
    // Daylight segmented control: light = grey track with a white thumb; dark = raised track with a white thumb.
    val dark = isSystemInDarkTheme()
    val colors = MaterialTheme.colorScheme
    val track = if (dark) colors.surfaceContainer else colors.surfaceContainerHighest
    val thumb = if (dark) colors.primary else colors.surfaceContainer
    val onThumb = if (dark) colors.onPrimary else colors.onSurface
    androidx.compose.foundation.layout.BoxWithConstraints {
    // Five labels in a phone's width: the smaller label style keeps "Changes" and its count whole.
    val narrow = maxWidth < 420.dp
    Row(
        Modifier
            .padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 10.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(track)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        for (t in ProjectTab.entries) {
            val on = t == selected
            val bg by animateColorAsState(if (on) thumb else Color.Transparent, tween(Motion.SHORT), label = "tab")
            val badge = t == ProjectTab.Changes && changeCount > 0
            Row(
                Modifier
                    // Overview is an icon, so six segments still fit a phone.
                    .weight(if (badge) 1.45f else if (t == ProjectTab.Overview) 0.6f else 1f)
                    .semantics(mergeDescendants = true) {
                        role = androidx.compose.ui.semantics.Role.Tab
                        this.selected = on
                        if (badge) stateDescription = "$changeCount uncommitted"
                    }
                    .height(36.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(bg)
                    .clickable { onSelect(t) },
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (t == ProjectTab.Overview) PhIcon(Ph.Info, t.label, size = 18.dp, tint = if (on) onThumb else colors.onSurfaceVariant)
                else Text(
                    t.label,
                    style = if (narrow) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelLarge,
                    color = if (on) onThumb else colors.onSurfaceVariant,
                    maxLines = 1,
                    softWrap = false,
                )
                if (badge) {
                    Spacer(Modifier.width(6.dp))
                    val peach = AppColors.tiles.peach
                    // A pill sized to its number, centred on the text line, never clipped by the segment.
                    Box(
                        Modifier.height(18.dp).widthIn(min = 18.dp).clip(RoundedCornerShape(9.dp)).background(peach.fill).padding(horizontal = 5.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(if (changeCount > 99) "99+" else "$changeCount", fontSize = 10.sp, lineHeight = 10.sp, fontWeight = FontWeight.Bold,
                            color = peach.on, maxLines = 1, softWrap = false,
                            style = androidx.compose.ui.text.TextStyle(platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false)))
                    }
                }
            }
        }
    }
    }
}

// ---- Changes -------------------------------------------------------------------------

/** The busy label for finishing [op]. */
internal fun continueLabel(op: GitOperation) = if (op == GitOperation.Merge) "Conclude the merge" else "Continue the ${op.label}"

/** Git is missing or failed: what happened, and the fix (install the tools, or retry). */
@Composable
private fun GitErrorCard(error: GitError, onInstall: () -> Unit, onRetry: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(error.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer, maxLines = 6, overflow = TextOverflow.Ellipsis)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (error.toolMissing) Button(onClick = onInstall) { Text("Install developer tools") }
            TextButton(onClick = onRetry) { Text("Retry") }
        }
    }
}

/** The project's diffs (Changes tab, History): see [DiffViewer]. */
@Composable
fun UnifiedDiff(diff: String) = DiffViewer(diff)

/** Chats started in this project, newest activity first. */
@Composable
private fun ChatsTab(dir: File, onOpenChat: (ChatSession) -> Unit) {
    val chats = ChatSessions.chats.filter { it.cwd.path == dir.path }
    val context = LocalContext.current
    // Per-chat turns and time, from the checkpoint records.
    val stats by produceState(emptyMap<String, dev.yurlungr.app.chat.Checkpoints.Stats>(), chats.size) {
        value = withContext(Dispatchers.IO) { chats.associate { it.id to dev.yurlungr.app.chat.Checkpoints.stats(context, it.id) } }
    }
    LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()) {
        if (chats.isNotEmpty()) item {
            val turns = stats.values.sumOf { it.turns }
            val millis = stats.values.sumOf { it.millis }
            val costs = chats.mapNotNull { it.usage }.filter { it.costAmount != null }.groupBy { it.currency ?: "USD" }
                .map { (cur, list) -> String.format(java.util.Locale.US, "%.2f %s", list.sumOf { it.costAmount!! }, cur) }
            Text(
                listOfNotNull("${chats.size} chat${if (chats.size == 1) "" else "s"}", "$turns prompts", "${dev.yurlungr.app.chat.duration(millis)} working",
                    costs.takeIf { it.isNotEmpty() }?.joinToString(" + ")?.let { "$it reported" }).joinToString(" · "),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, top = 4.dp),
            )
        }
        if (chats.isEmpty()) {
            item {
                Text(
                    "No chats in this project yet. Tap Chat below to start one here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 12.dp, horizontal = 4.dp),
                )
            }
        }
        items(chats, key = { it.id }) { chat ->
            Row(
                Modifier
                    .animateItem()
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .clickable { onOpenChat(chat) }
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AgentBadge(chat.agent, size = 34.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(chat.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        chat.agent.displayName + (stats[chat.id]?.turns?.takeIf { it > 0 }?.let { " · $it prompts" } ?: "") + when (chat.state) {
                            ChatState.Running -> " · working…"
                            ChatState.AuthRequired -> " · needs sign-in"
                            ChatState.Saved -> ""
                            else -> " · open"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (chat.state == ChatState.Running) WaveDots(dotSize = 4.dp)
            }
        }
    }
}

fun humanSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
}

// ---- History -------------------------------------------------------------------------

@Composable
private fun IdentityDialog(onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Who's committing?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Git records a name and email with each commit. Set once for all projects.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                OutlinedTextField(email, { email = it }, label = { Text("Email") }, singleLine = true, keyboardOptions = Keyboards.Email)
            }
        },
        confirmButton = {
            Button(onClick = { onSave(name.trim(), email.trim()) }, enabled = name.isNotBlank() && Git.isValidEmail(email)) { Text("Save & commit") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
