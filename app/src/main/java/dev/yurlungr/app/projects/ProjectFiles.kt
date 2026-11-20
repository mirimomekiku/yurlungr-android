package dev.yurlungr.app.projects

import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.contentDescription
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

// ---- Files ---------------------------------------------------------------------------

/**
 * Expandable file tree. In a repository every file shows its Git state (a status pill, "staged",
 * ignored files dimmed) and a filter row narrows it to uncommitted, ignored or not-ignored files.
 * Pull down to re-read the tree and Git's state.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun FilesTab(root: File, status: GitStatus?, git: Git, refreshKey: Pair<Int, Int>, onOpenFile: (File) -> Unit,
    onOpenFileAt: (File, Int?) -> Unit = { f, _ -> onOpenFile(f) }) {
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
    // After a git operation only git's state changed: the markers follow it, and the folders on
    // screen are listed again (a discard or stash can remove files). Cheap: no git runs here.
    LaunchedEffect(root, status) {
        treeStatus = status
        top = list(root)
        for (path in open.keys.toList()) open[path] = list(File(path))
    }
    // A refresh (pull down, or the project's): git's status read here too.
    LaunchedEffect(root, refreshKey, treeReload) {
        if (treeReload > 0) treeStatus = git.status(root)
        top = list(root)
        for (path in open.keys.toList()) open[path] = list(File(path))
        refreshing = false
    }
    // Git's ignored paths walk the tree (ls-files --ignored): only on a refresh, not after every stage or commit.
    val repoKnown = treeStatus?.isRepo
    LaunchedEffect(root, refreshKey, treeReload, repoKnown) {
        // Keep the previous ignore rules visible while they're re-read (no flicker on refresh).
        checkingIgnored = repoKnown == true
        if (checkingIgnored) {
            ignored = runCatching { git.ignoredPaths(root) }.getOrNull()
            checkingIgnored = false
        } else ignored = null
    }
    var historyOf by remember(root.path) { mutableStateOf<String?>(null) }
    historyOf?.let { path -> FileHistorySheet(root, git, path) { historyOf = null } }
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

    var searching by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }
    if (creating) dev.yurlungr.app.editor.NewFileSheet(root, onDismiss = { creating = false }) { creating = false; treeReload++; onOpenFile(it) }
    if (searching) ProjectSearchSheet(root, onDismiss = { searching = false }) { f, line -> searching = false; onOpenFileAt(f, line) }
    Column(Modifier.fillMaxSize()) {
        // Search across every file (ripgrep), above the tree.
        Row(
            Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp).fillMaxWidth().height(40.dp)
                .clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceContainer)
                .clickable { searching = true }.padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PhIcon(Ph.MagnifyingGlass, null, size = 16.dp, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
            Text("Search in files", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            Box(Modifier.size(32.dp).clip(RoundedCornerShape(16.dp)).clickable { creating = true }, contentAlignment = Alignment.Center) {
                PhIcon(Ph.Plus, "New file", size = 16.dp, tint = MaterialTheme.colorScheme.primary)
            }
        }
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
                            .semantics(mergeDescendants = true) { role = androidx.compose.ui.semantics.Role.Tab; selected = on }
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
                    // A committed (or changed) file in a repository: its history is a long press away.
                    onHistory = if (isRepo && !entry.isDir && !isIgnored && change?.untracked != true) ({ historyOf = relative }) else null,
                    onOpen = { onOpenFile(f) },
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
    modifier: Modifier = Modifier,
    /** In the flat Changed list: the folder it's in. */
    folder: String? = null,
    depth: Int, isDir: Boolean, expanded: Boolean,
    change: GitChange? = null,
    /** For folders: how many changed files are inside. */
    changedInside: Int = 0,
    ignored: Boolean = false, enabled: Boolean = true,
    /** Long press offers "History of this file". */
    onHistory: (() -> Unit)? = null,
    onOpen: () -> Unit = {},
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val guide = colors.outlineVariant.copy(alpha = 0.6f)
    val (icon, iconTint) = if (isDir) Ph.FolderSimple to colors.primary else fileIcon(name)
    val tone = change?.let { changeTone(it).second }
    var menu by remember { mutableStateOf(false) }
    Box(modifier) {
    if (onHistory != null) DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
        DropdownMenuItem(text = { Text("Open") }, leadingIcon = { PhIcon(Ph.FileText, null, size = 18.dp) }, onClick = { menu = false; onOpen() })
        DropdownMenuItem(text = { Text("History of this file") }, leadingIcon = { PhIcon(Ph.ClockCounterClockwise, null, size = 18.dp) },
            onClick = { menu = false; onHistory() })
    }
    Row(
        Modifier
            .fillMaxWidth()
            .height(if (folder != null) 48.dp else 40.dp)
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(enabled = enabled, onClick = onClick, onLongClick = onHistory?.let { { menu = true } }, onLongClickLabel = onHistory?.let { "File actions" })
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
                modifier = Modifier.clip(RoundedCornerShape(50)).background(AppColors.status.warning.copy(alpha = 0.14f)).padding(horizontal = 7.dp, vertical = 1.dp)
                    .semantics { contentDescription = "$changedInside changed inside" },
            )
            ignored -> Text("ignored", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
        }
    }
    }
}
