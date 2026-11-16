package dev.yurlungr.app.chat

import android.annotation.SuppressLint
import android.graphics.BitmapFactory
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.yurlungr.app.ui.AppColors
import dev.yurlungr.app.ui.AppSheet
import dev.yurlungr.app.ui.LocalOpenFile
import dev.yurlungr.app.ui.Ph
import dev.yurlungr.app.ui.PhIcon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** A file the agent created or changed in a chat, found from its edit tool calls. */
data class Artifact(val path: String, val isNew: Boolean, val turn: Int) {
    val file: File get() = File(path)
    val name: String get() = file.name
    val kind: ArtifactKind get() = ArtifactKind.of(name)
}

enum class ArtifactKind {
    Image, Web, Markdown, Code;

    companion object {
        fun of(name: String): ArtifactKind = when (name.substringAfterLast('.', "").lowercase()) {
            "png", "jpg", "jpeg", "webp", "gif", "bmp" -> Image
            "html", "htm", "svg" -> Web
            "md", "markdown" -> Markdown
            else -> Code
        }
    }
}

object Artifacts {
    /** A file: URL inside [folder] (by whole path segments, so `/p/app` doesn't cover `/p/app-secrets`). */
    fun isInside(scheme: String?, path: String?, folder: String): Boolean =
        scheme == "file" && path != null && folder.isNotEmpty() && !path.split('/').contains("..") &&
            PathPolicy.isUnder(path, folder)

    /**
     * Files edited or written in [items], each once (its last version), grouped by the prompt
     * (turn) that changed it. Works for every agent that reports edits as diffs, remote chats included.
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
}

/** Everything the agent produced in this chat, newest turn first, with previews for images and web files. */
@Composable
fun ArtifactsSheet(chat: ChatModel, onDismiss: () -> Unit) {
    val open = LocalOpenFile.current
    // Derived from the items' state, so a diff arriving in a tool_call_update shows up too.
    val artifacts by remember(chat) { androidx.compose.runtime.derivedStateOf { Artifacts.collect(chat.items) } }
    var preview by remember { mutableStateOf<Artifact?>(null) }
    preview?.let { ArtifactPreview(it, onClose = { preview = null }) }
    AppSheet(
        "Artifacts", onDismiss = onDismiss, icon = Ph.File, tile = AppColors.tiles.lime,
        subtitle = if (artifacts.isEmpty()) "Nothing created yet" else "${artifacts.size} file${if (artifacts.size == 1) "" else "s"} from this chat",
    ) {
        if (artifacts.isEmpty()) {
            Text(
                "Files ${chat.agent.displayName} creates or edits show up here, with previews for images, HTML and SVG.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@AppSheet
        }
        val groups = artifacts.groupBy { it.turn }.toList().sortedByDescending { it.first }
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 460.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for ((turn, list) in groups) {
                item(key = "t$turn") {
                    Text(if (turn == 0) "Before your first message" else "Prompt $turn", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                }
                items(list, key = { it.path }) { a ->
                    val exists = !chat.isRemote && a.file.isFile
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .clickable(enabled = exists) {
                                if (a.kind == ArtifactKind.Image || a.kind == ArtifactKind.Web) preview = a
                                else { open?.invoke(a.file); onDismiss() }
                            }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        PhIcon(if (a.kind == ArtifactKind.Image) Ph.Image else if (a.kind == ArtifactKind.Web) Ph.Globe else Ph.FileText, null, size = 20.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(a.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                (if (a.isNew) "New · " else "Edited · ") + (if (exists) a.file.parent.orEmpty().substringAfter("/projects/", a.file.parent.orEmpty()) else if (chat.isRemote) "on the other phone" else "no longer on disk"),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (exists && (a.kind == ArtifactKind.Image || a.kind == ArtifactKind.Web)) {
                            IconButton(onClick = { open?.invoke(a.file); onDismiss() }) { PhIcon(Ph.PencilSimple, "Open in editor", size = 18.dp) }
                        }
                    }
                }
            }
        }
    }
}

/** Full-screen preview: zoomable image, or the page rendered (HTML, SVG) with scripts allowed but no network escape. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun ArtifactPreview(a: Artifact, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().statusBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose) { PhIcon(Ph.X, "Close", size = 22.dp) }
                    Text(a.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    if (a.kind == ArtifactKind.Image) ZoomableImage(a.file) else WebFile(a.file)
                }
            }
        }
    }
}

@Composable
private fun ZoomableImage(file: File) {
    val bitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, file) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                // Downsampled: a camera-size PNG would not fit in memory at full size.
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }.also { BitmapFactory.decodeFile(file.path, it) }
                var sample = 1
                while (bounds.outWidth / sample > 2400 || bounds.outHeight / sample > 2400) sample *= 2
                BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
            }.getOrNull()
        }
    }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    val state = rememberTransformableState { zoom, pan, _ ->
        scale = (scale * zoom).coerceIn(1f, 8f)
        offset = if (scale == 1f) androidx.compose.ui.geometry.Offset.Zero else offset + pan
    }
    Box(Modifier.fillMaxSize().transformable(state), contentAlignment = Alignment.Center) {
        val b = bitmap
        if (b == null) Text("Couldn't open this image", color = MaterialTheme.colorScheme.onSurfaceVariant)
        else Image(
            b, file.name, contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y),
        )
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun WebFile(file: File) {
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
                // Agent-written (or repo-supplied) HTML is untrusted: no requests leave the phone.
                settings.blockNetworkLoads = true
                settings.loadWithOverviewMode = true
                settings.useWideViewPort = true
                settings.builtInZoomControls = true
                settings.displayZoomControls = false
                val folder = file.parentFile?.path.orEmpty()
                webViewClient = object : WebViewClient() {
                    // Links inside the artifact's folder work; anything else stays out of this view.
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                        !Artifacts.isInside(request.url.scheme, request.url.path, folder)

                    // Subresources too (images, scripts, fetch, iframes): only files from the artifact's folder load.
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): android.webkit.WebResourceResponse? =
                        if (Artifacts.isInside(request.url.scheme, request.url.path, folder)) null
                        else android.webkit.WebResourceResponse("text/plain", "utf-8", 403, "Blocked", emptyMap(), java.io.ByteArrayInputStream(ByteArray(0)))
                }
                loadUrl(android.net.Uri.fromFile(file).toString())
            }
        },
        onRelease = { it.stopLoading(); it.destroy() },
    )
}
