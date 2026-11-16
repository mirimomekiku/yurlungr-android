package dev.yurlungr.app.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.yurlungr.app.editor.FilePreview
import dev.yurlungr.app.editor.FilePreviewBody
import dev.yurlungr.app.editor.PreviewKind
import dev.yurlungr.app.ui.AppColors
import dev.yurlungr.app.ui.AppSheet
import dev.yurlungr.app.ui.LocalOpenFile
import dev.yurlungr.app.ui.Ph
import dev.yurlungr.app.ui.PhIcon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** A file the agent created or changed in a chat. [turn] is the prompt (1-based) that last touched it. */
data class Artifact(val path: String, val isNew: Boolean, val turn: Int) {
    val file: File get() = File(path)
    val name: String get() = file.name
    val preview: PreviewKind get() = FilePreview.kindOf(name)
}

object Artifacts {
    /** A file: URL inside [folder] (by whole path segments, so `/p/app` doesn't cover `/p/app-secrets`). */
    fun isInside(scheme: String?, path: String?, folder: String): Boolean =
        scheme == "file" && path != null && folder.isNotEmpty() && !path.split('/').contains("..") &&
            PathPolicy.isUnder(path, folder)

    /**
     * Files edited or written in [items], each once (its last version), by the prompt (turn) that changed it,
     * from the agent's edit diffs. Works for every agent that reports edits as diffs, remote chats included.
     */
    fun collect(items: List<ChatItem>): List<Artifact> {
        var turn = 0
        val seen = LinkedHashMap<String, Artifact>()
        for (item in items) {
            when (item) {
                is ChatItem.User -> turn++
                is ChatItem.ToolCall -> for (c in item.content) {
                    if (c is ToolContent.Diff && c.path.startsWith("/")) {
                        val before = seen[c.path]
                        seen[c.path] = Artifact(c.path, isNew = before?.isNew ?: (c.oldText == null), turn = turn)
                    }
                }
                else -> Unit
            }
        }
        return seen.values.toList()
    }

    /**
     * Joins several views of the same chat's files (edit diffs, and per-prompt snapshots that also see files
     * made by commands): one entry per path, new if its first sighting was new, in the latest prompt that touched it.
     */
    fun merge(vararg sources: List<Artifact>): List<Artifact> {
        val byPath = LinkedHashMap<String, Artifact>()
        for (a in sources.flatMap { it }.sortedBy { it.turn }) {
            val earlier = byPath[a.path]
            byPath[a.path] = if (earlier == null) a else a.copy(isNew = earlier.isNew)
        }
        return byPath.values.toList()
    }
}

/** Everything the agent produced in this chat, newest prompt first, with previews for images, pages, tables and JSON. */
@Composable
fun ArtifactsSheet(chat: ChatModel, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val open = LocalOpenFile.current
    val local = chat as? ChatSession
    // Derived from the items' state, so a diff arriving in a tool_call_update shows up too.
    val fromDiffs by remember(chat) { androidx.compose.runtime.derivedStateOf { Artifacts.collect(chat.items) } }
    // Per-prompt snapshots also catch what commands wrote (a chart from a script, a build's output).
    val fromSnapshots by produceState(emptyList<Artifact>(), fromDiffs, chat.state) {
        value = if (local == null) emptyList() else withContext(Dispatchers.IO) {
            val turns = Checkpoints.turns(context, chat.id).filter { !it.restore && it.before != null && it.after != null }.takeLast(30)
            val firstNumber = (Artifacts.collect(chat.items).maxOfOrNull { it.turn } ?: turns.size) - turns.size + 1
            turns.withIndex().flatMap { (i, t) ->
                Checkpoints.changedFilesWithStatus(context, chat.id, local.cwd, t.before!!, t.after!!)
                    .filter { (status, _) -> status != 'D' }
                    .take(200)
                    .map { (status, path) -> Artifact(File(local.cwd, path).path, isNew = status == 'A', turn = (firstNumber + i).coerceAtLeast(1)) }
            }
        }
    }
    val artifacts = remember(fromDiffs, fromSnapshots) { Artifacts.merge(fromSnapshots, fromDiffs) }
    var preview by remember { mutableStateOf<Artifact?>(null) }
    preview?.let { ArtifactPreview(it, onEdit = { f -> open?.invoke(f) }, onClose = { preview = null }) }
    AppSheet(
        "Artifacts", onDismiss = onDismiss, icon = Ph.File, tile = AppColors.tiles.lime,
        subtitle = if (artifacts.isEmpty()) "Nothing created yet" else "${artifacts.size} file${if (artifacts.size == 1) "" else "s"} from this chat",
    ) {
        if (artifacts.isEmpty()) {
            Text(
                "Files ${chat.agent.displayName} creates or edits show up here, with previews for images, HTML, SVG, CSV and JSON.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@AppSheet
        }
        val groups = artifacts.groupBy { it.turn }.toList().sortedByDescending { it.first }
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 460.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for ((turn, list) in groups) {
                item(key = "t$turn") {
                    Text(if (turn <= 0) "Before your first message" else "Prompt $turn", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                }
                items(list, key = { it.path }) { a ->
                    val exists = !chat.isRemote && a.file.isFile
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .clickable(enabled = exists) {
                                if (a.preview != PreviewKind.None) preview = a
                                else { open?.invoke(a.file); onDismiss() }
                            }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        PhIcon(when (a.preview) { PreviewKind.Image -> Ph.Image; PreviewKind.Web -> Ph.Globe; else -> Ph.FileText }, null, size = 20.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(a.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                (if (a.isNew) "New · " else "Edited · ") + (if (exists) a.file.parent.orEmpty().substringAfter("/projects/", a.file.parent.orEmpty()) else if (chat.isRemote) "on the other phone" else "no longer on disk"),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (exists && a.preview != PreviewKind.None && a.preview != PreviewKind.Image) {
                            IconButton(onClick = { open?.invoke(a.file); onDismiss() }) { PhIcon(Ph.PencilSimple, "Open in editor", size = 18.dp) }
                        }
                    }
                }
            }
        }
    }
}

/** Full-screen preview with Copy path and Share (and Edit for text-based files). */
@Composable
private fun ArtifactPreview(a: Artifact, onEdit: (File) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().statusBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose) { PhIcon(Ph.X, "Close", size = 22.dp) }
                    Text(a.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    IconButton(onClick = { FilePreview.copyPath(context, a.file) }) { PhIcon(Ph.Copy, "Copy path", size = 20.dp) }
                    TextButton(onClick = { FilePreview.share(context, a.file) }) { Text("Share") }
                    if (a.preview != PreviewKind.Image) TextButton(onClick = { onClose(); onEdit(a.file) }) { Text("Edit") }
                }
                Box(Modifier.weight(1f).fillMaxWidth()) { FilePreviewBody(a.file) }
            }
        }
    }
}
