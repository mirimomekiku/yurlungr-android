package dev.yurlungr.app.editor

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.webkit.MimeTypeMap
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import dev.yurlungr.app.ui.Ph
import dev.yurlungr.app.ui.PhIcon
import dev.yurlungr.app.ui.ScreenHeader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

/** How a file is shown instead of as text: pictures, pages, tables, formatted JSON. */
enum class PreviewKind { None, Image, Web, Table, Json }

object FilePreview {
    private const val MAX_TEXT_BYTES = 5L * 1024 * 1024
    private const val MAX_ROWS = 2000
    private const val MAX_COLUMNS = 40

    fun kindOf(name: String): PreviewKind = when (name.substringAfterLast('.', "").lowercase()) {
        "png", "jpg", "jpeg", "webp", "gif", "bmp", "ico" -> PreviewKind.Image
        "html", "htm", "svg" -> PreviewKind.Web
        "csv", "tsv" -> PreviewKind.Table
        "json" -> PreviewKind.Json
        else -> PreviewKind.None
    }

    /** RFC 4180-style rows: quoted fields may hold the delimiter, quotes ("") and newlines. */
    fun parseDelimited(text: String, delimiter: Char = ','): List<List<String>> {
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val cell = StringBuilder()
        var quoted = false
        var i = 0
        fun endCell() { row.add(cell.toString()); cell.setLength(0) }
        fun endRow() { endCell(); if (row.size > 1 || row[0].isNotEmpty()) rows.add(row); row = ArrayList() }
        while (i < text.length) {
            val c = text[i]
            when {
                quoted && c == '"' && text.getOrNull(i + 1) == '"' -> { cell.append('"'); i++ }
                c == '"' -> quoted = !quoted
                quoted -> cell.append(c)
                c == delimiter -> endCell()
                c == '\n' -> endRow()
                c == '\r' -> Unit
                else -> cell.append(c)
            }
            i++
        }
        if (cell.isNotEmpty() || row.isNotEmpty()) endRow()
        return rows
    }

    fun delimiterFor(name: String): Char = if (name.endsWith(".tsv", ignoreCase = true)) '\t' else ','

    /** [text] re-indented, or null when it isn't valid JSON. */
    fun prettyJson(text: String): String? = runCatching {
        val pretty = Json { prettyPrint = true }
        pretty.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), Json.parseToJsonElement(text))
    }.getOrNull()

    internal fun readCapped(file: File): String? = if (file.length() > MAX_TEXT_BYTES) null else runCatching { file.readText() }.getOrNull()

    internal fun limits() = MAX_ROWS to MAX_COLUMNS

    /** Folder under files/ that FileProvider shares (res/xml/file_paths.xml): copies of shared files only. */
    const val SHARE_OUT = "share-out"
    private const val MAX_SHARE_BYTES = 200L * 1024 * 1024
    private const val SHARED_TTL_MS = 60 * 60_000L

    /**
     * Hands a copy of [file] to another app. Only [SHARE_OUT] is exposed through FileProvider, so the
     * rest of the app's storage (settings, agent credentials) never gets a URI.
     */
    fun share(context: Context, file: File) {
        val app = context.applicationContext
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: "application/octet-stream"
        @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
        kotlinx.coroutines.GlobalScope.launch(Dispatchers.Main) {
            val staged = withContext(Dispatchers.IO) {
                runCatching {
                    check(file.isFile) { "${file.name} is no longer on disk" }
                    check(file.length() <= MAX_SHARE_BYTES) { "${file.name} is too large to share from here" }
                    stage(file, File(app.filesDir, SHARE_OUT), System.currentTimeMillis())
                }
            }
            staged.mapCatching { copy ->
                val uri = FileProvider.getUriForFile(app, "${app.packageName}.files", copy)
                val send = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                context.startActivity(Intent.createChooser(send, "Share ${file.name}").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.onFailure { Toast.makeText(app, "Couldn't share: ${it.message}", Toast.LENGTH_LONG).show() }
        }
    }

    /** Copies [source] into its own folder under [outDir] (keeping its name for the receiving app), dropping stale copies first. */
    internal fun stage(source: File, outDir: File, now: Long): File {
        outDir.mkdirs()
        outDir.listFiles()?.filter { now - it.lastModified() > SHARED_TTL_MS }?.forEach { it.deleteRecursively() }
        val dir = File(outDir, now.toString()).apply { mkdirs() }
        val name = source.name.replace(Regex("[^A-Za-z0-9._ -]"), "_").trim('.', ' ').take(120).ifEmpty { "file" }
        val dest = File(dir, name)
        source.inputStream().use { input -> dest.outputStream().use { input.copyTo(it, 64 * 1024) } }
        return dest
    }

    fun copyPath(context: Context, file: File) {
        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Path", file.path))
        Toast.makeText(context, "Path copied", Toast.LENGTH_SHORT).show()
    }
}

/** A file viewer screen for pictures, pages, tables and JSON, with Copy path, Save a copy, Share and (for text) Edit. */
@Composable
fun FilePreviewScreen(file: File, onBack: () -> Unit, onEdit: (() -> Unit)? = null) {
    val context = LocalContext.current
    val saveFile = FileExport.rememberSaveFile()
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).systemBarsPadding()) {
        ScreenHeader(file.name, onBack) {
            IconButton(onClick = { FilePreview.copyPath(context, file) }) { PhIcon(Ph.Copy, "Copy path", size = 20.dp) }
            IconButton(onClick = { saveFile(file) }) { PhIcon(Ph.DownloadSimple, "Save a copy", size = 20.dp) }
            TextButton(onClick = { FilePreview.share(context, file) }) { Text("Share") }
            if (onEdit != null) TextButton(onClick = onEdit) { Text("Edit") }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) { FilePreviewBody(file) }
    }
}

