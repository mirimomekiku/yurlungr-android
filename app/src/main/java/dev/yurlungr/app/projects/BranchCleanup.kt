package dev.yurlungr.app.projects

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.yurlungr.app.ui.AppColors
import dev.yurlungr.app.ui.AppSheet
import dev.yurlungr.app.ui.FlowBar
import dev.yurlungr.app.ui.Ph
import dev.yurlungr.app.ui.PhIcon
import java.io.File

/** What deleting some branches did: [deleted] went, [unmerged] git refused (`-d`: they'd lose commits), [failed] for other reasons. */
data class CleanupResult(val deleted: List<String>, val unmerged: List<String>, val failed: Map<String, String>)

/** Whether `git branch -d` refused because the branch has commits nothing else has. */
internal fun refusedUnmerged(output: String): Boolean = output.contains("not fully merged", ignoreCase = true)

/**
 * Deletes branches that are merged into this one, or whose remote branch is gone (a merged pull
 * request), with `git branch -d`, which refuses to lose commits. Those it refuses (a squash-merged
 * pull request looks unmerged) are listed, and only a second, explicit confirmation deletes them
 * with `-D`.
 */
@Composable
internal fun BranchCleanupSheet(dir: File, git: Git, onDismiss: () -> Unit, onChanged: () -> Unit) {
    var candidates by remember { mutableStateOf<Result<List<CleanupBranch>>?>(null) }
    val picked = remember { mutableStateListOf<String>() }
    var running by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<CleanupResult?>(null) }
    var confirmForce by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val found = git.cleanupCandidates(dir)
        candidates = found
        picked.clear()
        picked += found.getOrNull().orEmpty().map { it.name }
    }

    fun delete(names: List<String>, force: Boolean) {
        if (running || names.isEmpty()) return
        running = true
        GitJobs.launch {
            val deleted = ArrayList<String>()
            val unmerged = ArrayList<String>()
            val failed = LinkedHashMap<String, String>()
            for (name in names) {
                val r = git.deleteBranch(dir, name, force)
                val text = r.errorOutput + "\n" + r.output
                when {
                    r.ok -> deleted += name
                    !force && refusedUnmerged(text) -> unmerged += name
                    else -> failed[name] = r.describe("git branch ${if (force) "-D" else "-d"}").lines().last()
                }
            }
            result = CleanupResult(deleted, unmerged, failed)
            running = false
            if (deleted.isNotEmpty()) onChanged()
            candidates = git.cleanupCandidates(dir)
            picked.removeAll(deleted)
        }
    }

    val list = candidates?.getOrNull().orEmpty()
    AppSheet(
        "Clean up branches", onDismiss = onDismiss, icon = Ph.GitBranch, tile = AppColors.tiles.lilac, dismissable = !running,
        subtitle = "Local branches merged into this one, or whose branch on the remote is gone",
        actions = {
            OutlinedButton(onClick = onDismiss, enabled = !running, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) { Text("Close") }
            Button(
                onClick = { delete(picked.toList(), force = false) },
                enabled = !running && picked.isNotEmpty(),
                modifier = Modifier.weight(1f).heightIn(min = 52.dp),
            ) { Text(if (picked.isEmpty()) "Delete" else "Delete ${picked.size}") }
        },
    ) {
        val c = candidates
        when {
            c == null || running -> FlowBar(Modifier.fillMaxWidth())
            c.isFailure -> Text(c.exceptionOrNull()?.message ?: "Couldn't list the branches.", color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall)
            list.isEmpty() -> Text("Nothing to clean up: no other branch is merged into this one or gone from the remote.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        result?.let { r ->
            val lines = listOfNotNull(
                r.deleted.takeIf { it.isNotEmpty() }?.let { "Deleted: ${it.joinToString()}" },
                r.unmerged.takeIf { it.isNotEmpty() }?.let { "Not deleted, they have commits no other branch has: ${it.joinToString()}" },
                r.failed.takeIf { it.isNotEmpty() }?.let { m -> m.entries.joinToString("\n") { "${it.key}: ${it.value}" } },
            )
            Text(lines.joinToString("\n"), style = MaterialTheme.typography.bodySmall)
            if (r.unmerged.isNotEmpty()) {
                OutlinedButton(onClick = { confirmForce = true }, enabled = !running) { Text("Delete those anyway…") }
            }
        }
        if (!running) for (b in list) {
            val on = b.name in picked
            Row(
                Modifier.fillMaxWidth().clickable { if (on) picked.remove(b.name) else picked.add(b.name) }.padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PhIcon(if (on) Ph.CheckCircle else Ph.Circle, if (on) "Selected" else "Not selected", size = 20.dp,
                    tint = if (on) AppColors.status.success else MaterialTheme.colorScheme.outline)
                Spacer(Modifier.width(12.dp))
                androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
                    Text(b.name, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        listOfNotNull("merged into this branch".takeIf { b.merged }, "gone from the remote".takeIf { b.gone }).joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    val unmerged = result?.unmerged.orEmpty()
    if (confirmForce && unmerged.isNotEmpty()) {
        AppSheet(
            "Delete unmerged branches?", onDismiss = { confirmForce = false }, icon = Ph.Warning, tile = AppColors.tiles.peach,
            subtitle = unmerged.joinToString(),
            actions = {
                OutlinedButton(onClick = { confirmForce = false }, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) { Text("Keep them") }
                Button(
                    onClick = { confirmForce = false; delete(unmerged, force = true) },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
                    modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                ) { Text("Delete (-D)") }
            },
        ) {
            Text(
                "Git refused these: they have commits that no other branch here has. If their pull requests were squash-merged, those commits are on the remote " +
                    "in another form and deleting is fine. Otherwise the commits are lost (only git's reflog keeps them for a while).",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
