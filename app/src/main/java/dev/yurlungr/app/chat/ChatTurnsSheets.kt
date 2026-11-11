package dev.yurlungr.app.chat

import android.text.format.DateUtils
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.yurlungr.app.projects.DiffFullScreen
import dev.yurlungr.app.ui.AppColors
import dev.yurlungr.app.ui.AppSheet
import dev.yurlungr.app.ui.Ph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun duration(ms: Long): String = when {
    ms < 60_000 -> "${ms / 1000} s"
    ms < 3_600_000 -> "${ms / 60_000} min ${ms % 60_000 / 1000} s"
    else -> "${ms / 3_600_000} h ${ms % 3_600_000 / 60_000} min"
}

/**
 * Each prompt in this chat with what it changed in the project: open its diff, or put the files
 * back as they were before it (that's a checkpoint too, so a restore can be undone).
 */
@Composable
fun CheckpointsSheet(chat: ChatSession, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var reload by remember { mutableIntStateOf(0) }
    val turns by produceState(emptyList<ChatTurn>(), reload) { value = withContext(Dispatchers.IO) { Checkpoints.turns(context, chat.id) }.asReversed() }
    var diff by remember { mutableStateOf<Pair<ChatTurn, String>?>(null) }
    var restoring by remember { mutableStateOf<ChatTurn?>(null) }
    val colors = MaterialTheme.colorScheme
    diff?.let { (turn, text) -> DiffFullScreen("Turn ${turn.index}", turn.prompt, text) { diff = null } }

    AppSheet(
        "Checkpoints", onDismiss = onDismiss, icon = Ph.ClockCounterClockwise, tile = AppColors.tiles.lilac,
        subtitle = "The project before and after each prompt. Kept outside the project's own git.",
    ) {
        if (turns.isEmpty()) Text(
            if (Checkpoints.enabledFor(dev.yurlungr.app.runtime.RuntimePaths(context), chat.cwd)) "No turns yet: each prompt adds one."
            else "Checkpoints are kept for chats in a project folder, not the home folder.",
            color = colors.onSurfaceVariant,
        )
        for (t in turns) {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(colors.surfaceContainerHigh).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("${t.index}. ${t.prompt}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(
                        DateUtils.getRelativeTimeSpanString(t.startedAt).toString(),
                        t.endedAt?.let { duration(t.millis) } ?: "running",
                        t.filesChanged?.let { if (it == 0) "no file changes" else "$it file${if (it == 1) "" else "s"} changed" },
                        if (t.before == null) "no checkpoint" else null,
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (t.before != null && t.after != null && (t.filesChanged ?: 0) > 0) TextButton(onClick = {
                        scope.launch { diff = t to Checkpoints.diff(context, chat.id, chat.cwd, t.before, t.after) }
                    }) { Text("Diff") }
                    // Not while the agent works: the restore and the agent would write the same files.
                    val busy = chat.state == ChatState.Running
                    if (t.before != null && t.endedAt != null) TextButton(enabled = !busy && !Checkpoints.restoring, onClick = { restoring = t }) {
                        Text(if (t.restore) "Undo this restore" else "Restore to before")
                    }
                }
            }
        }
    }

    restoring?.let { t ->
        AppSheet(if (t.restore) "Undo this restore?" else "Restore to before turn ${t.index}?", onDismiss = { restoring = null }, icon = Ph.ArrowCounterClockwise,
            actions = {
                OutlinedButton(onClick = { restoring = null }, modifier = Modifier.weight(1f)) { Text("Cancel") }
                Button(
                    onClick = {
                        restoring = null
                        // In the app's scope: closing this sheet mustn't stop a restore halfway.
                        val app = context.applicationContext
                        Checkpoints.scope.launch {
                            val r = Checkpoints.restore(app, chat.id, chat.cwd, t.before!!, label = if (t.restore) "Undid restore ${t.index}" else "Restored to before turn ${t.index}")
                            Toast.makeText(app, r.fold({ "Files restored. To undo, use “Undo this restore” in Checkpoints." }, { "Couldn't restore: ${it.message}" }), Toast.LENGTH_LONG).show()
                            reload++
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = colors.error, contentColor = colors.onError),
                    modifier = Modifier.weight(1f),
                ) { Text("Restore") }
            },
        ) {
            Text(
                if (t.restore) "The project's files go back to how they were just before that restore. Edits made since then are replaced. " +
                    "The agent isn't told; mention it in your next message."
                else "The project's files go back to how they were before “${t.prompt.take(80)}”: this turn's changes and every later one are undone. " +
                    "Files added since are removed. The agent isn't told; mention it in your next message. A snapshot is taken first, so you can undo this.",
                style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant,
            )
        }
    }
}

/** This chat's turns, time, changes, and what the agent reports (context used, cost). */
@Composable
fun UsageSheet(chat: ChatModel, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val stats by produceState(Checkpoints.Stats(0, 0, 0)) { value = withContext(Dispatchers.IO) { Checkpoints.stats(context, chat.id) } }
    val colors = MaterialTheme.colorScheme
    AppSheet("Usage", onDismiss = onDismiss, icon = Ph.Lightning, tile = AppColors.tiles.sky, subtitle = chat.title) {
        @Composable
        fun line(label: String, value: String) {
            Row(Modifier.fillMaxWidth()) {
                Text(label, color = colors.onSurfaceVariant, modifier = Modifier.weight(1f))
                Text(value, fontWeight = FontWeight.Medium)
            }
        }
        line("Prompts", "${stats.turns}")
        line("Time working", duration(stats.millis))
        line("Files changed", "${stats.filesChanged}")
        val u = chat.usage
        if (u != null) {
            line("Context used", "${u.used / 1000}k of ${u.size / 1000}k tokens (${(u.used * 100 / u.size.coerceAtLeast(1))}%)")
            line("Cost", u.costAmount?.let { String.format(java.util.Locale.US, "%.4f %s", it, u.currency ?: "USD") } ?: "not reported by ${chat.agent.displayName}")
        } else {
            Text("${chat.agent.displayName} hasn't reported token usage or cost for this session.", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
    }
}
