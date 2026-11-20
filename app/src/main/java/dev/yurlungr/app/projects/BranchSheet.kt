package dev.yurlungr.app.projects

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.yurlungr.app.runtime.CommandResult
import dev.yurlungr.app.ui.AppColors
import dev.yurlungr.app.ui.Chip
import dev.yurlungr.app.ui.Keyboards
import dev.yurlungr.app.ui.Ph
import dev.yurlungr.app.ui.PhIcon
import dev.yurlungr.app.ui.SectionLabel
import dev.yurlungr.app.ui.SkeletonListItem
import dev.yurlungr.app.ui.WaveDots
import kotlinx.coroutines.launch
import java.io.File

private val BRANCH_NAME = Regex("[A-Za-z0-9._/-]{1,100}")

/**
 * Branch sheet (opened from the branch chip): pull, create, rename, switch (offering to stash
 * when the working tree has changes), clean up merged or gone branches, and stash / apply /
 * pop / drop. Stash entries are acted on by their commit, not their position.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BranchSheet(dir: File, git: Git, status: GitStatus, onDismiss: () -> Unit, onChanged: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var branches by remember { mutableStateOf<List<GitBranch>?>(null) }
    var stashes by remember { mutableStateOf<List<GitStash>>(emptyList()) }
    var busy by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<Pair<String, Boolean>?>(null) } // text, isError
    var reload by remember { mutableIntStateOf(0) }
    var creating by remember { mutableStateOf(false) }
    var stashing by remember { mutableStateOf(false) }
    var confirmSwitch by remember { mutableStateOf<GitBranch?>(null) }
    var dropping by remember { mutableStateOf<GitStash?>(null) }
    var renaming by remember { mutableStateOf<GitBranch?>(null) }
    var cleaningUp by remember { mutableStateOf(false) }
    val dirty = status.changes.isNotEmpty()
    // Ops run in GitJobs, so closing the sheet (or the project) can't stop a stash-and-switch half way.
    var open by remember { mutableStateOf(true) }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { open = false } }

    LaunchedEffect(reload) {
        branches = git.branches(dir).getOrElse { result = (it.message ?: "Couldn't list branches") to true; emptyList() }
        stashes = git.stashes(dir).getOrElse { emptyList() }
    }
    // Pick up branches others pushed, once per opening; the list refreshes when it lands.
    LaunchedEffect(Unit) {
        if (status.hasOrigin && git.fetch(dir).ok) reload++
    }

    fun op(label: String, then: suspend () -> Unit = {}, block: suspend () -> CommandResult) {
        if (busy != null) return
        busy = label
        result = null
        GitJobs.launch {
            val r = block()
            if (r.ok) then()
            result = (if (r.ok) r.output.trim().lines().takeLast(4).joinToString("\n").ifBlank { "$label: done" } else r.describe(label)) to !r.ok
            busy = null
            reload++
            if (open) onChanged()
        }
    }

    fun switch(branch: GitBranch, stashFirst: Boolean) = op("Switch") {
        if (!stashFirst) return@op git.switchTo(dir, branch)
        val reason = "Before switching to ${branch.name}"
        val (s, stashed) = git.stashSaving(dir, reason)
        if (!s.ok) return@op s
        // Remembered by its commit: the Changes tab offers it back until it's restored.
        val sha = if (stashed) git.stashTop(dir) else null
        sha?.let { RoutineStashes.set(context, dir, TrackedStash(it, reason)) }
        val switched = git.switchTo(dir, branch)
        // Nothing stashed (e.g. only ignored files changed), or the switch worked: done.
        if (switched.ok || sha == null) {
            return@op if (switched.ok && sha != null) switched.copy(output = switched.output.trim() + "\nYour changes are stashed: Restore them from the Changes tab when you want them here.")
            else switched
        }
        // The switch failed: bring the changes back rather than leaving them hidden in a stash.
        val restored = git.stashPopSha(dir, sha)
        if (restored.ok) RoutineStashes.clear(context, dir)
        val note = if (restored.ok) {
            "Your changes were restored."
        } else {
            "Your changes are still stashed (Stashes below, or Restore in the Changes tab); restoring them failed:\n${restored.describe("git stash pop").lines().takeLast(2).joinToString("\n")}"
        }
        CommandResult(switched.exitCode, switched.output.trim() + "\n" + note, switched.timedOut, errorOutput = switched.errorOutput)
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp).animateContentSize()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PhIcon(Ph.GitBranch, null, size = 20.dp, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(status.branch ?: "detached", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Text(
                        when {
                            status.unborn -> "No commits yet"
                            status.upstream == null -> "Not tracking a remote branch"
                            status.ahead == 0 && status.behind == 0 -> "Up to date with ${status.upstream}"
                            else -> "${status.ahead} to push · ${status.behind} to pull · ${status.upstream}"
                        } + if (dirty) " · ${status.changes.size} uncommitted" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (busy != null) WaveDots(dotSize = 4.dp)
            }

            Row(
                Modifier.padding(vertical = 12.dp).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilledTonalButton(onClick = { op("Pull") { git.pull(dir) } }, enabled = busy == null && status.upstream != null) {
                    PhIcon(Ph.DownloadSimple, null, size = 16.dp)
                    Spacer(Modifier.width(6.dp))
                    Text("Pull")
                }
                FilledTonalButton(onClick = { creating = true }, enabled = busy == null && !status.unborn) {
                    PhIcon(Ph.Plus, null, size = 16.dp)
                    Spacer(Modifier.width(6.dp))
                    Text("New branch")
                }
                FilledTonalButton(onClick = { stashing = true }, enabled = busy == null && dirty && !status.unborn) {
                    PhIcon(Ph.ClockCounterClockwise, null, size = 16.dp)
                    Spacer(Modifier.width(6.dp))
                    Text("Stash changes")
                }
                FilledTonalButton(onClick = { cleaningUp = true }, enabled = busy == null && !status.unborn) {
                    PhIcon(Ph.Trash, null, size = 16.dp)
                    Spacer(Modifier.width(6.dp))
                    Text("Clean up")
                }
            }

            result?.let { (text, isError) ->
                Text(
                    text,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    color = if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerHigh)
                        .padding(10.dp),
                )
            }

            LazyColumn(Modifier.heightIn(max = 460.dp)) {
                item { SectionLabel("Switch branch") }
                val list = branches
                if (list == null) items(3) { SkeletonListItem() }
                else items(list, key = { (if (it.isRemote) "r:" else "l:") + it.name }) { b ->
                    BranchRow(
                        b, enabled = busy == null && !b.isCurrent,
                        onRename = if (b.isRemote || busy != null) null else ({ renaming = b }),
                    ) {
                        if (dirty) confirmSwitch = b else switch(b, stashFirst = false)
                    }
                }
                if (stashes.isNotEmpty()) {
                    item { SectionLabel("Stashes · ${stashes.size}", Modifier.padding(top = 8.dp)) }
                    items(stashes, key = { it.sha.ifEmpty { it.ref } }) { s ->
                        StashRow(
                            s,
                            enabled = busy == null,
                            onPop = { op("Pop") { git.stashPop(dir, s).also { if (it.ok) forgetTracked(context, dir, s) } } },
                            onApply = { op("Apply") { git.stashApply(dir, s) } },
                            onDrop = { dropping = s },
                        )
                    }
                }
                item { Spacer(Modifier.height(16.dp)) }
            }
        }
    }

    if (creating) {
        NameDialog(
            title = "New branch",
            label = "Branch name",
            supporting = "Starts from ${status.branch ?: "HEAD"}; your uncommitted changes come along.",
            validate = { BRANCH_NAME.matches(it) && !it.startsWith("-") && ".." !in it },
            confirm = "Create & switch",
            onDismiss = { creating = false },
        ) { name ->
            creating = false
            op("New branch") { git.createBranch(dir, name) }
        }
    }
    if (stashing) {
        NameDialog(
            title = "Stash changes",
            label = "Message (optional)",
            supporting = "Saves all ${status.changes.size} changes, new files included, and cleans the working tree.",
            validate = { true },
            confirm = "Stash",
            onDismiss = { stashing = false },
        ) { message ->
            stashing = false
            op("Stash") { git.stash(dir, message) }
        }
    }
    dropping?.let { s ->
        dev.yurlungr.app.ui.AppSheet(
            "Drop this stash?", onDismiss = { dropping = null }, icon = Ph.Trash, subtitle = s.message,
            actions = {
                androidx.compose.material3.OutlinedButton(onClick = { dropping = null }, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) { Text("Cancel") }
                Button(
                    onClick = {
                        dropping = null
                        op("Drop") { git.stashDrop(dir, s).also { if (it.ok) forgetTracked(context, dir, s) } }
                    },
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError,
                    ),
                    modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                ) { Text("Drop") }
            },
        ) {
            Text(
                "The saved changes in it are deleted (${s.whenRelative}). This can't be undone from here.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    renaming?.let { b ->
        NameDialog(
            title = "Rename ${b.name}",
            label = "New name",
            supporting = "Only the local branch is renamed; a remote one keeps its name until you push.",
            validate = { it != b.name && BRANCH_NAME.matches(it) && !it.startsWith("-") && ".." !in it },
            confirm = "Rename",
            initial = b.name,
            onDismiss = { renaming = null },
        ) { name ->
            renaming = null
            op("Rename") { git.renameBranch(dir, b.name, name) }
        }
    }
    if (cleaningUp) {
        BranchCleanupSheet(dir, git, onDismiss = { cleaningUp = false }, onChanged = { reload++; if (open) onChanged() })
    }
    confirmSwitch?.let { b ->
        AlertDialog(
            onDismissRequest = { confirmSwitch = null },
            title = { Text("Switch to ${b.name.substringAfter('/').takeIf { b.isRemote } ?: b.name}?") },
            text = { Text("You have ${status.changes.size} uncommitted changes. Stash them first, or bring them to the other branch (git refuses if they conflict).") },
            confirmButton = {
                Button(onClick = {
                    confirmSwitch = null
                    switch(b, stashFirst = true)
                }) { Text("Stash & switch") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { confirmSwitch = null }) { Text("Cancel") }
                    TextButton(onClick = {
                        confirmSwitch = null
                        switch(b, stashFirst = false)
                    }) { Text("Bring along") }
                }
            },
        )
    }
}

/** The tracked routine stash is gone once its entry is popped or dropped here. */
private fun forgetTracked(context: android.content.Context, dir: File, s: GitStash) {
    if (s.sha.isNotEmpty() && RoutineStashes.get(context, dir)?.sha == s.sha) RoutineStashes.clear(context, dir)
}

