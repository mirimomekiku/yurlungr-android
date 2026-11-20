package dev.yurlungr.app.projects

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import dev.yurlungr.app.ui.AppColors
import dev.yurlungr.app.ui.AppSheet
import dev.yurlungr.app.ui.Ph
import dev.yurlungr.app.ui.PhIcon

/** What [op] is waiting for, for the banner; [conflicts] unresolved files, [changes] uncommitted ones. */
internal fun operationText(op: GitOperation, conflicts: Int, changes: Int): String = when {
    conflicts > 0 -> "A ${op.label} stopped on conflicts in $conflicts file${if (conflicts == 1) "" else "s"}. " +
        "Resolve them (open each one), then ${if (op == GitOperation.Merge) "conclude the merge" else "continue"}."
    op == GitOperation.Merge && changes == 0 ->
        "A merge is half done and changes nothing here (both sides already match). Conclude it to record the merge, or abort it."
    op == GitOperation.Merge -> "A merge is waiting to be concluded: the conflicts are resolved. Conclude it to commit the merge."
    else -> "A ${op.label} is waiting: the conflicts are resolved. Continue to carry on with it."
}

/**
 * An unfinished merge, rebase, cherry-pick or revert: what it's waiting for, Continue (or
 * Conclude, for a merge) and Abort. Continue waits until no file is still conflicted; Abort asks first.
 */
@Composable
internal fun OperationBanner(
    op: GitOperation,
    status: GitStatus,
    busy: String?,
    onContinue: (GitOperation) -> Unit,
    onAbort: (GitOperation) -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirmAbort by remember { mutableStateOf(false) }
    val tones = AppColors.status
    val conflicts = status.conflicts.size
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(tones.warningContainer).padding(start = 12.dp, end = 8.dp, top = 12.dp, bottom = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PhIcon(Ph.GitMerge, null, size = 18.dp, tint = tones.onWarningContainer)
            Spacer(Modifier.width(8.dp))
            Text(
                op.label.replaceFirstChar { it.uppercase() } + " in progress",
                style = MaterialTheme.typography.titleSmall, color = tones.onWarningContainer, modifier = Modifier.weight(1f),
            )
        }
        Text(operationText(op, conflicts, status.changes.size), style = MaterialTheme.typography.bodySmall, color = tones.onWarningContainer)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { confirmAbort = true }, enabled = busy == null) { Text("Abort…") }
            Spacer(Modifier.width(4.dp))
            Button(onClick = { onContinue(op) }, enabled = busy == null && conflicts == 0) {
                Text(if (op == GitOperation.Merge) "Conclude merge" else "Continue")
            }
        }
    }
    if (confirmAbort) {
        AppSheet(
            "Abort the ${op.label}?", onDismiss = { confirmAbort = false }, icon = Ph.Warning, tile = AppColors.tiles.peach,
            subtitle = op.abortCommand,
            actions = {
                OutlinedButton(onClick = { confirmAbort = false }, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) { Text("Keep going") }
                Button(
                    onClick = { confirmAbort = false; onAbort(op) },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
                    modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                ) { Text("Abort") }
            },
        ) {
            Text(
                "The branch goes back to where it was before the ${op.label} started, and the conflict resolutions made so far are thrown away." +
                    if (op == GitOperation.Merge) " Uncommitted changes you had before the merge are kept when git can." else "",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The user's changes a routine stashed (Get latest, a branch switch) and didn't put back:
 * Restore pops exactly that entry. When it was already re-applied with conflicts, the entry is
 * only a backup, and Drop (asked first) removes it.
 */
@Composable
internal fun StashBanner(stash: TrackedStash, busy: String?, onRestore: () -> Unit, onDrop: () -> Unit, modifier: Modifier = Modifier) {
    var confirmDrop by remember { mutableStateOf(false) }
    val sky = AppColors.tiles.sky
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(sky.fill.copy(alpha = 0.35f))
            .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PhIcon(Ph.ClockCounterClockwise, null, size = 16.dp, tint = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.width(8.dp))
        Text(
            if (stash.applied) "Your stashed changes were put back with conflicts. The stash is kept as a backup until you drop it."
            else "Your changes are still stashed (${stash.reason.removePrefix("Yurlungr: ")}).",
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f).padding(vertical = 6.dp),
        )
        if (stash.applied) TextButton(onClick = { confirmDrop = true }, enabled = busy == null) { Text("Drop…") }
        else TextButton(onClick = onRestore, enabled = busy == null) { Text("Restore") }
    }
    if (confirmDrop) {
        AppSheet(
            "Drop the backup stash?", onDismiss = { confirmDrop = false }, icon = Ph.Trash, subtitle = stash.sha.take(12),
            actions = {
                OutlinedButton(onClick = { confirmDrop = false }, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) { Text("Cancel") }
                Button(
                    onClick = { confirmDrop = false; onDrop() },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
                    modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                ) { Text("Drop") }
            },
        ) {
            Text(
                "Do this once the conflicts are resolved and your changes look right: the stash entry is deleted, and this can't be undone.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
