package dev.yurlungr.app.chat

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Color as AndroidColor
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import dev.yurlungr.app.ui.ThemePrefs
import kotlinx.serialization.json.JsonPrimitive
import java.io.ByteArrayInputStream

/** What a fenced block draws as instead of code. */
enum class RichKind(val label: String) { Mermaid("mermaid"), Math("math") }

object RichBlocks {
    private val MATH_FENCES = setOf("math", "latex", "tex", "katex")

    /** The kind a code fence's language names, or null for ordinary code. */
    fun kindOf(language: String?): RichKind? = when (language?.trim()?.lowercase()) {
        "mermaid" -> RichKind.Mermaid
        in MATH_FENCES -> RichKind.Math
        else -> null
    }

    private val FENCE = Regex("^\\s{0,3}(`{3,}|~{3,})")

    /**
     * Display math written as `$$ … $$` or `\[ … \]` on lines of its own, turned into ```math fences
     * before Markdown parsing (which would otherwise eat its backslashes). Code blocks are left alone.
     */
    fun fenceDisplayMath(text: String): String {
        if (!text.contains("$$") && !text.contains("\\[")) return text
        val lines = text.lines()
        val out = ArrayList<String>(lines.size)
        var fence: String? = null
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val open = FENCE.find(line)?.groupValues?.get(1)
            if (fence != null) {
                if (open != null && open[0] == fence[0] && open.length >= fence.length && line.trim().all { it == fence!![0] }) fence = null
                out += line; i++; continue
            }
            if (open != null) { fence = open; out += line; i++; continue }
            val t = line.trim()
            val (start, end) = when {
                t.startsWith("$$") -> "$$" to "$$"
                t.startsWith("\\[") -> "\\[" to "\\]"
                else -> null to null
            }
            if (start == null || end == null) { out += line; i++; continue }
            // One line ($$x$$) or several up to the closing delimiter.
            val firstRest = t.removePrefix(start)
            val body = StringBuilder()
            var closed = -1
            if (firstRest.trimEnd().endsWith(end) && firstRest.trim().length >= end.length) {
                body.append(firstRest.trimEnd().removeSuffix(end))
                closed = i
            } else {
                body.append(firstRest)
                var j = i + 1
                while (j < lines.size && j - i <= 200) {
                    val l = lines[j].trimEnd()
                    if (l.endsWith(end)) {
                        body.append('\n').append(l.removeSuffix(end))
                        closed = j
                        break
                    }
                    if (lines[j].isBlank() && body.isBlank()) break
                    body.append('\n').append(lines[j])
                    j++
                }
            }
            val math = body.toString().trim()
            if (closed < 0 || math.isEmpty()) { out += line; i++; continue }
            out += "```math"
            out += math.lines()
            out += "```"
            i = closed + 1
        }
        return out.joinToString("\n")
    }

    /** The page that draws [source]: the bundled library from assets, nothing from the network. */
    internal fun page(kind: RichKind, source: String, dark: Boolean, fg: String): String {
        // As a JS string literal; "</" can't end the script element early.
        val src = JsonPrimitive(source).toString().replace("</", "<\\/")
        val script = when (kind) {
            RichKind.Math -> """<link rel="stylesheet" href="katex.min.css"><script src="katex.min.js"></script>
<script>try{katex.render($src,out,{displayMode:true,throwOnError:true,trust:false});done()}catch(e){fail(e)}</script>"""
            RichKind.Mermaid -> """<script src="mermaid.min.js"></script>
<script>try{mermaid.initialize({startOnLoad:false,securityLevel:'strict',theme:'${if (dark) "dark" else "default"}'});
mermaid.render('d'+Date.now(),$src).then(function(r){out.innerHTML=r.svg;done()}).catch(fail)}catch(e){fail(e)}</script>"""
        }
        return """<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<style>html,body{margin:0;padding:0;background:transparent;color:$fg;font-family:sans-serif}
#out{padding:8px 4px;overflow-x:auto;text-align:center}#out svg{max-width:100%;height:auto}.katex-display{margin:0}</style>
</head><body><div id="out"></div>
<script>var out=document.getElementById('out');
function done(){setTimeout(function(){Rich.height(Math.ceil(out.getBoundingClientRect().height))},0)}
function fail(e){Rich.failed(String(e&&e.message||e))}</script>
$script</body></html>"""
    }

    /** Heights already measured, so a block scrolled back into view doesn't jump. */
    internal val heights = android.util.LruCache<String, Int>(64)
}

