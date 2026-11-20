package dev.yurlungr.app.projects

import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.yurlungr.app.runtime.CommandResult
import dev.yurlungr.app.ui.AppColors
import dev.yurlungr.app.ui.AppSheet
import dev.yurlungr.app.ui.Ph
import dev.yurlungr.app.ui.PhIcon
import dev.yurlungr.app.ui.SkeletonCode
import dev.yurlungr.app.ui.SkeletonListItem
import dev.yurlungr.app.ui.WaveDots
import kotlinx.coroutines.launch
import java.io.File

/** Part of the next commit (staged in git's index). */
internal val GitChange.isStaged: Boolean get() = !untracked && index != ' ' && label != "conflict"

/** Has edits not yet staged (or is untracked). */
internal val GitChange.hasUnstaged: Boolean get() = untracked || (worktree != ' ' && label != "conflict")

/**
 * Uncommitted work, grouped like a commit is built: conflicts first, then what's staged (in the
 * next commit) and the other changes. Tick a file to stage or unstage it, tap it for its diff, ⋮
 * for full screen, open or discard. The commit box stays at the bottom.
 */
@Composable
internal fun ChangesTab(
    dir: File,
    git: Git,
    status: GitStatus?,
    busy: String?,
    message: Pair<String, Boolean>?,
    onDismissMessage: () -> Unit,
    onInit: () -> Unit,
    commitMessage: String,
    onCommitMessageChange: (String) -> Unit,
    /** Message, and whether to commit only the staged files. */
    onCommit: (String, Boolean) -> Unit,
    onPull: () -> Unit,
    onPush: () -> Unit,
    onAddGitignore: () -> Unit,
    onPublish: () -> Unit,
    onOpenFile: (File) -> Unit,
    /** Runs a quick git operation (stage, discard…), then refreshes. */
    onGit: (label: String, block: suspend () -> CommandResult) -> Unit,
    onResolveConflict: (File) -> Unit = onOpenFile,
    /** The merge, rebase, cherry-pick or revert waiting to be finished. */
    operation: GitOperation? = null,
    onContinueOperation: (GitOperation) -> Unit = {},
    onAbortOperation: (GitOperation) -> Unit = {},
    /** The user's changes a routine stashed and didn't put back. */
    routineStash: TrackedStash? = null,
    onRestoreStash: (TrackedStash) -> Unit = {},
    onDropStash: (TrackedStash) -> Unit = {},
    /** Offered when the last git write failed on index.lock: removes a stale one (asks first). */
    onRemoveLock: (() -> Unit)? = null,
) {
    val expanded = remember(dir) { mutableStateMapOf<String, String?>() } // key -> diff (null = loading)
    var fullScreen by remember { mutableStateOf<Pair<GitChange, String>?>(null) }
    var discarding by remember { mutableStateOf<GitChange?>(null) }
    var confirmLock by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    /** Each section shows its own part: staged rows what's in the index, the others what isn't yet. */
    fun sideOf(key: String) = when (key.substringBefore(':')) {
        "s" -> DiffSide.Staged
        "u" -> DiffSide.Unstaged
        else -> DiffSide.All
    }
    fun loadDiff(key: String, change: GitChange) = scope.launch {
        val diff = git.diff(dir, change, status?.unborn == true, sideOf(key))
        // Collapsed while this one ran: leave it.
        if (expanded.containsKey(key)) expanded[key] = diff
    }
    fun toggleDiff(key: String, change: GitChange) {
        if (expanded.containsKey(key)) expanded.remove(key)
        else {
            expanded[key] = null
            loadDiff(key, change)
        }
    }
    // The files changed (an edit, staging a hunk, an agent): open diffs are read again, and ones whose row is gone close.
    androidx.compose.runtime.LaunchedEffect(status) {
        val s = status ?: return@LaunchedEffect
        for (key in expanded.keys.toList()) {
            val path = key.substringAfter(':')
            val change = s.changes.firstOrNull { it.path == path }
            val stillThere = change != null && when (key.substringBefore(':')) {
                "c" -> change.label == "conflict"
                "s" -> change.isStaged
                else -> change.hasUnstaged
            }
            if (stillThere) loadDiff(key, change!!) else expanded.remove(key)
        }
    }
    fun showFull(key: String, change: GitChange) = scope.launch { fullScreen = change to git.diff(dir, change, status?.unborn == true, sideOf(key)) }

    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 2.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) {
            when {
                status == null -> items(5) { SkeletonListItem() }
                // Shown in the card above; never offer "Initialize git" for a repo git just couldn't read.
                status.error != null -> item { Text("Git couldn't read this folder; see the message above.", style = MaterialTheme.typography.bodyMedium) }
                !status.isRepo -> item { InitCard(busy, onInit) }
                else -> {
                    item(key = "sync") { SyncCard(status, busy, onPull, onPush, onPublish) }
                    operation?.let { op ->
                        item(key = "operation") { OperationBanner(op, status, busy, onContinueOperation, onAbortOperation) }
                    }
                    routineStash?.let { s ->
                        item(key = "stash") { StashBanner(s, busy, onRestore = { onRestoreStash(s) }, onDrop = { onDropStash(s) }) }
                    }
                    message?.let { (text, isError) ->
                        item(key = "message") { ResultBox(text, isError, onDismissMessage, action = onRemoveLock?.let { { confirmLock = true } }) }
                    }
                    if (!File(dir, ".gitignore").exists() && status.changes.any { looksGenerated(it.path) }) {
                        item(key = "gitignore") { GitignoreHint(busy, onAddGitignore) }
                    }
                    if (status.changes.isEmpty()) {
                        item(key = "clean") { CleanState() }
                    } else {
                        val conflicts = status.changes.filter { it.label == "conflict" }
                        val staged = status.changes.filter { it.isStaged }
                        val unstaged = status.changes.filter { it.hasUnstaged }
                        val rows: LazyListScope.(String, String, List<GitChange>, Boolean, String?, (() -> Unit)?) -> Unit =
                            { prefix, title, list, isStaged, actionLabel, action ->
                                if (list.isNotEmpty()) {
                                    item(key = "h-$prefix") { SectionHeader(title, list.size, actionLabel, enabled = busy == null, onAction = action) }
                                    items(list, key = { "$prefix:${it.path}" }) { change ->
                                        val key = "$prefix:${change.path}"
                                        val diff = expanded[key]
                                        // Staging hunk by hunk: only from a diff of exactly the side being changed, never while busy.
                                        val hunks = remember(diff) { diff?.takeIf { prefix != "c" }?.let(HunkPatch::parse) }
                                        ChangeRow(
                                            change = change,
                                            staged = isStaged,
                                            conflict = prefix == "c",
                                            expanded = expanded.containsKey(key),
                                            diff = diff,
                                            enabled = busy == null,
                                            hunkAction = if (hunks != null && busy == null) (if (isStaged) "Unstage hunk" else "Stage hunk") else null,
                                            onHunk = { index ->
                                                val patch = diff?.let { HunkPatch.forHunk(it, index) }
                                                if (patch != null) onGit(if (isStaged) "Unstage hunk" else "Stage hunk") { git.applyToIndex(dir, patch, reverse = isStaged) }
                                            },
                                            onToggleStage = {
                                                if (isStaged) onGit("Unstage") { git.unstage(dir, listOf(change.path), status.unborn) }
                                                else onGit("Stage") { git.stage(dir, listOf(change.path)) }
                                            },
                                            onToggleDiff = { toggleDiff(key, change) },
                                            onFullScreen = { showFull(key, change) },
                                            // A conflicted file opens in the merge view.
                                            onOpen = File(dir, change.path).takeIf { it.isFile }?.let { f -> { if (prefix == "c") onResolveConflict(f) else onOpenFile(f) } },
                                            onDiscard = if (prefix == "c" || change.label == "renamed") null else ({ discarding = change }),
                                            modifier = Modifier.animateItem(),
                                        )
                                    }
                                }
                            }
                        rows("c", "Conflicts", conflicts, false, "Mark all resolved", { onGit("Stage") { git.stage(dir, conflicts.map { it.path }) } })
                        rows("s", "Staged", staged, true, "Unstage all", { onGit("Unstage") { git.unstage(dir, staged.map { it.path }, status.unborn) } })
                        rows("u", "Changes", unstaged, false, "Stage all", { onGit("Stage") { git.stage(dir, unstaged.map { it.path }) } })
                    }
                }
            }
        }
        if (status?.isRepo == true && status.changes.isNotEmpty()) {
            val staged = status.changes.count { it.isStaged }
            CommitBar(commitMessage, onCommitMessageChange, staged, status.changes.size, busy, onCommit)
        }
    }

    fullScreen?.let { (change, diff) ->
        DiffFullScreen(change.path, changeLabel(change), diff, onDismiss = { fullScreen = null })
    }
    if (confirmLock && onRemoveLock != null) {
        AppSheet(
            "Remove the stale lock?", onDismiss = { confirmLock = false }, icon = Ph.Warning, subtitle = ".git/index.lock",
            actions = {
                OutlinedButton(onClick = { confirmLock = false }, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) { Text("Cancel") }
                Button(onClick = { confirmLock = false; onRemoveLock() }, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) { Text("Remove lock") }
            },
        ) {
            Text(
                "Git keeps this file while it changes the index, and leaves it behind when it's stopped half way (a timeout, the app closed). " +
                    "It's only removed if no git is running in this project right now; if one is, wait for it instead.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    discarding?.let { change ->
        AppSheet(
            "Discard changes?",
            onDismiss = { discarding = null },
            subtitle = change.path,
            icon = Ph.Trash,
            actions = {
                OutlinedButton(onClick = { discarding = null }, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) { Text("Cancel") }
                Button(
                    onClick = {
                        discarding = null
                        onGit("Discard") { git.discard(dir, change) }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
                    modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                ) { Text(if (change.untracked) "Delete file" else "Discard") }
            },
        ) {
            Text(
                when {
                    change.untracked -> "This file isn't in git yet, so it will be deleted. This can't be undone."
                    change.index == 'A' -> "This new file will be removed from git and deleted. This can't be undone."
                    else -> "Its staged and unstaged edits are thrown away and it goes back to the last commit. This can't be undone."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun changeLabel(c: GitChange) = when (c.label) {
    "new" -> "New file"
    "added" -> "Added"
    "deleted" -> "Deleted"
    "conflict" -> "Conflict"
    "renamed" -> "Renamed" + (c.origPath?.let { " from $it" } ?: "")
    else -> "Modified"
}

internal fun looksGenerated(path: String): Boolean =
    path.startsWith(".gradle/") || path.startsWith("build/") || path.contains("/build/") ||
        path.startsWith("node_modules/") || path.startsWith(".kotlin/") || path == "local.properties"

/** The one-letter status and its colour, shared by Changes and Files. */
@Composable
internal fun changeTone(c: GitChange): Pair<String, Color> {
    val tones = AppColors.status
    return when (c.label) {
        "new" -> "U" to tones.success
        "added" -> "A" to tones.success
        "deleted" -> "D" to MaterialTheme.colorScheme.error
        "conflict" -> "!" to MaterialTheme.colorScheme.error
        "renamed" -> "R" to MaterialTheme.colorScheme.primary
        else -> "M" to tones.warning
    }
}

@Composable
internal fun StatusLetter(change: GitChange, size: androidx.compose.ui.unit.Dp = 22.dp) {
    val (letter, color) = changeTone(change)
    val spoken = changeLabel(change)
    Box(
        Modifier.size(size).clip(RoundedCornerShape(7.dp)).background(color.copy(alpha = 0.16f))
            .clearAndSetSemantics { contentDescription = spoken },
        contentAlignment = Alignment.Center,
    ) { Text(letter, color = color, fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace) }
}

@Composable
private fun SyncCard(status: GitStatus, busy: String?, onPull: () -> Unit, onPush: () -> Unit, onPublish: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val lilac = AppColors.tiles.lilac
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(colors.surfaceContainer).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(lilac.fill), contentAlignment = Alignment.Center) {
                PhIcon(Ph.GitBranch, null, size = 18.dp, tint = lilac.on)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(status.branch ?: "detached HEAD", style = MaterialTheme.typography.titleSmall, fontFamily = FontFamily.Monospace,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    when {
                        status.unborn -> "No commits yet"
                        !status.hasOrigin -> "Only on this phone. Not on GitHub yet"
                        status.upstream == null -> "Not pushed yet"
                        status.ahead == 0 && status.behind == 0 -> "Up to date with ${status.upstream}"
                        else -> listOfNotNull(
                            status.ahead.takeIf { it > 0 }?.let { "$it to push" },
                            status.behind.takeIf { it > 0 }?.let { "$it to pull" },
                        ).joinToString(" · ") + " · ${status.upstream}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            if (busy != null) {
                WaveDots(dotSize = 5.dp)
                Spacer(Modifier.width(6.dp))
                Text(busy, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false))
            }
        }
        when {
            // No branch to push to or publish: commits made here only stay if a branch is made for them.
            status.detached -> Text(
                "Detached HEAD: you're on a commit, not a branch, so there's nothing to push. To keep commits made here, create a branch (Branches → New branch).",
                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
            )
            status.hasOrigin -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SyncButton("Pull", Ph.CloudArrowDown, status.behind, highlight = status.behind > 0,
                    enabled = busy == null && status.upstream != null, modifier = Modifier.weight(1f), onClick = onPull)
                SyncButton("Push", Ph.CloudArrowUp, status.ahead, highlight = status.ahead > 0 || status.upstream == null,
                    enabled = busy == null && !status.unborn, modifier = Modifier.weight(1f), onClick = onPush)
            }
            !status.unborn -> Button(onClick = onPublish, enabled = busy == null, modifier = Modifier.fillMaxWidth()) {
                PhIcon(Ph.GithubLogo, null, size = 16.dp)
                Spacer(Modifier.width(8.dp))
                Text("Publish to GitHub")
            }
        }
    }
}

@Composable
private fun SyncButton(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, count: Int, highlight: Boolean,
    enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val content: @Composable () -> Unit = {
        PhIcon(icon, null, size = 16.dp)
        Spacer(Modifier.width(6.dp))
        Text(label)
        if (count > 0) {
            Spacer(Modifier.width(6.dp))
            Text(if (count > 99) "99+" else "$count", fontWeight = FontWeight.Bold)
        }
    }
    if (highlight) Button(onClick = onClick, enabled = enabled, modifier = modifier) { content() }
    else FilledTonalButton(onClick = onClick, enabled = enabled, modifier = modifier) { content() }
}

@Composable
private fun SectionHeader(title: String, count: Int, actionLabel: String?, enabled: Boolean, onAction: (() -> Unit)?) {
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title.uppercase(), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant, letterSpacing = 0.8.sp)
        Spacer(Modifier.width(8.dp))
        Text("$count", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
        Spacer(Modifier.weight(1f))
        if (actionLabel != null && onAction != null) TextButton(onClick = onAction, enabled = enabled) { Text(actionLabel) }
    }
}

@Composable
private fun ChangeRow(
    change: GitChange,
    staged: Boolean,
    conflict: Boolean,
    expanded: Boolean,
    diff: String?,
    enabled: Boolean,
    /** "Stage hunk" / "Unstage hunk" on each hunk of the diff, or null when it can't be done hunk by hunk. */
    hunkAction: String? = null,
    onHunk: (Int) -> Unit = {},
    onToggleStage: () -> Unit,
    onToggleDiff: () -> Unit,
    onFullScreen: () -> Unit,
    onOpen: (() -> Unit)?,
    onDiscard: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val tones = AppColors.status
    val name = change.path.substringAfterLast('/')
    val folder = change.path.substringBeforeLast('/', "")
    var menu by remember { mutableStateOf(false) }
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(if (expanded) colors.surfaceContainerHigh else colors.surfaceContainer).animateContentSize(),
    ) {
        Row(Modifier.fillMaxWidth().clickable(onClick = onToggleDiff).padding(start = 2.dp, end = 2.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically) {
            // A checkbox: ticked = in the next commit.
            IconButton(onClick = onToggleStage, enabled = enabled) {
                PhIcon(
                    when { conflict -> Ph.Warning; staged -> Ph.CheckCircle; else -> Ph.Circle },
                    when { conflict -> "Mark resolved"; staged -> "Unstage"; else -> "Stage" },
                    size = 22.dp,
                    tint = when { conflict -> colors.error; staged -> tones.success; else -> colors.outline },
                )
            }
            StatusLetter(change)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                Text(name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, fontFamily = FontFamily.Monospace,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    textDecoration = if (change.label == "deleted") androidx.compose.ui.text.style.TextDecoration.LineThrough else null)
                val sub = listOfNotNull(folder.ifEmpty { null }, change.origPath?.let { "from $it" }).joinToString(" · ")
                if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            PhIcon(if (expanded) Ph.CaretUp else Ph.CaretDown, null, size = 14.dp, tint = colors.onSurfaceVariant)
            Box {
                IconButton(onClick = { menu = true }) { PhIcon(Ph.DotsThreeVertical, "More", size = 18.dp, tint = colors.onSurfaceVariant) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("View diff full screen") }, leadingIcon = { PhIcon(Ph.ArrowsLeftRight, null, size = 18.dp) },
                        onClick = { menu = false; onFullScreen() })
                    onOpen?.let { open ->
                        DropdownMenuItem(text = { Text("Open file") }, leadingIcon = { PhIcon(Ph.FileText, null, size = 18.dp) },
                            onClick = { menu = false; open() })
                    }
                    onDiscard?.let { discard ->
                        DropdownMenuItem(
                            text = { Text(if (change.untracked) "Delete file" else "Discard changes", color = colors.error) },
                            leadingIcon = { PhIcon(Ph.Trash, null, size = 18.dp, tint = colors.error) },
                            enabled = enabled,
                            onClick = { menu = false; discard() },
                        )
                    }
                }
            }
        }
        if (expanded) {
            Column(Modifier.padding(start = 8.dp, end = 8.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (diff == null) SkeletonCode(lines = 6) else DiffViewer(diff, hunkAction = hunkAction, onHunk = onHunk)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = onFullScreen) { Text("Full screen") }
                    onOpen?.let { TextButton(onClick = it) { Text("Open file") } }
                }
            }
        }
    }
}

