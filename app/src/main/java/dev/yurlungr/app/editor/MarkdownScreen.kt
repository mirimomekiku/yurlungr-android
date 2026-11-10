package dev.yurlungr.app.editor

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import dev.yurlungr.app.chat.MarkdownDoc
import dev.yurlungr.app.chat.MdBlockView
import dev.yurlungr.app.chat.rememberMdHost
import dev.yurlungr.app.projects.Git
import dev.yurlungr.app.ui.AppSheet
import dev.yurlungr.app.ui.Ph
import dev.yurlungr.app.ui.PhIcon
import dev.yurlungr.app.ui.ScreenHeader
import dev.yurlungr.app.ui.SkeletonCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

internal fun isMarkdownFile(name: String): Boolean = name.substringAfterLast('.', "").lowercase() in setOf("md", "markdown", "mdown")

/** Where each document was scrolled to, so Edit → Back (or reopening it) returns to the same place. */
private object MarkdownPositions {
    private val positions = HashMap<String, Pair<Int, Int>>()
    fun get(path: String) = positions[path] ?: (0 to 0)
    fun put(path: String, index: Int, offset: Int) { positions[path] = index to offset }
}

/** The viewer's text size (Settings-like, per app). */
private object MarkdownPrefs {
    private const val PREFS = "markdown_viewer"
    var scale by mutableFloatStateOf(1f)
        private set
    private var loaded = false
    fun load(context: Context) {
        if (loaded) return
        loaded = true
        scale = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getFloat("scale", 1f)
    }
    fun set(context: Context, value: Float) {
        scale = value.coerceIn(0.8f, 1.6f)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putFloat("scale", scale) }
    }
}

private sealed interface Loaded {
    data class Doc(val text: String, val doc: MarkdownDoc, val stamp: DiskStamp) : Loaded
    data class Error(val message: String) : Loaded
}

/** Reads and parses (off the main thread) a Markdown file for the viewer. */
private suspend fun load(file: File): Loaded = withContext(Dispatchers.IO) {
    runCatching {
        require(file.isFile) { "File not found" }
        require(file.length() <= 1024 * 1024) { "This file is too large to preview (over 1 MB)." }
        require(!Git.isBinary(file)) { "This file contains binary data." }
        val stamp = DiskStamp.of(file)
        val text = file.readText()
        Loaded.Doc(text, MarkdownDoc.parse(text), stamp)
    }.getOrElse { Loaded.Error(it.message ?: it.toString()) }
}

/**
 * Read-only preview. Editing is a separate route so Back returns to a freshly loaded preview, at
 * the same place. Blocks render lazily; it offers find, an outline, #anchor and footnote links, a
 * text size, the raw text, copy and share, and reloads when the file changes on disk.
 */