@Composable
private fun BranchRow(b: GitBranch, enabled: Boolean, onRename: (() -> Unit)? = null, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (b.isCurrent) colors.primaryContainer.copy(alpha = 0.5f) else androidx.compose.ui.graphics.Color.Transparent)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PhIcon(
            if (b.isCurrent) Ph.CheckCircle else if (b.isRemote) Ph.Globe else Ph.GitBranch,
            null,
            size = 18.dp,
            tint = if (b.isCurrent) AppColors.status.success else colors.onSurfaceVariant,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(b.name, fontFamily = FontFamily.Monospace, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(
                    if (b.isRemote) "remote · creates a local branch" else b.upstream?.let { "tracks $it" },
                    b.updated.ifBlank { null },
                ).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant,
                maxLines = 1,
            )
        }
        if (b.isCurrent) Chip("Current", onClick = {}, selected = true)
        if (onRename != null) {
            var menu by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { menu = true }) { PhIcon(Ph.DotsThreeVertical, "More", size = 18.dp) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Rename…") }, leadingIcon = { PhIcon(Ph.PencilSimple, null, size = 18.dp) },
                        onClick = { menu = false; onRename() })
                }
            }
        }
    }
}

@Composable
private fun StashRow(s: GitStash, enabled: Boolean, onPop: () -> Unit, onApply: () -> Unit, onDrop: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(start = 12.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        PhIcon(Ph.ClockCounterClockwise, null, size = 18.dp, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(s.message, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("${s.ref} · ${s.whenRelative}", style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TextButton(onClick = onPop, enabled = enabled) { Text("Pop") }
        Box {
            IconButton(onClick = { menu = true }, enabled = enabled) { PhIcon(Ph.DotsThreeVertical, "More", size = 18.dp) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Apply (keep stash)") }, onClick = { menu = false; onApply() })
                DropdownMenuItem(text = { Text("Drop", color = MaterialTheme.colorScheme.error) }, onClick = { menu = false; onDrop() })
            }
        }
    }
}

@Composable
private fun NameDialog(
    title: String,
    label: String,
    supporting: String,
    validate: (String) -> Boolean,
    confirm: String,
    initial: String = "",
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                text,
                { text = it },
                label = { Text(label) },
                supportingText = { Text(supporting) },
                singleLine = true,
                keyboardOptions = Keyboards.Identifier,
            )
        },
        confirmButton = { Button(onClick = { onConfirm(text.trim()) }, enabled = validate(text.trim())) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
