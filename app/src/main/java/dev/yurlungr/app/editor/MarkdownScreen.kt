package dev.yurlungr.app.editor

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.yurlungr.app.chat.MarkdownText
import dev.yurlungr.app.projects.Git
import dev.yurlungr.app.ui.ScreenHeader
import dev.yurlungr.app.ui.SkeletonCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

internal fun isMarkdownFile(name: String): Boolean = name.substringAfterLast('.', "").lowercase() in setOf("md", "markdown", "mdown")

/** Read-only preview. Editing is a separate route so Back returns to a freshly loaded preview. */
@Composable
fun MarkdownScreen(file: File, refreshTick: Int, onBack: () -> Unit, onEdit: () -> Unit) {
    BackHandler(onBack = onBack)
    var document by remember(file.path) { mutableStateOf<String?>(null) }
    var error by remember(file.path) { mutableStateOf<String?>(null) }
    LaunchedEffect(file.path, refreshTick) {
        val result = withContext(Dispatchers.IO) {
            runCatching {
                require(file.isFile) { "File not found" }
                require(file.length() <= 1024 * 1024) { "This file is too large to preview (over 1 MB)." }
                require(!Git.isBinary(file)) { "This file contains binary data." }
                file.readText()
            }
        }
        document = result.getOrNull()
        error = result.exceptionOrNull()?.message
    }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).systemBarsPadding()) {
        ScreenHeader(file.name, onBack) {
            TextButton(onClick = onEdit) { Text("Edit") }
        }
        when {
            error != null -> Text(error.orEmpty(), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(20.dp))
            document == null -> SkeletonCode()
            document!!.isEmpty() -> Text("Empty Markdown file", modifier = Modifier.padding(20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            else -> SelectionContainer(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                MarkdownText(document.orEmpty(), Modifier.padding(20.dp), baseDirectory = file.parentFile)
            }
        }
    }
}
