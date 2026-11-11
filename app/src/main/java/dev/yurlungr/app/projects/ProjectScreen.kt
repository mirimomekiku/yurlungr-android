package dev.yurlungr.app.projects

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

private enum class ProjectTab(val label: String) { Files("Files"), Changes("Changes"), History("History"), Chats("Chats") }

/**
 * A project's workspace: name (tap to switch projects) and branch on top, Files / Changes /
 * History / Chats below, a status strip when there's something to commit or push, and Chat ·
 * Build · Terminal along the bottom.
 */
@Composable
fun ProjectScreen(
    dir: File,
    /** "files" / "changes" / "history" / "chats"; Files when null. */
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
) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val paths = remember { RuntimePaths(context) }
    val git = remember { Git(paths) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(dir) { LastProject.set(context, dir) }

    var tab by rememberSaveable { mutableStateOf(ProjectTab.entries.firstOrNull { it.name.equals(initialTab, ignoreCase = true) } ?: ProjectTab.Files) }
    var status by remember { mutableStateOf<GitStatus?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<Pair<String, Boolean>?>(null) } // text, isError
    var reload by remember { mutableIntStateOf(0) }
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
    var showSetup by remember { mutableStateOf(false) }
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
    /** A git command running in the terminal sheet: its title and arguments. */
    var gitTerminal by remember { mutableStateOf<Pair<String, List<String>>?>(null) }

    LaunchedEffect(dir, refreshTick, reload) {
        launch { status = git.status(dir) }
        launch { stacks = withContext(Dispatchers.IO) { runCatching { StackDetector.detect(dir) }.getOrDefault(emptyList()) } }
        launch { capabilities = withContext(Dispatchers.IO) {
            runCatching { dev.yurlungr.app.web.ProjectDetector.detect(dir) }.getOrElse {
                dev.yurlungr.app.web.ProjectCapabilities(File(dir,"gradlew").isFile,emptyList())
            }
        } }
    }

    /** [quiet]: say nothing when it works (stage, discard…); failures are always shown. */
    fun runGit(label: String, quiet: Boolean = false, onSuccess: () -> Unit = {}, block: suspend () -> dev.yurlungr.app.runtime.CommandResult) {
        if (busy != null) return
        busy = label
        message = null
        scope.launch {
            val r = block()
            if (r.ok) onSuccess()
            // On failure, say why in plain words (not installed, timed out, exit code) before git's own lines.
            message = if (r.ok && quiet) null else (if (r.ok) r.output.trim().lines().takeLast(6).joinToString("\n").ifBlank { "$label: done" } else r.describe(label)) to !r.ok
            busy = null
            reload++
        }
    }

    var commitStagedOnly by remember { mutableStateOf(false) }
    fun doCommit(text: String) =
        runGit("Commit", onSuccess = { commitMessage = "" }) { if (commitStagedOnly) git.commitStaged(dir, text) else git.commitAll(dir, text) }
    fun commit(text: String, stagedOnly: Boolean) = scope.launch {
        commitStagedOnly = stagedOnly
        val (name, email) = git.identity()
        if (name.isBlank() || email.isBlank()) askIdentity = text else doCommit(text)
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding()
            .imePadding(),
    ) {
        Header(dir, paths.projects, onBack, onSwitchProject, onAllProjects)
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
                    if (toPush > 0 && busy == null) runGit("Push") { git.push(dir, s) } else tab = ProjectTab.Changes
                }
            }
        }
        }
        // Right after cloning (or creating) a project: offer to install what it needs.
        val missing = stacks.orEmpty().filter { it.canInstall && it.installed == false }
        if (missing.isNotEmpty() && !setupBannerDismissed) {
            SetupBanner(
                missing,
                onInstall = { runInTerminal(installCommand(dir, missing), "Install dependencies · ${dir.name}") },
                onDetails = { showSetup = true },
                onDismiss = { setupBannerDismissed = true; SetupBannerPrefs.dismiss(context, dir) },
            )
        }
        status?.error?.let { e ->
            GitErrorCard(
                e,
                onInstall = { runInTerminal(dev.yurlungr.app.runtime.setupScriptCommand("dev-tools-setup.sh"), "Developer tools") },
                onRetry = { reload++ },
            )
        }
        dev.yurlungr.app.web.WebRunCard(dir, onLogs = onOpenTerminal)
        AnimatedVisibility(headerVisible, enter = reveal, exit = collapse) {
            SectionTabs(tab, changeCount = status?.changes?.size ?: 0) { tab = it }
        }
        Box(Modifier.weight(1f).nestedScroll(headerScroll)) {
            when (tab) {
                ProjectTab.Changes -> ChangesTab(
                    dir = dir,
                    git = git,
                    status = status,
                    busy = busy,
                    message = message,
                    onDismissMessage = { message = null },
                    onInit = { runGit("Initialize") { git.init(dir) } },
                    commitMessage = commitMessage,
                    onCommitMessageChange = { commitMessage = it },
                    onCommit = ::commit,
                    onOpenFile = onOpenFile,
                    onGit = { label, block -> runGit(label, quiet = true, block = block) },
                    // Shown live in a terminal sheet: progress, prompts and git's own messages.
                    onPull = { gitTerminal = "Pull" to listOf("pull", "--ff-only") },
                    onPush = {
                        status?.let { s ->
                            gitTerminal = "Push" to if (s.upstream == null) listOf("push", "-u", "origin", "HEAD") else listOf("push")
                        }
                    },
                    onAddGitignore = {
                        scope.launch {
                            git.addDefaultGitignore(dir)
                            reload++
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
                ProjectTab.Files -> FilesTab(dir, status, git, refreshTick to reload, onOpenFile)
                ProjectTab.History -> HistoryView(dir, git, status, refreshKey = refreshTick * 1000 + reload)
                ProjectTab.Chats -> ChatsTab(dir, onOpenChat)
            }
        }
        ActionBar(dir, isAndroid, onNewChat, runInTerminal, onOpenTerminal,
            hasWeb = capabilities?.web?.isNotEmpty() == true,
            onRunWeb = {
                val run = dev.yurlungr.app.web.WebRunSessions.get(dir)
                when {
                    run?.state == dev.yurlungr.app.web.WebRunState.Running -> dev.yurlungr.app.web.WebRunSessions.open(context, run)
                    run?.state?.active == true -> { dev.yurlungr.app.web.WebRunSessions.logs(run); onOpenTerminal() }
                    else -> capabilities?.web?.takeIf { it.isNotEmpty() }?.let { projects ->
                        scope.launch {
                            // Anything it can't decide alone (no Node.js, no start command…) opens the options.
                            if (dev.yurlungr.app.web.WebQuickRun.start(context, dir, projects) != null) showWebRun = true
                        }
                    }
                }
            },
            onRunWebOptions = { showWebRun = true },
            hasGit = status?.isRepo == true, onGitActions = { showGitActions = true },
            hasStacks = stacks.orEmpty().isNotEmpty(), onSetup = { showSetup = true },
            setupNeeded = stacks.orEmpty().any { it.canInstall && it.installed == false })
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

    if (showGitActions) status?.takeIf { it.isRepo }?.let { s ->
        val agents by androidx.compose.runtime.produceState(emptyList<Agent>()) {
            value = withContext(Dispatchers.IO) { agentStatuses(paths).filter { it.installed }.map { it.agent } }
        }
        GitActionsSheet(
            dir, git, s, agents,
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
                    .weight(if (badge) 1.45f else 1f)
                    .height(36.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(bg)
                    .clickable { onSelect(t) },
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    t.label,
                    style = MaterialTheme.typography.labelLarge,
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

/**
 * The project dock: Chat (pick an agent) as the main button, then compact tools (Build for
 * Android, Run for web, Setup, Git, Terminal) with a dot when one needs you (dependencies
 * missing) or is live (a web server running).
 */
@Composable
private fun ActionBar(dir: File, isAndroid: Boolean, onNewChat: (Agent) -> Unit, runInTerminal: (String, String) -> Unit,
    onOpenTerminal: () -> Unit, hasWeb: Boolean, onRunWeb: () -> Unit, hasGit: Boolean, onGitActions: () -> Unit,
    hasStacks: Boolean, onSetup: () -> Unit, setupNeeded: Boolean = false, onRunWebOptions: () -> Unit = onRunWeb) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val tiles = AppColors.tiles
    val installed by androidx.compose.runtime.produceState(emptyList<Agent>()) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            agentStatuses(RuntimePaths(context)).filter { it.installed }.map { it.agent }
        }
    }
    val webState = dev.yurlungr.app.web.WebRunSessions.get(dir)?.state
    var agentMenu by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(26.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 12.dp)
            .clip(shape)
            .background(colors.surfaceContainer)
            .border(1.dp, colors.outlineVariant, shape)
            .padding(6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Box(Modifier.weight(1.7f)) {
            val enabled = installed.isNotEmpty()
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(if (enabled) colors.primary else colors.surfaceContainerHigh)
                    .clickable(enabled = enabled) { if (installed.size == 1) onNewChat(installed[0]) else agentMenu = true },
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val tint = if (enabled) colors.onPrimary else colors.outline
                PhIcon(Ph.ChatCircle, null, size = 20.dp, tint = tint)
                Spacer(Modifier.width(8.dp))
                Text("Chat", style = MaterialTheme.typography.titleMedium, fontSize = 16.sp, color = tint)
            }
            DropdownMenu(expanded = agentMenu, onDismissRequest = { agentMenu = false }) {
                for (agent in installed) {
                    DropdownMenuItem(
                        leadingIcon = { AgentBadge(agent, size = 26.dp) },
                        text = { Text(agent.displayName) },
                        onClick = {
                            agentMenu = false
                            onNewChat(agent)
                        },
                    )
                }
            }
        }
        Spacer(Modifier.width(4.dp))
        if (isAndroid) {
            DockButton("Build", Ph.Hammer, tiles.lime, Modifier.weight(1f)) {
                runInTerminal(
                    "cd ${shellQuote(dir.path)} && bash ./gradlew assembleDebug && " +
                        "yurlungr-install \"\$(ls -t app/build/outputs/apk/debug/*.apk | head -1)\"",
                    "Build ${dir.name}",
                )
            }
        }
        if (hasWeb) {
            // Says what a tap does now: start it, or open the app once it's up.
            val running = webState == dev.yurlungr.app.web.WebRunState.Running
            val starting = webState?.active == true && !running
            DockButton(
                when { running -> "Open"; starting -> "Starting"; else -> "Run" },
                if (running) Ph.Globe else Ph.Play, tiles.lime, Modifier.weight(1f),
                dot = when { running -> AppColors.status.success; starting -> AppColors.status.warning; else -> null },
                onLongClick = onRunWebOptions, onClick = onRunWeb,
            )
        }
        if (hasStacks) DockButton("Setup", Ph.Wrench, tiles.sky, Modifier.weight(1f), dot = if (setupNeeded) AppColors.status.warning else null, onClick = onSetup)
        if (hasGit) DockButton("Git", Ph.GitBranch, tiles.lilac, Modifier.weight(1f), onClick = onGitActions)
        Box(Modifier.weight(1f)) {
            var terminalMenu by remember { mutableStateOf(false) }
            val newHere = { runInTerminal("cd ${shellQuote(dir.path)}", dir.name) }
            DockButton("Terminal", Ph.TerminalWindow, null, Modifier.fillMaxWidth()) {
                // With terminals already open, ask: reuse one (those in this project first) or start fresh.
                if (TerminalSessions.sessions.isEmpty()) newHere() else terminalMenu = true
            }
            TerminalMenu(dir, terminalMenu, onDismiss = { terminalMenu = false }, onNew = { terminalMenu = false; newHere() }) { s ->
                terminalMenu = false
                TerminalSessions.select(s)
                onOpenTerminal()
            }
        }
    }
}

/** Open terminals (this project's first, marked "here") plus "New terminal in <project>". */
@Composable
private fun TerminalMenu(dir: File, expanded: Boolean, onDismiss: () -> Unit, onNew: () -> Unit, onOpen: (TerminalSession) -> Unit) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        val entries = remember(expanded) {
            TerminalSessions.sessions.mapIndexed { i, s ->
                val cwd = runCatching { s.cwd }.getOrNull()
                Triple(i, s, cwd)
            }.sortedByDescending { (_, _, cwd) -> cwd != null && (cwd == dir.path || cwd.startsWith(dir.path + "/")) }
        }
        Text(
            "Open terminal",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        )
        DropdownMenuItem(
            leadingIcon = { PhIcon(Ph.Plus, null, size = 18.dp, tint = MaterialTheme.colorScheme.primary) },
            text = { Text("New terminal in ${dir.name}") },
            onClick = onNew,
        )
        HorizontalDivider()
        for ((i, s, cwd) in entries) {
            val here = cwd != null && (cwd == dir.path || cwd.startsWith(dir.path + "/"))
            DropdownMenuItem(
                leadingIcon = {
                    Box(
                        Modifier.size(26.dp).clip(RoundedCornerShape(8.dp)).background(if (here) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest),
                        contentAlignment = Alignment.Center,
                    ) { Text("${i + 1}", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 12.sp) }
                },
                text = {
                    Column {
                        Text(TerminalSessions.title(s, i), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            listOfNotNull(
                                cwd?.let { cwdLabel(it) },
                                if (!s.isRunning) "exited" else if (here) "here" else null,
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (here) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                onClick = { onOpen(s) },
            )
        }
    }
}

/** A dock tool: a tinted icon chip over a short label, with an optional status dot. */
@Composable
private fun DockButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tile: dev.yurlungr.app.ui.Tile?,
    modifier: Modifier = Modifier,
    dot: Color? = null,
    /** Options, when the tap already acts. */
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier
            .height(56.dp)
            .clip(RoundedCornerShape(18.dp))
            .combinedClickable(onClickLabel = label, onLongClickLabel = onLongClick?.let { "$label options" }, onLongClick = onLongClick, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box {
            Box(
                Modifier.size(width = 40.dp, height = 28.dp).clip(RoundedCornerShape(14.dp))
                    .background(tile?.fill ?: colors.surfaceContainerHighest),
                contentAlignment = Alignment.Center,
            ) { PhIcon(icon, null, size = 17.dp, tint = tile?.on ?: colors.onSurface) }
            if (dot != null) {
                // Ringed in the dock colour so it reads on any tile.
                Box(
                    Modifier.align(Alignment.TopEnd).size(11.dp).clip(CircleShape)
                        .background(colors.surfaceContainer).padding(2.dp).clip(CircleShape).background(dot),
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(label, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = colors.onSurface, maxLines = 1, softWrap = false)
    }
}

// ---- Changes -------------------------------------------------------------------------

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

// ---- Files ---------------------------------------------------------------------------

/**
 * Expandable file tree. In a repository every file shows its Git state (a status pill, "staged",
 * ignored files dimmed) and a filter row narrows it to uncommitted, ignored or not-ignored files.
 * Pull down to re-read the tree and Git's state.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun FilesTab(root: File, status: GitStatus?, git: Git, refreshKey: Pair<Int, Int>, onOpenFile: (File) -> Unit) {
    val open = remember(root.path) { mutableStateMapOf<String, List<File>>() }
    var top by remember(root.path) { mutableStateOf<List<File>?>(null) }
    var refreshing by remember(root.path) { mutableStateOf(false) }
    var filter by rememberSaveable(root.path) { mutableStateOf(FileTreeFilter.All) }
    var ignored by remember(root.path) { mutableStateOf<Set<String>?>(null) }
    var checkingIgnored by remember(root.path) { mutableStateOf(false) }
    var treeReload by remember(root.path) { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    suspend fun list(dir: File) = withContext(Dispatchers.IO) {
        dir.listFiles { f -> f.name != ".git" }.orEmpty().sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }))
    }
    // Refreshing here also re-reads changes without requiring navigation away from the project.
    var treeStatus by remember(root.path) { mutableStateOf(status) }
    LaunchedEffect(root, status, refreshKey, treeReload) {
        treeStatus = if (treeReload > 0) git.status(root) else status
        top = list(root)
        for (path in open.keys.toList()) open[path] = list(File(path))
        // Keep the previous ignore rules visible while they're re-read (no flicker on refresh).
        checkingIgnored = treeStatus?.isRepo == true
        if (checkingIgnored) {
            ignored = runCatching { git.ignoredPaths(root) }.getOrNull()
            checkingIgnored = false
        } else ignored = null
        refreshing = false
    }
    val isRepo = treeStatus?.isRepo == true
    val gitState = remember(treeStatus, ignored) { FileTreeGitState(ignored.orEmpty(), treeStatus?.changes.orEmpty()) }
    // Uncommitted only needs the status; the ignore-based filters wait for the ignore rules.
    val effectiveFilter = when {
        !isRepo -> FileTreeFilter.All
        filter == FileTreeFilter.Changed -> filter
        ignored != null -> filter
        else -> FileTreeFilter.All
    }
    // Built off the main thread: every row needs a stat() or two, and big expanded trees have thousands.
    // Keeps showing the previous rows until the new ones are ready; null only before the first build.
    val openNow = open.toMap()
    val rows by produceState<List<TreeEntry>?>(null, top, openNow, gitState, effectiveFilter) {
        val listed = top ?: return@produceState
        value = withContext(Dispatchers.IO) {
            if (effectiveFilter == FileTreeFilter.Changed) {
                // Include deleted paths too; a filesystem-only tree cannot show them.
                gitState.changesByPath.keys.sorted().map { File(root, it).let { f -> TreeEntry(f, 0, f.isDirectory, f.isFile) } }
            } else buildList {
                fun add(files: List<File>, depth: Int) {
                    for (f in files) {
                        val isDir = f.isDirectory
                        val relative = f.relativeTo(root).invariantSeparatorsPath
                        if (!gitState.includes(relative, isDir, effectiveFilter)) continue
                        add(TreeEntry(f, depth, isDir, enabled = isDir || f.exists()))
                        openNow[f.path]?.let { add(it, depth + 1) }
                    }
                }
                add(listed, 0)
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        if (isRepo) {
            val changes = treeStatus?.changes.orEmpty()
            val colors = MaterialTheme.colorScheme
            // Segmented filter: the whole tree, what changed, what git ignores, or the source without it.
            Row(
                Modifier.padding(horizontal = 12.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp))
                    .background(colors.surfaceContainer).padding(3.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                for (option in listOf(FileTreeFilter.All, FileTreeFilter.Changed, FileTreeFilter.NotIgnored, FileTreeFilter.Ignored)) {
                    val needsIgnore = option == FileTreeFilter.Ignored || option == FileTreeFilter.NotIgnored
                    val usable = !needsIgnore || ignored != null
                    val on = effectiveFilter == option
                    val bg by animateColorAsState(if (on) colors.surfaceContainerHighest else Color.Transparent, tween(Motion.SHORT), label = "filter")
                    Row(
                        Modifier.weight(if (option == FileTreeFilter.Changed && changes.isNotEmpty()) 1.3f else 1f).height(32.dp)
                            .clip(RoundedCornerShape(11.dp)).background(bg).clickable(enabled = usable) { filter = option },
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(option.label, style = MaterialTheme.typography.labelMedium, maxLines = 1, softWrap = false,
                            fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                            color = when { !usable -> colors.outline; on -> colors.onSurface; else -> colors.onSurfaceVariant })
                        if (option == FileTreeFilter.Changed && changes.isNotEmpty()) {
                            Spacer(Modifier.width(5.dp))
                            Text(if (changes.size > 99) "99+" else "${changes.size}", fontSize = 10.sp, fontWeight = FontWeight.Bold,
                                color = AppColors.tiles.peach.on, maxLines = 1, softWrap = false,
                                modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(AppColors.tiles.peach.fill).padding(horizontal = 5.dp))
                        }
                    }
                }
            }
            // Where things stand, by kind, with the same colours as the rows below.
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp).heightIn(min = 36.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when {
                    checkingIgnored && ignored == null -> SummaryText("Reading Git's ignore rules…")
                    ignored == null -> SummaryText("Couldn't read Git's ignore rules. Pull down to retry.")
                    changes.isEmpty() -> {
                        PhIcon(Ph.CheckCircle, null, size = 14.dp, tint = AppColors.status.success)
                        SummaryText("Everything is committed", Modifier.padding(start = 0.dp))
                    }
                    else -> {
                        val tones = AppColors.status
                        val counts = changes.groupingBy { it.label }.eachCount()
                        for ((label, tone) in listOf("new" to tones.success, "added" to tones.success, "modified" to tones.warning,
                            "renamed" to colors.primary, "deleted" to colors.error, "conflict" to colors.error)) {
                            val n = counts[label] ?: continue
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(7.dp).clip(CircleShape).background(tone))
                                Spacer(Modifier.width(5.dp))
                                SummaryText("$n $label")
                            }
                        }
                        val staged = changes.count { it.isStaged }
                        if (staged > 0) SummaryText("$staged staged")
                    }
                }
                Spacer(Modifier.weight(1f))
                if (open.isNotEmpty() && effectiveFilter != FileTreeFilter.Changed) {
                    TextButton(onClick = { open.clear() }) { Text("Collapse all", style = MaterialTheme.typography.labelMedium) }
                }
            }
        }
        val pullState = androidx.compose.material3.pulltorefresh.rememberPullToRefreshState()
        androidx.compose.material3.pulltorefresh.PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = { refreshing = true; treeReload++ },
            state = pullState,
            modifier = Modifier.fillMaxWidth().weight(1f),
            indicator = {
                androidx.compose.material3.pulltorefresh.PullToRefreshDefaults.IndicatorBox(
                    state = pullState,
                    isRefreshing = refreshing,
                    modifier = Modifier.align(Alignment.TopCenter),
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ) {
                    if (refreshing) dev.yurlungr.app.ui.WaveDots(Modifier.align(Alignment.Center), dotSize = 4.dp)
                    else PhIcon(Ph.ArrowClockwise, "Pull to refresh", Modifier.align(Alignment.Center), size = 18.dp)
                }
            },
        ) {
        LazyColumn(contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp), modifier = Modifier.fillMaxSize()) {
            val loaded = rows
            if (top == null || loaded == null) items(6) { SkeletonListItem() }
            else if (loaded.isEmpty()) item {
                Text(if (effectiveFilter == FileTreeFilter.All) "Empty folder" else "No ${effectiveFilter.label.lowercase()} files here.",
                    modifier = Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(loaded.orEmpty(), key = { it.file.path }) { entry ->
                val f = entry.file
                val depth = entry.depth
                val relative = f.relativeTo(root).invariantSeparatorsPath
                val change = gitState.changesByPath[relative]
                val changed = gitState.isChanged(relative, entry.isDir)
                val isIgnored = ignored != null && gitState.isIgnored(relative)
                val flat = effectiveFilter == FileTreeFilter.Changed
                TreeRow(
                    name = f.name,
                    folder = if (flat) relative.substringBeforeLast('/', "").ifEmpty { null } else null,
                    depth = depth, isDir = entry.isDir, expanded = open.containsKey(f.path),
                    enabled = entry.enabled,
                    change = change,
                    changedInside = if (entry.isDir && changed) gitState.changedInside[relative] ?: 0 else 0,
                    ignored = isIgnored,
                    modifier = Modifier.animateItem(),
                ) {
                    when {
                        !entry.isDir -> onOpenFile(f)
                        open.containsKey(f.path) -> open.keys.filter { it == f.path || it.startsWith(f.path + "/") }.forEach { open.remove(it) }
                        else -> scope.launch { open[f.path] = list(f) }
                    }
                }
            }
        }
        }
    }
}

/** A file-tree row with its file-system facts read once, off the main thread. */
private data class TreeEntry(val file: File, val depth: Int, val isDir: Boolean, val enabled: Boolean)

@Composable
private fun SummaryText(text: String, modifier: Modifier = Modifier) =
    Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, modifier = modifier)

/** An icon and tint for a file, by its kind (code, config, docs, images, scripts). */
@Composable
private fun fileIcon(name: String): Pair<androidx.compose.ui.graphics.vector.ImageVector, Color> {
    val colors = MaterialTheme.colorScheme
    val ext = name.substringAfterLast('.', "").lowercase()
    return when {
        ext in setOf("png", "jpg", "jpeg", "gif", "webp", "svg", "ico", "bmp") -> Ph.Image to AppColors.status.success
        ext in setOf("md", "markdown", "txt", "rst", "adoc") || name.equals("LICENSE", true) -> Ph.BookOpen to colors.secondary
        ext in setOf("sh", "bash", "zsh", "bat", "ps1") || name == "gradlew" -> Ph.Terminal to colors.tertiary
        ext in setOf("json", "yml", "yaml", "toml", "xml", "properties", "gradle", "kts", "lock", "ini", "cfg", "conf") ||
            name.startsWith(".") -> Ph.GearSix to colors.onSurfaceVariant
        ext in setOf("kt", "java", "ts", "tsx", "js", "jsx", "mjs", "cjs", "py", "rs", "go", "dart", "swift", "c", "h", "cpp",
            "cs", "rb", "php", "html", "css", "scss", "vue", "svelte", "astro", "sql") -> Ph.FileText to colors.primary
        else -> Ph.File to colors.onSurfaceVariant
    }
}

@Composable
private fun TreeRow(
    name: String,
    /** In the flat Changed list: the folder it's in. */
    folder: String? = null,
    depth: Int, isDir: Boolean, expanded: Boolean,
    change: GitChange? = null,
    /** For folders: how many changed files are inside. */
    changedInside: Int = 0,
    ignored: Boolean = false, enabled: Boolean = true, modifier: Modifier = Modifier, onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val guide = colors.outlineVariant.copy(alpha = 0.6f)
    val (icon, iconTint) = if (isDir) Ph.FolderSimple to colors.primary else fileIcon(name)
    val tone = change?.let { changeTone(it).second }
    Row(
        modifier
            .fillMaxWidth()
            .height(if (folder != null) 48.dp else 40.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick)
            // A thin guide per level, so deep trees stay readable.
            .drawBehind {
                for (level in 0 until depth) {
                    val x = (12 + 22 * level + 7).dp.toPx()
                    drawLine(guide, androidx.compose.ui.geometry.Offset(x, 0f), androidx.compose.ui.geometry.Offset(x, size.height), strokeWidth = 1.dp.toPx())
                }
            }
            .padding(start = 12.dp + 22.dp * depth, end = 10.dp)
            .then(if (ignored) Modifier.alpha(0.5f) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(14.dp)) {
            if (isDir) PhIcon(if (expanded) Ph.CaretDown else Ph.CaretRight, null, size = 12.dp, tint = colors.onSurfaceVariant)
        }
        Spacer(Modifier.width(8.dp))
        PhIcon(icon, null, size = 18.dp, tint = iconTint)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                name,
                color = tone ?: colors.onSurface,
                fontFamily = FontFamily.Monospace,
                fontSize = 13.5.sp,
                fontWeight = if (change != null) FontWeight.Medium else FontWeight.Normal,
                textDecoration = if (change?.label == "deleted") androidx.compose.ui.text.style.TextDecoration.LineThrough else null,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (folder != null) Text(folder, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        when {
            change != null && !isDir -> {
                // Staged: already in the next commit.
                if (change.isStaged) PhIcon(Ph.CheckCircle, "Staged", size = 14.dp, tint = AppColors.status.success, modifier = Modifier.padding(end = 6.dp))
                StatusLetter(change, size = 20.dp)
            }
            changedInside > 0 -> Text(
                if (changedInside > 99) "99+" else "$changedInside",
                fontSize = 11.sp, fontWeight = FontWeight.Bold, color = AppColors.status.warning,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(AppColors.status.warning.copy(alpha = 0.14f)).padding(horizontal = 7.dp, vertical = 1.dp),
            )
            ignored -> Text("ignored", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
        }
    }
}

/** Chats started in this project, newest activity first. */
@Composable
private fun ChatsTab(dir: File, onOpenChat: (ChatSession) -> Unit) {
    val chats = ChatSessions.chats.filter { it.cwd.path == dir.path }
    LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()) {
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
                        chat.agent.displayName + when (chat.state) {
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
