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

    LaunchedEffect(dir, refreshTick, reload) {
        launch { status = git.status(dir) }
        launch { capabilities = withContext(Dispatchers.IO) {
            runCatching { dev.yurlungr.app.web.ProjectDetector.detect(dir) }.getOrElse {
                dev.yurlungr.app.web.ProjectCapabilities(File(dir,"gradlew").isFile,emptyList())
            }
        } }
    }

    fun runGit(label: String, onSuccess: () -> Unit = {}, block: suspend () -> dev.yurlungr.app.runtime.CommandResult) {
        if (busy != null) return
        busy = label
        message = null
        scope.launch {
            val r = block()
            if (r.ok) onSuccess()
            message = (r.output.trim().lines().takeLast(6).joinToString("\n").ifBlank { if (r.ok) "$label: done" else "$label failed" }) to !r.ok
            busy = null
            reload++
        }
    }

    fun commit(text: String) = scope.launch {
        val (name, email) = git.identity()
        if (name.isBlank() || email.isBlank()) {
            askIdentity = text
        } else {
            runGit("Commit", onSuccess = { commitMessage = "" }) { git.commitAll(dir, text) }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding()
            .imePadding(),
    ) {
        Header(dir, paths.projects, onBack, onSwitchProject, onAllProjects)
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
        dev.yurlungr.app.web.WebRunCard(dir, onLogs = onOpenTerminal)
        SectionTabs(tab, changeCount = status?.changes?.size ?: 0) { tab = it }
        Box(Modifier.weight(1f)) {
            when (tab) {
                ProjectTab.Changes -> ChangesTab(
                    dir = dir,
                    git = git,
                    status = status,
                    busy = busy,
                    message = message,
                    onInit = { runGit("Initialize") { git.init(dir) } },
                    commitMessage = commitMessage,
                    onCommitMessageChange = { commitMessage = it },
                    onCommit = ::commit,
                    onPull = { runGit("Pull") { git.pull(dir) } },
                    onPush = { status?.let { s -> runGit("Push") { git.push(dir, s) } } },
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
                                "gh repo create ${shellQuote(dir.name)} --private --source=. --remote=origin --push",
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
            hasWeb = capabilities?.web?.isNotEmpty() == true, onRunWeb = { showWebRun = true })
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
                    git.setIdentity(name, email)
                    runGit("Commit", onSuccess = { commitMessage = "" }) { git.commitAll(dir, pending) }
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
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for (t in ProjectTab.entries) {
            val on = t == selected
            val bg by animateColorAsState(if (on) thumb else Color.Transparent, tween(Motion.SHORT), label = "tab")
            Row(
                Modifier
                    .weight(1f)
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
                )
                if (t == ProjectTab.Changes && changeCount > 0) {
                    Spacer(Modifier.width(5.dp))
                    val peach = AppColors.tiles.peach
                    Box(
                        Modifier.height(18.dp).widthIn(min = 18.dp).clip(CircleShape).background(peach.fill).padding(horizontal = 5.dp),
                        contentAlignment = Alignment.Center,
                    ) { Text(if (changeCount > 99) "99+" else "$changeCount", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = peach.on) }
                }
            }
        }
    }
}

/** Chat (pick an agent) · Build & install (Android projects) · Terminal. */
@Composable
private fun ActionBar(dir: File, isAndroid: Boolean, onNewChat: (Agent) -> Unit, runInTerminal: (String, String) -> Unit,
    onOpenTerminal: () -> Unit, hasWeb: Boolean, onRunWeb: () -> Unit) {
    val context = LocalContext.current
    val installed by androidx.compose.runtime.produceState(emptyList<Agent>()) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            agentStatuses(RuntimePaths(context)).filter { it.installed }.map { it.agent }
        }
    }
    var agentMenu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.weight(2f)) {
            ActionTile("Chat", Ph.ChatCircle, primary = true, enabled = installed.isNotEmpty()) {
                if (installed.size == 1) onNewChat(installed[0]) else agentMenu = true
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
        if (isAndroid) {
            ActionTile("Build", Ph.Play, Modifier.weight(1f), tile = AppColors.tiles.lime) {
                runInTerminal(
                    "cd ${shellQuote(dir.path)} && bash ./gradlew assembleDebug && " +
                        "yurlungr-install \"\$(ls -t app/build/outputs/apk/debug/*.apk | head -1)\"",
                    "Build ${dir.name}",
                )
            }
        }
        if (hasWeb) {
            ActionTile("Run", Ph.Globe, Modifier.weight(1f), tile = AppColors.tiles.lime, onClick = onRunWeb)
        }
        Box(Modifier.weight(1f)) {
            var terminalMenu by remember { mutableStateOf(false) }
            val newHere = { runInTerminal("cd ${shellQuote(dir.path)}", dir.name) }
            ActionTile("Terminal", Ph.TerminalWindow) {
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

@Composable
private fun ActionTile(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    enabled: Boolean = true,
    tile: dev.yurlungr.app.ui.Tile? = null,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val bg = when {
        primary -> colors.primary
        tile != null -> tile.fill
        else -> colors.surfaceContainer
    }
    val fg = when {
        primary -> colors.onPrimary
        tile != null -> tile.on
        else -> colors.onSurface
    }
    val shape = RoundedCornerShape(22.dp)
    val content: @Composable () -> Unit = {
        val tint = if (enabled) fg else colors.outline
        PhIcon(icon, null, size = if (primary) 20.dp else 18.dp, tint = tint)
        if (primary) {
            Spacer(Modifier.width(8.dp))
            Text(label, style = MaterialTheme.typography.titleMedium, fontSize = 16.sp, color = tint)
        } else {
            Spacer(Modifier.height(3.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = tint)
        }
    }
    val base = modifier
        .fillMaxWidth()
        .height(60.dp)
        .clip(shape)
        .background(if (enabled) bg else colors.surfaceContainerHigh)
        .then(if (!primary && tile == null) Modifier.border(1.dp, colors.outlineVariant, shape) else Modifier)
        .clickable(enabled = enabled, onClick = onClick)
    // The main action reads as one line (icon + label); the others stack.
    if (primary) {
        Row(base, horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) { content() }
    } else {
        Column(base, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) { content() }
    }
}

// ---- Changes -------------------------------------------------------------------------

@Composable
private fun ChangesTab(
    dir: File,
    git: Git,
    status: GitStatus?,
    busy: String?,
    message: Pair<String, Boolean>?,
    onInit: () -> Unit,
    commitMessage: String,
    onCommitMessageChange: (String) -> Unit,
    onCommit: (String) -> Unit,
    onPull: () -> Unit,
    onPush: () -> Unit,
    onAddGitignore: () -> Unit,
    onPublish: () -> Unit,
) {
    val expanded = remember(dir) { mutableStateMapOf<String, String?>() } // path -> diff (null = loading)
    val scope = rememberCoroutineScope()

    LazyColumn(
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        when {
            status == null -> items(5) { SkeletonListItem() }
            !status.isRepo -> item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("This folder isn't a git repository.", style = MaterialTheme.typography.bodyMedium)
                    Button(onClick = onInit, enabled = busy == null) { Text("Initialize git") }
                }
            }
            else -> {
                item { SyncRow(status, busy, onPull, onPush) }
                if (!status.hasOrigin && !status.unborn) {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "Not on GitHub yet.",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            FilledTonalButton(onClick = onPublish, enabled = busy == null) { Text("Publish to GitHub") }
                        }
                    }
                }
                message?.let { (text, isError) -> item { ResultBox(text, isError) } }
                if (!File(dir, ".gitignore").exists() && status.changes.any { looksGenerated(it.path) }) {
                    item {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    "No .gitignore: build output and caches would be committed.",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                FilledTonalButton(onClick = onAddGitignore, enabled = busy == null) { Text("Add standard .gitignore") }
                            }
                        }
                    }
                }
                if (status.changes.isEmpty()) {
                    item {
                        Text("No changes. Working tree clean.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                commitMessage,
                                onCommitMessageChange,
                                label = { Text("Commit message") },
                                modifier = Modifier.fillMaxWidth(),
                                maxLines = 4,
                            )
                            Button(
                                onClick = {
                                    onCommit(commitMessage.trim())
                                },
                                enabled = busy == null && commitMessage.isNotBlank(),
                            ) { Text("Commit all ${status.changes.size} changes") }
                        }
                    }
                    changeItems(status, expanded) { change ->
                        if (expanded.containsKey(change.path)) {
                            expanded.remove(change.path)
                        } else {
                            expanded[change.path] = null
                            scope.launch { expanded[change.path] = git.diff(dir, change, status.unborn) }
                        }
                    }
                }
            }
        }
    }
}

