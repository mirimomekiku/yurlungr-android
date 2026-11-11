package dev.yurlungr.app.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.yurlungr.app.agents.Agent
import dev.yurlungr.app.agents.agentStatuses
import dev.yurlungr.app.chat.Attachment
import dev.yurlungr.app.chat.ChatSession
import dev.yurlungr.app.chat.ChatSessions
import dev.yurlungr.app.projects.LastProject
import dev.yurlungr.app.projects.ProjectAgentPrefs
import dev.yurlungr.app.runtime.RuntimePaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** What another app shared with Yurlungr: text (or a link) and files. */
data class SharedContent(val text: String?, val subject: String?, val uris: List<Uri>) {
    companion object {
        fun from(intent: Intent?, ownPackage: String): SharedContent? {
            if (intent == null) return null
            val uris: List<Uri> = when (intent.action) {
                Intent.ACTION_SEND -> listOfNotNull(intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
                Intent.ACTION_SEND_MULTIPLE -> intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
                else -> return null
            }.filter { isForeign(it, ownPackage) }
            val text = intent.getStringExtra(Intent.EXTRA_TEXT)
            if (text.isNullOrBlank() && uris.isEmpty()) return null
            return SharedContent(text, intent.getStringExtra(Intent.EXTRA_SUBJECT), uris)
        }

        /**
         * Only content another app actually owns. The app opens shared links with its own access,
         * so a `file:` link or one to its own provider would hand out its private files (~/.ssh...).
         */
        internal fun isForeign(uri: Uri, ownPackage: String): Boolean {
            if (uri.scheme != "content") return false
            val authority = uri.authority ?: return false
            return authority.split(';').none { it == ownPackage || it.startsWith("$ownPackage.") }
        }
    }
}

private fun displayName(context: Context, uri: Uri): String =
    runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: (uri.lastPathSegment?.substringAfterLast('/') ?: "shared")

/**
 * Shared from another app: ask an agent about it in a project (text as the prompt, files as
 * attachments), or save the files into a project.
 */
@Composable
fun ShareSheet(content: SharedContent, onDismiss: () -> Unit, onOpenChat: (ChatSession) -> Unit) {
    val context = LocalContext.current
    val paths = remember { RuntimePaths(context) }
    val scope = rememberCoroutineScope()
    val projects by produceState(emptyList<File>()) {
        value = withContext(Dispatchers.IO) {
            paths.projects.listFiles { f -> f.isDirectory && !f.name.startsWith(".") }.orEmpty().sortedByDescending { it.lastModified() }
        }
    }
    val agents by produceState(emptyList<Agent>()) { value = withContext(Dispatchers.IO) { agentStatuses(paths).filter { it.installed }.map { it.agent } } }
    var project by remember { mutableStateOf(LastProject.get(context)) }
    var agent by remember { mutableStateOf<Agent?>(null) }
    val names = remember(content) { content.uris.map { displayName(context, it) } }
    var prompt by remember {
        mutableStateOf(
            when {
                content.text != null && content.uris.isEmpty() -> "Look at this:\n\n" + listOfNotNull(content.subject, content.text).joinToString("\n")
                content.uris.isNotEmpty() -> "Look at the attached file${if (content.uris.size > 1) "s" else ""}: ${names.joinToString()}."
                else -> ""
            },
        )
    }
    val chosenAgent = agent ?: project?.let { ProjectAgentPrefs.defaultAgent(context, it) }?.takeIf { it in agents } ?: agents.firstOrNull()

    AppSheet(
        "Share to Yurlungr", onDismiss = onDismiss, icon = Ph.Paperclip, tile = AppColors.tiles.lilac,
        subtitle = if (names.isEmpty()) "Text from another app" else names.joinToString(),
        actions = {
            if (content.uris.isNotEmpty()) OutlinedButton(
                enabled = project?.let { dev.yurlungr.app.projects.LastProject.acceptsSharedFiles(context, it) } == true,
                modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                onClick = {
                    val target = project?.takeIf { dev.yurlungr.app.projects.LastProject.acceptsSharedFiles(context, it) } ?: return@OutlinedButton
                    scope.launch {
                        val saved = withContext(Dispatchers.IO) {
                            content.uris.zip(names).mapNotNull { (uri, name) ->
                                runCatching {
                                    var dest = File(target, name)
                                    var i = 1
                                    while (dest.exists()) dest = File(target, name.substringBeforeLast('.') + " (${i++})" + name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" })
                                    context.contentResolver.openInputStream(uri)!!.use { input -> dest.outputStream().use { input.copyTo(it) } }
                                    dest.name
                                }.getOrNull()
                            }
                        }
                        Toast.makeText(context, if (saved.isEmpty()) "Couldn't save" else "Saved ${saved.joinToString()} to ${target.name}", Toast.LENGTH_LONG).show()
                        onDismiss()
                    }
                },
            ) { Text("Save to project") }
            Button(
                enabled = chosenAgent != null && prompt.isNotBlank(),
                modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                onClick = {
                    val a = chosenAgent ?: return@Button
                    val cwd = project ?: paths.home
                    scope.launch {
                        val attachments = withContext(Dispatchers.IO) {
                            content.uris.zip(names).mapNotNull { (uri, name) ->
                                runCatching {
                                    val type = context.contentResolver.getType(uri) ?: "application/octet-stream"
                                    val bytes = context.contentResolver.openInputStream(uri)!!.use { input ->
                                        val buf = java.io.ByteArrayOutputStream()
                                        val chunk = ByteArray(64 * 1024)
                                        while (true) {
                                            val n = input.read(chunk)
                                            if (n < 0) break
                                            buf.write(chunk, 0, n)
                                            require(buf.size() <= 10 * 1024 * 1024) { "too large" }
                                        }
                                        buf.toByteArray()
                                    }
                                    Attachment(name, type, bytes)
                                }.getOrNull()
                            }
                        }
                        if (attachments.size < content.uris.size) Toast.makeText(context, "Some files couldn't be attached (over 10 MB?)", Toast.LENGTH_LONG).show()
                        val chat = ChatSessions.create(context, a, cwd)
                        chat.queue(prompt.trim(), attachments)
                        onDismiss()
                        onOpenChat(chat)
                    }
                },
            ) { Text("Ask ${chosenAgent?.displayName ?: "an agent"}") }
        },
    ) {
        Text("Project", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("None (home)", selected = project == null, onClick = { project = null })
            for (p in projects.take(20)) Chip(p.name, selected = project == p, onClick = { project = p })
        }
        if (agents.size > 1) {
            Text("Agent", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (a in agents) Chip(a.displayName, selected = chosenAgent == a, onClick = { agent = a })
            }
        }
        OutlinedTextField(prompt, { prompt = it }, label = { Text("Message") }, minLines = 3, maxLines = 10,
            shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth())
    }
}