/** The preview itself, for a screen or a dialog. */
@Composable
fun FilePreviewBody(file: File) {
    if (!file.isFile) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("This file is no longer on disk", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        return
    }
    when (FilePreview.kindOf(file.name)) {
        PreviewKind.Image -> ZoomableImage(file)
        PreviewKind.Web -> WebFile(file)
        PreviewKind.Table -> TableView(file)
        PreviewKind.Json -> JsonView(file)
        PreviewKind.None -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No preview for this file", color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
fun ZoomableImage(file: File) {
    val bitmap by produceState<ImageBitmap?>(null, file) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                // Downsampled: a camera-size picture would not fit in memory at full size.
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }.also { BitmapFactory.decodeFile(file.path, it) }
                var sample = 1
                while (bounds.outWidth / sample > 2400 || bounds.outHeight / sample > 2400) sample *= 2
                BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
            }.getOrNull()
        }
    }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val state = rememberTransformableState { zoom, pan, _ ->
        scale = (scale * zoom).coerceIn(1f, 8f)
        offset = if (scale == 1f) Offset.Zero else offset + pan
    }
    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                // Double tap: zoom in, or back to fit.
                detectTapGestures(onDoubleTap = { if (scale > 1f) { scale = 1f; offset = Offset.Zero } else scale = 3f })
            }
            .transformable(state),
        contentAlignment = Alignment.Center,
    ) {
        val b = bitmap
        if (b == null) Text("Couldn't open this image", color = MaterialTheme.colorScheme.onSurfaceVariant)
        else Image(
            b, file.name, contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y),
        )
    }
}

/**
 * HTML or SVG, rendered with scripts on but sandboxed: agent-written or repository HTML is untrusted, so
 * nothing leaves the phone (network loads are blocked) and only files from the page's own folder load.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebFile(file: File) {
    val context = LocalContext.current
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = {
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = true
                settings.allowFileAccessFromFileURLs = false
                settings.allowUniversalAccessFromFileURLs = false
                settings.allowContentAccess = false
                settings.blockNetworkLoads = true
                settings.loadWithOverviewMode = true
                settings.useWideViewPort = true
                settings.builtInZoomControls = true
                settings.displayZoomControls = false
                val folder = file.parentFile?.path.orEmpty()
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                        !dev.yurlungr.app.chat.Artifacts.isInside(request.url.scheme, request.url.path, folder)

                    // Subresources too (images, scripts, fetch, iframes): only files from the page's folder load.
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): android.webkit.WebResourceResponse? =
                        if (dev.yurlungr.app.chat.Artifacts.isInside(request.url.scheme, request.url.path, folder)) null
                        else android.webkit.WebResourceResponse("text/plain", "utf-8", 403, "Blocked", emptyMap(), java.io.ByteArrayInputStream(ByteArray(0)))
                }
                loadUrl(android.net.Uri.fromFile(file).toString())
            }
        },
        onRelease = { it.stopLoading(); it.destroy() },
    )
}

@Composable
private fun TableView(file: File) {
    val rows by produceState<List<List<String>>?>(null, file) {
        value = withContext(Dispatchers.IO) {
            FilePreview.readCapped(file)?.let { FilePreview.parseDelimited(it, FilePreview.delimiterFor(file.name)) }
        }
    }
    val data = rows
    if (data == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Too large to preview as a table. Open it in the editor.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        return
    }
    val (maxRows, maxCols) = FilePreview.limits()
    val shown = data.take(maxRows)
    val columns = shown.maxOfOrNull { it.size }?.coerceAtMost(maxCols) ?: 0
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize()) {
        Text(
            "${data.size} row${if (data.size == 1) "" else "s"} · $columns column${if (columns == 1) "" else "s"}" + if (data.size > maxRows) " · first $maxRows shown" else "",
            style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        Box(Modifier.weight(1f).horizontalScroll(rememberScrollState())) {
            LazyColumn(Modifier.fillMaxSize()) {
                itemsIndexed(shown) { i, row ->
                    Row(Modifier.background(if (i == 0) colors.surfaceContainerHigh else if (i % 2 == 0) colors.surfaceContainer else colors.background)) {
                        for (c in 0 until columns) {
                            Text(
                                row.getOrElse(c) { "" },
                                modifier = Modifier.width(150.dp).padding(horizontal = 10.dp, vertical = 7.dp),
                                maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 13.sp,
                                fontWeight = if (i == 0) FontWeight.SemiBold else FontWeight.Normal,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun JsonView(file: File) {
    // Split into lines for a lazy list: a few MB of formatted JSON as one Text would freeze the screen while it lays out.
    val lines by produceState<List<String>?>(null, file) {
        value = withContext(Dispatchers.IO) { FilePreview.readCapped(file)?.let { (FilePreview.prettyJson(it) ?: it).lines() } }
    }
    val body = lines
    if (body == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Too large to preview. Open it in the editor.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        return
    }
    SelectionContainer {
        LazyColumn(Modifier.fillMaxSize().horizontalScroll(rememberScrollState()), contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp)) {
            items(body.size) { i -> Text(body[i], fontFamily = FontFamily.Monospace, fontSize = 12.sp, softWrap = false) }
        }
    }
}
