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
 * Branch sheet (opened from the branch chip): pull, create, switch (offering to stash when the
 * working tree has changes), and stash / apply / pop / drop.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BranchSheet(dir: File, git: Git, status: GitStatus, onDismiss: () -> Unit, onChanged: () -> Unit) {
    val scope = rememberCoroutineScope()
    var branches by remember { mutableStateOf<List<GitBranch>?>(null) }
    var stashes by remember { mutableStateOf<List<GitStash>>(emptyList()) }
    var busy by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<Pair<String, Boolean>?>(null) } // text, isError
    var reload by remember { mutableIntStateOf(0) }
    var creating by remember { mutableStateOf(false) }
    var stashing by remember { mutableStateOf(false) }
    var confirmSwitch by remember { mutableStateOf<GitBranch?>(null) }
    val dirty = status.changes.isNotEmpty()

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
        scope.launch {
            val r = block()
            if (r.ok) then()
            result = (if (r.ok) r.output.trim().lines().takeLast(4).joinToString("\n").ifBlank { "$label: done" } else r.describe(label)) to !r.ok
            busy = null
            reload++
            onChanged()
        }
    }

    fun switch(branch: GitBranch, stashFirst: Boolean) = op("Switch") {
        if (!stashFirst) return@op git.switchTo(dir, branch)
        val (s, stashed) = git.stashSaving(dir, "Before switching to ${branch.name}")
        if (!s.ok) return@op s
        val switched = git.switchTo(dir, branch)
        // Nothing stashed (e.g. only ignored files changed), or the switch worked: done.
        if (switched.ok || !stashed) return@op switched
        // The switch failed: bring the changes back rather than leaving them hidden in a stash.
        val restored = git.stashPop(dir, GitStash("stash@{0}", "", ""))
        val note = if (restored.ok) {
            "Your changes were restored."
        } else {
            "Your changes are saved in stash@{0} (Stashes below); restoring them failed:\n${restored.output.trim().lines().takeLast(2).joinToString("\n")}"
        }
        CommandResult(switched.exitCode, switched.output.trim() + "\n" + note, switched.timedOut)
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
                    BranchRow(b, enabled = busy == null && !b.isCurrent) {
                        if (dirty) confirmSwitch = b else switch(b, stashFirst = false)
                    }
                }
                if (stashes.isNotEmpty()) {
                    item { SectionLabel("Stashes · ${stashes.size}", Modifier.padding(top = 8.dp)) }
                    items(stashes, key = { it.ref }) { s ->
                        StashRow(
                            s,
                            enabled = busy == null,
                            onPop = { op("Pop") { git.stashPop(dir, s) } },
                            onApply = { op("Apply") { git.stashApply(dir, s) } },
                            onDrop = { op("Drop") { git.stashDrop(dir, s) } },
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

@Composable
private fun BranchRow(b: GitBranch, enabled: Boolean, onClick: () -> Unit) {
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
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf("") }
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