private fun looksGenerated(path: String): Boolean =
    path.startsWith(".gradle/") || path.startsWith("build/") || path.contains("/build/") ||
        path.startsWith("node_modules/") || path.startsWith(".kotlin/") || path == "local.properties"

private fun LazyListScope.changeItems(
    status: GitStatus,
    expanded: Map<String, String?>,
    onToggle: (GitChange) -> Unit,
) {
    items(status.changes, key = { it.path }) { change ->
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            modifier = Modifier.fillMaxWidth().animateContentSize(),
        ) {
            Column(Modifier.clickable { onToggle(change) }.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        change.label.uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = when (change.label) {
                            "new", "added" -> Color(0xFF2DA44E)
                            "deleted" -> MaterialTheme.colorScheme.error
                            "conflict" -> Color(0xFFD29922)
                            else -> MaterialTheme.colorScheme.primary
                        },
                        modifier = Modifier.width(76.dp),
                    )
                    Text(
                        change.origPath?.let { "$it → ${change.path}" } ?: change.path,
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (expanded.containsKey(change.path)) {
                    Spacer(Modifier.size(8.dp))
                    val diff = expanded[change.path]
                    if (diff == null) SkeletonCode(lines = 6) else UnifiedDiff(diff)
                }
            }
        }
    }
}