/** The commit box, pinned under the list. */
@Composable
private fun CommitBar(
    message: String,
    onMessageChange: (String) -> Unit,
    staged: Int,
    total: Int,
    busy: String?,
    onCommit: (String, Boolean) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp).clip(RoundedCornerShape(22.dp)).background(colors.surfaceContainer)
            .padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                message, onMessageChange,
                placeholder = { Text("Commit message") },
                shape = RoundedCornerShape(16.dp),
                maxLines = 4,
                modifier = Modifier.weight(1f),
            )
            Button(
                onClick = { onCommit(message.trim(), staged > 0) },
                enabled = busy == null && message.isNotBlank(),
                shape = RoundedCornerShape(16.dp),
                contentPadding = PaddingValues(horizontal = 14.dp),
                modifier = Modifier.height(54.dp),
            ) {
                PhIcon(Ph.GitCommit, null, size = 18.dp)
                Spacer(Modifier.width(6.dp))
                Text("Commit")
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = 36.dp).padding(start = 6.dp)) {
            Text(
                if (staged > 0) "Commits the $staged staged file${if (staged == 1) "" else "s"}; the rest stay uncommitted."
                else "Stages and commits all $total change${if (total == 1) "" else "s"}.",
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (staged in 1 until total) {
                TextButton(onClick = { onCommit(message.trim(), false) }, enabled = busy == null && message.isNotBlank()) { Text("Commit all") }
            }
        }
    }
}