/**
 * A Mermaid diagram or display math, drawn in a small sandboxed WebView with the libraries bundled
 * in the app (nothing loads from the network). Shows the source as code when it can't be drawn;
 * "Code" toggles between the two.
 */
@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
@Composable
fun RichBlock(kind: RichKind, source: String, fallback: @Composable () -> Unit) {
    val context = LocalContext.current
    val dark = ThemePrefs.isDark()
    val colors = MaterialTheme.colorScheme
    val key = "${kind.name}:$dark:$source"
    var height by remember(key) { mutableStateOf(RichBlocks.heights.get(key)) }
    var failed by remember(key) { mutableStateOf<String?>(null) }
    var showCode by remember(key) { mutableStateOf(false) }
    // While a reply streams the source changes with every chunk: draw once it has settled.
    var settled by remember(key) { mutableStateOf(height != null) }
    androidx.compose.runtime.LaunchedEffect(key) {
        kotlinx.coroutines.delay(SETTLE_MS)
        settled = true
    }
    if (!settled) {
        fallback()
        return
    }
    if (failed != null && !showCode) {
        Column {
            fallback()
            Text("Couldn't draw this ${kind.label}: ${failed!!.take(160)}", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
        }
        return
    }
    Column(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).background(colors.surfaceContainer)) {
        Row(Modifier.fillMaxWidth().padding(start = 10.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(kind.label, color = colors.onSurfaceVariant, fontSize = 11.sp, modifier = Modifier.weight(1f))
            Text(
                if (showCode) "Diagram" else "Code",
                color = colors.onSurfaceVariant,
                fontSize = 11.sp,
                modifier = Modifier.clip(MaterialTheme.shapes.small).clickable { showCode = !showCode }.padding(horizontal = 8.dp, vertical = 6.dp),
            )
            Text(
                "Copy",
                color = colors.onSurfaceVariant,
                fontSize = 11.sp,
                modifier = Modifier.clip(MaterialTheme.shapes.small).clickable {
                    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(kind.label, source))
                    Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                }.padding(horizontal = 8.dp, vertical = 6.dp),
            )
        }
        if (showCode) {
            fallback()
            return@Column
        }
        val fg = String.format("#%06X", colors.onSurface.toArgb() and 0xFFFFFF)
        Box(Modifier.fillMaxWidth().height((height ?: 64).dp)) { androidx.compose.runtime.key(key) {
            AndroidView(
                modifier = Modifier.fillMaxWidth().height((height ?: 64).dp),
                factory = { ctx ->
                    val main = Handler(Looper.getMainLooper())
                    WebView(ctx).apply {
                        setBackgroundColor(AndroidColor.TRANSPARENT)
                        isVerticalScrollBarEnabled = false
                        settings.javaScriptEnabled = true
                        settings.blockNetworkLoads = true
                        settings.allowContentAccess = false
                        settings.allowFileAccessFromFileURLs = false
                        settings.allowUniversalAccessFromFileURLs = false
                        addJavascriptInterface(object {
                            @JavascriptInterface fun height(px: Int) = main.post {
                                val h = px.coerceIn(24, 2000)
                                RichBlocks.heights.put(key, h)
                                height = h
                            }.let { }
                            @JavascriptInterface fun failed(message: String) = main.post { failed = message }.let { }
                        }, "Rich")
                        webViewClient = object : WebViewClient() {
                            // Links in a diagram don't navigate the block.
                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true

                            // Only the bundled libraries load.
                            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                                if (request.url.toString().startsWith(BASE)) null
                                else WebResourceResponse("text/plain", "utf-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))
                        }
                        loadDataWithBaseURL(BASE, RichBlocks.page(kind, source, dark, fg), "text/html", "utf-8", null)
                    }
                },
                onRelease = { it.stopLoading(); it.destroy() },
            )
        } }
    }
}

private const val BASE = "file:///android_asset/richtext/"
private const val SETTLE_MS = 700L