@Composable
fun MarkdownScreen(file: File, refreshTick: Int, onBack: () -> Unit, onEdit: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var loaded by remember(file.path) { mutableStateOf<Loaded?>(null) }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var matchIndex by remember { mutableIntStateOf(0) }
    var showOutline by remember { mutableStateOf(false) }
    var raw by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { MarkdownPrefs.load(context) }
    BackHandler {
        if (searching) { searching = false; query = "" } else onBack()
    }

    LaunchedEffect(file.path, refreshTick) { loaded = load(file) }
    // Follow changes made elsewhere (an agent editing the file, a pull): check every 1.5 s.
    LaunchedEffect(file.path) {
        while (true) {
            delay(1_500)
            val current = (loaded as? Loaded.Doc)?.stamp ?: continue
            val now = withContext(Dispatchers.IO) { DiskStamp.of(file) }
            if (now != current) loaded = load(file)
        }
    }

    val (startIndex, startOffset) = remember(file.path) { MarkdownPositions.get(file.path) }
    val listState = rememberLazyListState(startIndex, startOffset)
    DisposableEffect(file.path) {
        onDispose { MarkdownPositions.put(file.path, listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset) }
    }

    val doc = (loaded as? Loaded.Doc)?.doc
    // Blocks containing the search text, in order; next/previous jump between them.
    val matches = remember(doc, query) {
        val q = query.trim()
        if (doc == null || q.isEmpty()) emptyList()
        else doc.blocks.indices.filter { MarkdownDoc.plainText(doc.blocks[it]).contains(q, ignoreCase = true) }
    }
    fun jumpTo(i: Int) {
        if (matches.isEmpty()) return
        matchIndex = ((i % matches.size) + matches.size) % matches.size
        scope.launch { listState.animateScrollToItem(matches[matchIndex]) }
    }
    LaunchedEffect(matches) { matchIndex = 0; if (matches.isNotEmpty()) listState.animateScrollToItem(matches[0]) }

    val onAnchor: (String) -> Unit = { anchor ->
        val target = doc?.blockFor(anchor)
        if (target != null) scope.launch { listState.animateScrollToItem(target) }
        else Toast.makeText(context, "No section ${anchor.removePrefix("#")} in this file", Toast.LENGTH_SHORT).show()
    }
    val host = rememberMdHost(allowLocalFiles = true, baseDirectory = file.parentFile, onAnchor = onAnchor, query = if (searching) query else "")

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).systemBarsPadding()) {
        ScreenHeader(file.name, onBack) {
            IconButton(onClick = { searching = !searching; if (!searching) query = "" }) { PhIcon(Ph.MagnifyingGlass, "Find", size = 20.dp) }
            if (doc?.headings?.isNotEmpty() == true) {
                IconButton(onClick = { showOutline = true }) { PhIcon(Ph.ListChecks, "Outline", size = 20.dp) }
            }
            TextButton(onClick = onEdit) { Text("Edit") }
            androidx.compose.foundation.layout.Box {
                IconButton(onClick = { menu = true }) { PhIcon(Ph.DotsThreeVertical, "More", size = 20.dp) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(if (raw) "Show formatted" else "Show raw text") }, onClick = { raw = !raw; menu = false })
                    DropdownMenuItem(text = { Text("Copy all") }, onClick = {
                        menu = false
                        (loaded as? Loaded.Doc)?.text?.let { t ->
                            context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(file.name, t))
                            Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                        }
                    })
                    DropdownMenuItem(text = { Text("Share") }, onClick = {
                        menu = false
                        (loaded as? Loaded.Doc)?.text?.let { t ->
                            val send = Intent(Intent.ACTION_SEND).setType("text/markdown")
                                .putExtra(Intent.EXTRA_SUBJECT, file.name).putExtra(Intent.EXTRA_TEXT, t.take(100_000))
                            context.startActivity(Intent.createChooser(send, "Share ${file.name}"))
                        }
                    })
                    Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Text size", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(end = 8.dp))
                        TextButton(onClick = { MarkdownPrefs.set(context, MarkdownPrefs.scale - 0.1f) }, enabled = MarkdownPrefs.scale > 0.85f) { Text("A−") }
                        TextButton(onClick = { MarkdownPrefs.set(context, MarkdownPrefs.scale + 0.1f) }, enabled = MarkdownPrefs.scale < 1.55f) { Text("A+") }
                    }
                }
            }
        }
        if (searching) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    query, { query = it },
                    placeholder = { Text("Find in document") },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.weight(1f),
                )
                Text(
                    if (query.isBlank()) "" else if (matches.isEmpty()) "0" else "${matchIndex + 1}/${matches.size}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
                IconButton(onClick = { jumpTo(matchIndex - 1) }, enabled = matches.isNotEmpty()) { PhIcon(Ph.CaretUp, "Previous", size = 18.dp) }
                IconButton(onClick = { jumpTo(matchIndex + 1) }, enabled = matches.isNotEmpty()) { PhIcon(Ph.CaretDown, "Next", size = 18.dp) }
            }
        }

        // Text size: scale every sp in the document.
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, density.fontScale * MarkdownPrefs.scale)) {
            when (val l = loaded) {
                null -> SkeletonCode()
                is Loaded.Error -> Text(l.message, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(20.dp))
                is Loaded.Doc -> when {
                    l.text.isBlank() -> Text("Empty Markdown file", modifier = Modifier.padding(20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    raw -> SelectionContainer(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                        Text(l.text, fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(16.dp))
                    }
                    else -> SelectionContainer(Modifier.weight(1f).fillMaxWidth()) {
                        LazyColumn(
                            state = listState,
                            contentPadding = PaddingValues(20.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            itemsIndexed(l.doc.blocks) { _, block -> MdBlockView(block, host) }
                        }
                    }
                }
            }
        }
    }

    if (showOutline) doc?.let { d ->
        AppSheet("Outline", onDismiss = { showOutline = false }, subtitle = "${d.headings.size} headings", icon = Ph.ListChecks) {
            for (h in d.headings) {
                Text(
                    h.text,
                    style = if (h.level <= 2) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium,
                    color = if (h.level <= 2) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            showOutline = false
                            scope.launch { listState.animateScrollToItem(h.blockIndex) }
                        }
                        .padding(start = (12 * (h.level - 1)).dp, top = 6.dp, bottom = 6.dp),
                )
            }
        }
    }
}