@Composable
private fun SyncRow(status: GitStatus, busy: String?, onPull: () -> Unit, onPush: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(status.branch ?: "?", style = MaterialTheme.typography.titleSmall, fontFamily = FontFamily.Monospace)
            Text(
                when {
                    status.unborn -> "No commits yet"
                    status.upstream == null -> "Not pushed anywhere yet"
                    status.ahead == 0 && status.behind == 0 -> "Up to date with ${status.upstream}"
                    else -> "${status.ahead} to push · ${status.behind} to pull (${status.upstream})"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (busy != null) {
            WaveDots(dotSize = 5.dp)
            Spacer(Modifier.width(8.dp))
            Text(busy, style = MaterialTheme.typography.labelMedium)
        } else {
            TextButton(onClick = onPull, enabled = status.upstream != null) { Text("Pull") }
            TextButton(onClick = onPush, enabled = !status.unborn && status.hasOrigin) { Text("Push") }
        }
    }
}

@Composable
private fun ResultBox(text: String, isError: Boolean) {
    Text(
        text,
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
        lineHeight = 15.sp,
        color = if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerHighest)
            .padding(10.dp),
    )
}

/** Colors a unified diff (or the synthetic "new file" form). Capped for very large diffs. */
@Composable
fun UnifiedDiff(diff: String) {
    val lines = remember(diff) {
        diff.lines().filterNot { it.startsWith("diff --git") || it.startsWith("index ") }.take(1500)
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(Color(0xFF0D1117))
            .padding(8.dp),
    ) {
        for (line in lines) {
            Text(
                line,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                lineHeight = 15.sp,
                color = when {
                    line.startsWith("+++") || line.startsWith("---") -> Color(0xFF8B949E)
                    line.startsWith("@@") -> Color(0xFF79C0FF)
                    line.startsWith("+") -> Color(0xFF7EE787)
                    line.startsWith("-") -> Color(0xFFFF7B72)
                    else -> Color(0xFFE6EDF3)
                },
            )
        }
    }
}

// ---- Files ---------------------------------------------------------------------------