@Composable
private fun CleanState() {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(64.dp).clip(RoundedCornerShape(22.dp)).background(AppColors.status.successContainer), contentAlignment = Alignment.Center) {
            PhIcon(Ph.CheckCircle, null, size = 30.dp, tint = AppColors.status.onSuccessContainer)
        }
        Text("Working tree clean", style = MaterialTheme.typography.titleMedium)
        Text("Nothing to commit. Edits made here or by an agent show up in this tab.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

@Composable
private fun InitCard(busy: String?, onInit: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val lilac = AppColors.tiles.lilac
        Box(Modifier.size(64.dp).clip(RoundedCornerShape(22.dp)).background(lilac.fill), contentAlignment = Alignment.Center) {
            PhIcon(Ph.GitBranch, null, size = 28.dp, tint = lilac.on)
        }
        Text("Not a git repository", style = MaterialTheme.typography.titleMedium)
        Text("Start tracking this folder to commit, see history and publish it.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        Button(onClick = onInit, enabled = busy == null) { Text("Initialize git") }
    }
}

@Composable
private fun GitignoreHint(busy: String?, onAdd: () -> Unit) {
    val tones = AppColors.status
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(tones.warningContainer).padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PhIcon(Ph.Warning, null, size = 16.dp, tint = tones.onWarningContainer)
        Spacer(Modifier.width(8.dp))
        Text("No .gitignore: build output and caches would be committed.", style = MaterialTheme.typography.bodySmall,
            color = tones.onWarningContainer, modifier = Modifier.weight(1f))
        TextButton(onClick = onAdd, enabled = busy == null) { Text("Add one") }
    }
}

@Composable
private fun ResultBox(text: String, isError: Boolean, onDismiss: () -> Unit, action: (() -> Unit)? = null) {
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(if (isError) colors.errorContainer else colors.surfaceContainerHighest),
    ) {
        Row(Modifier.padding(start = 12.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.Top) {
            Text(
                text,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                lineHeight = 15.sp,
                color = if (isError) colors.onErrorContainer else colors.onSurfaceVariant,
                modifier = Modifier.weight(1f).padding(vertical = 8.dp),
            )
            IconButton(onClick = onDismiss) { PhIcon(Ph.XCircle, "Dismiss", size = 18.dp, tint = colors.onSurfaceVariant) }
        }
        // A killed git's index.lock: the fix is one tap (asked first), not a terminal command.
        if (action != null) TextButton(onClick = action, modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)) { Text("Remove stale lock…") }
    }
}
