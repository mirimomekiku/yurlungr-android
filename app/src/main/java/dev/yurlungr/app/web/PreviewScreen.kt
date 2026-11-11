package dev.yurlungr.app.web

import android.annotation.SuppressLint
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import dev.yurlungr.app.runtime.RuntimeBridge
import dev.yurlungr.app.ui.AppColors
import dev.yurlungr.app.ui.AppSheet
import dev.yurlungr.app.ui.NavTile
import dev.yurlungr.app.ui.Ph
import dev.yurlungr.app.ui.PhIcon

private data class ConsoleLine(val level: ConsoleMessage.MessageLevel, val text: String, val source: String)

private fun isLocal(url: String): Boolean = url.toUri().host?.lowercase() in setOf("127.0.0.1", "localhost")

/**
 * A running local web app inside the app: back/forward within it, reload, a phone or desktop
 * layout, the page's console messages and errors, and Open in the browser. Links to other sites
 * open in the browser; only 127.0.0.1 and localhost stay here.
 */
@SuppressLint("SetJavaScriptEnabled") // a local dev server the user started
@Composable
fun PreviewScreen(url: String, title: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val console = remember { mutableStateListOf<ConsoleLine>() }
    var current by remember { mutableStateOf(url) }
    var progress by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var desktop by remember { mutableStateOf(false) }
    var showConsole by remember { mutableStateOf(false) }
    val webView = remember {
        // Inspectable from a computer's chrome://inspect in debug builds.
        if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) WebView.setWebContentsDebuggingEnabled(true)
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val target = request.url.toString()
                    if (isLocal(target)) return false
                    RuntimeBridge.open(context, target)
                    return true
                }
                override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) { current = url; error = null }
                override fun onReceivedError(view: WebView, request: WebResourceRequest, err: WebResourceError) {
                    if (request.isForMainFrame) error = "${err.description} (${request.url})"
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView, newProgress: Int) { progress = newProgress }
                override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                    console += ConsoleLine(message.messageLevel(), message.message(), "${message.sourceId().substringAfterLast('/')}:${message.lineNumber()}")
                    if (console.size > 500) console.removeAt(0)
                    return true
                }
            }
            loadUrl(url)
        }
    }
    DisposableEffect(Unit) { onDispose { webView.stopLoading(); webView.destroy() } }
    BackHandler { if (webView.canGoBack()) webView.goBack() else onBack() }
    val errors = console.count { it.level == ConsoleMessage.MessageLevel.ERROR }
    val colors = MaterialTheme.colorScheme

    Column(Modifier.fillMaxSize().background(colors.background).systemBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            NavTile(onBack)
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(current, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = { webView.reload() }) { PhIcon(Ph.ArrowClockwise, "Reload", size = 19.dp) }
            IconButton(onClick = {
                desktop = !desktop
                val ua = android.webkit.WebSettings.getDefaultUserAgent(context)
                webView.settings.userAgentString = if (desktop) ua.replace(Regex("Android [^;)]*;?"), "X11; Linux x86_64;").replace(" Mobile", "") else null
                webView.reload()
            }) { PhIcon(if (desktop) Ph.Laptop else Ph.DeviceMobile, if (desktop) "Desktop layout" else "Phone layout", size = 19.dp) }
            Box {
                IconButton(onClick = { showConsole = true }) { PhIcon(Ph.Bug, "Console", size = 19.dp, tint = if (errors > 0) colors.error else colors.onSurface) }
                if (errors > 0) Text(
                    if (errors > 99) "99+" else "$errors", fontSize = 9.sp, color = colors.onError,
                    modifier = Modifier.align(Alignment.TopEnd).clip(RoundedCornerShape(8.dp)).background(colors.error).padding(horizontal = 4.dp),
                )
            }
            IconButton(onClick = { RuntimeBridge.open(context, current) }) { PhIcon(Ph.Globe, "Open in browser", size = 19.dp) }
        }
        if (progress in 1..99) LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
        Box(Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(factory = { webView }, modifier = Modifier.fillMaxSize())
            error?.let { message ->
                Column(
                    Modifier.fillMaxSize().background(colors.background).padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    PhIcon(Ph.Warning, null, size = 32.dp, tint = AppColors.status.warning)
                    Text("Couldn't load the page", style = MaterialTheme.typography.titleMedium)
                    Text(message, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    Text("Is the server still running? Check its Logs.", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    Button(onClick = { error = null; webView.reload() }) { Text("Try again") }
                }
            }
        }
    }

    if (showConsole) {
        AppSheet("Console", onDismiss = { showConsole = false }, subtitle = "${console.size} messages · $errors errors", icon = Ph.Bug) {
            if (console.isEmpty()) Text("Nothing logged yet.", color = colors.onSurfaceVariant)
            for (line in console.takeLast(200).asReversed()) {
                val tint = when (line.level) {
                    ConsoleMessage.MessageLevel.ERROR -> colors.error
                    ConsoleMessage.MessageLevel.WARNING -> AppColors.status.warning
                    else -> colors.onSurface
                }
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                        .background(if (line.level == ConsoleMessage.MessageLevel.ERROR) colors.errorContainer.copy(alpha = 0.4f) else Color.Transparent)
                        .padding(8.dp),
                ) {
                    Text(line.text, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = tint)
                    Text(line.source, fontSize = 10.sp, color = colors.onSurfaceVariant)
                }
            }
        }
    }
}