/** Expandable file tree, with an optional view of Git ignore and working-tree state. */
@Composable
private fun FilesTab(root: File, status: GitStatus?, git: Git, refreshKey: Pair<Int, Int>, onOpenFile: (File) -> Unit) {
    val open = remember(root.path) { mutableStateMapOf<String, List<File>>() }
    var top by remember(root.path) { mutableStateOf<List<File>?>(null) }
    var showGit by rememberSaveable(root.path) { mutableStateOf(false) }
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
        ignored = null
        checkingIgnored = treeStatus?.isRepo == true
        if (checkingIgnored) {
            ignored = runCatching { git.ignoredPaths(root) }.getOrNull()
            checkingIgnored = false
        }
    }
    val gitState = remember(treeStatus, ignored) { FileTreeGitState(ignored.orEmpty(), treeStatus?.changes.orEmpty()) }
    val effectiveFilter = if (showGit && ignored != null && treeStatus?.isRepo == true) filter else FileTreeFilter.All
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
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (treeStatus?.isRepo == true) {
                Chip("Git details", selected = showGit, onClick = { showGit = !showGit })
                if (showGit && ignored != null) FileTreeFilter.entries.forEach { option ->
                    Chip(option.label, selected = filter == option, onClick = { filter = option })
                }
            }
            TextButton(onClick = { treeReload++ }) { Text("Refresh") }
        }
        if (showGit && treeStatus?.isRepo == true && ignored == null) {
            Text(if (checkingIgnored) "Checking Git ignore rules…" else "Couldn't read ignore rules. Tap Refresh to retry.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        }
        LazyColumn(contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp), modifier = Modifier.weight(1f)) {
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
                val details = if (showGit && ignored != null && treeStatus?.isRepo == true) {
                    when {
                        isIgnored -> "Ignored"
                        change != null -> if (change.untracked) "Untracked" else {
                            val label = change.label.replaceFirstChar { it.uppercase() }
                            when {
                                change.index != ' ' && change.worktree != ' ' -> "$label · staged + unstaged"
                                change.index != ' ' -> "$label · staged"
                                else -> label
                            }
                        }
                        changed -> "Changes"
                        else -> "Not ignored"
                    }
                } else null
                TreeRow(
                    name = if (effectiveFilter == FileTreeFilter.Changed) relative else f.name,
                    depth = depth, isDir = entry.isDir, expanded = open.containsKey(f.path),
                    enabled = entry.enabled,
                    mark = change?.let(::changeMark) ?: if (changed) "•" else null,
                    details = details, ignored = showGit && isIgnored,
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

/** A file-tree row with its file-system facts read once, off the main thread. */
private data class TreeEntry(val file: File, val depth: Int, val isDir: Boolean, val enabled: Boolean)

private fun changeMark(c: GitChange) = when (c.label) {
    "new" -> "?"
    "added" -> "A"
    "deleted" -> "D"
    "conflict" -> "!"
    "renamed" -> "R"
    else -> "M"
}

@Composable
private fun TreeRow(name: String, depth: Int, isDir: Boolean, expanded: Boolean, mark: String?, details: String? = null, ignored: Boolean = false, enabled: Boolean = true, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val tones = AppColors.status
    val markColor = when (mark) {
        "?", "A" -> tones.success
        "D", "!" -> MaterialTheme.colorScheme.error
        else -> tones.warning
    }
    Row(
        modifier
            .fillMaxWidth()
            .height(42.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (mark != null && !isDir) MaterialTheme.colorScheme.surfaceContainer else Color.Transparent)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(start = 12.dp + 22.dp * depth, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(14.dp)) {
            if (isDir) PhIcon(if (expanded) Ph.CaretDown else Ph.CaretRight, null, size = 12.dp, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(8.dp))
        PhIcon(
            if (isDir) Ph.FolderSimple else Ph.FileText,
            null,
            size = 18.dp,
            tint = when {
                isDir -> MaterialTheme.colorScheme.primary
                mark != null -> markColor
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        Spacer(Modifier.width(10.dp))
        Text(
            name,
            color = if (ignored) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.5.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (details != null) Text(details, style = MaterialTheme.typography.labelSmall,
            color = if (mark != null) markColor else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 8.dp))
        else if (mark != null) Text(mark, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = markColor)
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
            Button(onClick = { onSave(name.trim(), email.trim()) }, enabled = name.isNotBlank() && email.contains('@')) { Text("Save & commit") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
