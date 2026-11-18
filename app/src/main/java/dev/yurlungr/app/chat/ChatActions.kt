package dev.yurlungr.app.chat

import android.content.Context
import android.widget.Toast
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import dev.yurlungr.app.editor.FileExport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class ExportFormat(val extension: String, val label: String) { Markdown("md", "Markdown"), Json("json", "JSON") }

/** Writes [chat] to a staged file in the app's cache, ready to be saved or shared. */
fun stageExport(context: Context, title: String, agent: String, folder: String, items: List<ChatItem>, format: ExportFormat): File {
    val text = when (format) {
        ExportFormat.Markdown -> ChatExport.markdown(title, agent, folder, items)
        ExportFormat.Json -> ChatExport.json(title, agent, folder, items)
    }
    val root = File(context.cacheDir, "chat-export")
    // Earlier exports have long been copied out (the copy runs right after the picker closes).
    val dayAgo = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
    root.listFiles().orEmpty().filter { it.lastModified() < dayAgo }.forEach { it.deleteRecursively() }
    // A folder per export: the file keeps the chat's name, and two exports can't overwrite each other.
    val dir = File(root, System.nanoTime().toString()).apply { mkdirs() }
    return File(dir, ChatExport.fileName(title, format.extension)).apply { writeText(text) }
}

/** A function that exports a chat in a format and asks where to save it (the system "Save to…" picker). */
@Composable
fun rememberExportChat(chat: ChatModel): (ExportFormat) -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val save = FileExport.rememberSaveFile()
    return { format ->
        // Read on the main thread; written out on IO.
        val items = chat.items.toList()
        val title = chat.title
        val agent = chat.agent.displayName
        val folder = chat.cwdLabel
        scope.launch {
            val file = withContext(Dispatchers.IO) {
                runCatching { stageExport(context.applicationContext, title, agent, folder, items, format) }
            }
            file.fold(save) { Toast.makeText(context, "Couldn't export: ${it.message}", Toast.LENGTH_LONG).show() }
        }
    }
}

@Composable
fun RenameChatDialog(current: String, onRename: (String) -> Unit, onDismiss: () -> Unit) {
    var value by remember { mutableStateOf(TextFieldValue(current, selection = TextRange(0, current.length))) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename chat") },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                placeholder = { Text("Leave empty for an automatic name") },
            )
        },
        confirmButton = { TextButton(onClick = { onRename(value.text); onDismiss() }) { Text("Rename") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
