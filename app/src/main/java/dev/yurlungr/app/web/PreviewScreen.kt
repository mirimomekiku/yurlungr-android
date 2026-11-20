package dev.yurlungr.app.web

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.WebStorage
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalDensity
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

private fun isLocal(url: String): Boolean = url.toUri().host?.lowercase() in setOf("127.0.0.1", "localhost")

/** The local port a preview URL points at, for its public link; null for other hosts. */
private fun localPort(url: String): Int? = url.toUri().takeIf { isLocal(url) }?.let { uri ->
    uri.port.takeIf { it > 0 } ?: if (uri.scheme == "https") 443 else 80
}

/**
 * A running local web app inside the app: back/forward within it, reload, a phone, tablet or
 * desktop layout, the page's console messages and failed requests, Open in the browser and the
 * public link (with a QR code). Links to other sites open in the browser; only 127.0.0.1 and
 * localhost stay here.
 */
@SuppressLint("SetJavaScriptEnabled") // a local dev server the user started
@Composable
fun PreviewScreen(url: String, title: String, onBack: () -> Unit, onOpenTerminal: () -> Unit = {}) {
    val context = LocalContext.current
    val log = remember { mutableStateListOf<PreviewLogEntry>() }
    fun add(entry: PreviewLogEntry) {
        log += entry
        if (log.size > PreviewLogs.MAX) log.removeAt(0)
    }
    var current by remember { mutableStateOf(url) }
    var progress by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var width by remember { mutableStateOf(PreviewWidth.Phone) }
    var widthMenu by remember { mutableStateOf(false) }
    var more by remember { mutableStateOf(false) }
    var showConsole by remember { mutableStateOf(false) }
    var sharing by remember { mutableStateOf(false) }
    // Set when another project used this origin before: the first page load also unregisters its service workers.
    var clearWorkersOnLoad by remember { mutableStateOf(false) }
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
                override fun onPageFinished(view: WebView, url: String) {
                    if (clearWorkersOnLoad && PreviewSiteData.origin(url) != null) {
                        clearWorkersOnLoad = false
                        view.evaluateJavascript(PreviewSiteData.clearScript(reloadIfAny = true), null)
                    }
                }
                override fun onReceivedError(view: WebView, request: WebResourceRequest, err: WebResourceError) {
                    if (request.isForMainFrame) error = "${err.description} (${request.url})"
                    add(PreviewLogs.request(request.method, request.url.toString(), null, err.description?.toString().orEmpty()))
                }
                override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                    add(PreviewLogs.request(request.method, request.url.toString(), response.statusCode, response.reasonPhrase.orEmpty()))
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView, newProgress: Int) { progress = newProgress }
                override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                    val level = when (message.messageLevel()) {
                        ConsoleMessage.MessageLevel.ERROR -> PreviewLogLevel.Error
                        ConsoleMessage.MessageLevel.WARNING -> PreviewLogLevel.Warning
                        else -> PreviewLogLevel.Info
                    }
                    add(PreviewLogEntry(level, message.message(), "${message.sourceId().substringAfterLast('/')}:${message.lineNumber()}"))
                    return true
                }
            }
        }
    }
    val scope = rememberCoroutineScope()
    /** Clears [origin]'s storage, cookies and cache; the page itself unregisters its service workers first when it's showing. */
    fun clearSiteData(origin: String, then: () -> Unit) {
        if (PreviewSiteData.origin(webView.url.orEmpty()) == origin) webView.evaluateJavascript(PreviewSiteData.clearScript(reloadIfAny = false), null)
        scope.launch {
            delay(300) // let the page's own clearing finish before its storage goes
            WebStorage.getInstance().deleteOrigin(origin)
            // Cookies have no per-origin delete: expire each one this origin sees (they're shared by every port on 127.0.0.1).
            val cookies = CookieManager.getInstance()
            for (name in PreviewSiteData.cookieNames(cookies.getCookie(origin))) cookies.setCookie(origin, "$name=; Max-Age=0; Path=/")
            cookies.flush()
            webView.clearCache(true)
            then()
        }
    }
    // Which project showed this origin last: a different one gets a clean slate rather than its leftovers.
    LaunchedEffect(Unit) {
        val origin = PreviewSiteData.origin(url)
        val port = localPort(url)
        val owner = port?.let { p -> WebRunSessions.all().firstOrNull { it.config.port == p && it.state.active }?.root?.path }
        if (origin != null && owner != null) {
            val prefs = context.getSharedPreferences("preview_origins", android.content.Context.MODE_PRIVATE)
            val previous = withContext(Dispatchers.IO) { prefs.getString(origin, null).also { prefs.edit().putString(origin, owner).apply() } }
            if (PreviewSiteData.needsClear(previous, owner)) {
                clearWorkersOnLoad = true
                clearSiteData(origin) {
                    Toast.makeText(context, "Another project used this port before: its site data was cleared.", Toast.LENGTH_SHORT).show()
                    webView.loadUrl(url)
                }
                return@LaunchedEffect
            }
        }
        webView.loadUrl(url)
    }
    DisposableEffect(Unit) { onDispose { webView.stopLoading(); webView.destroy() } }
    BackHandler { if (webView.canGoBack()) webView.goBack() else onBack() }
    val errors = log.count { it.level == PreviewLogLevel.Error }
    val colors = MaterialTheme.colorScheme
    val port = localPort(current) ?: localPort(url)
    val tunnel = port?.let { TunnelSessions.get(it) }?.takeIf { it.active }

    Column(Modifier.fillMaxSize().background(colors.background).systemBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            NavTile(onBack)
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(current, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = { webView.reload() }) { PhIcon(Ph.ArrowClockwise, "Reload", size = 19.dp) }
            Box {
                IconButton(onClick = { widthMenu = true }) { PhIcon(width.icon(), "Layout width: ${width.label}", size = 19.dp) }
                DropdownMenu(expanded = widthMenu, onDismissRequest = { widthMenu = false }) {
                    for (option in PreviewWidth.entries) DropdownMenuItem(
                        text = { Text(option.label + (option.cssWidth?.let { " · $it px" } ?: "")) },
                        leadingIcon = { PhIcon(option.icon(), null, size = 18.dp, tint = if (option == width) colors.primary else colors.onSurface) },
                        onClick = {
                            widthMenu = false
                            if (option != width) {
                                width = option
                                webView.settings.userAgentString = option.userAgent(WebSettings.getDefaultUserAgent(context))
                                webView.reload()
                            }
                        },
                    )
                }
            }
            Box {
                IconButton(onClick = { showConsole = true }) { PhIcon(Ph.Bug, "Console", size = 19.dp, tint = if (errors > 0) colors.error else colors.onSurface) }
                if (errors > 0) Text(
                    if (errors > 99) "99+" else "$errors", fontSize = 9.sp, color = colors.onError,
                    modifier = Modifier.align(Alignment.TopEnd).clip(RoundedCornerShape(8.dp)).background(colors.error).padding(horizontal = 4.dp),
                )
            }
            Box {
                IconButton(onClick = { more = true }) { PhIcon(Ph.DotsThreeVertical, "More", size = 19.dp) }
                DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                    DropdownMenuItem(text = { Text("Open in browser") }, leadingIcon = { PhIcon(Ph.ArrowUp, null, size = 18.dp) },
                        onClick = { more = false; RuntimeBridge.open(context, current) })
                    PreviewSiteData.origin(current)?.let { origin ->
                        DropdownMenuItem(
                            text = { Text("Clear site data") },
                            leadingIcon = { PhIcon(Ph.Trash, null, size = 18.dp) },
                            onClick = {
                                more = false
                                clearSiteData(origin) {
                                    Toast.makeText(context, "Cleared storage, cookies, cache and service workers", Toast.LENGTH_SHORT).show()
                                    webView.reload()
                                }
                            },
                        )
                    }
                    if (port != null) DropdownMenuItem(
                        text = { Text(if (tunnel?.state == TunnelState.Live) "Public link & QR code" else "Share public link") },
                        leadingIcon = { PhIcon(Ph.Globe, null, size = 18.dp, tint = if (tunnel?.state == TunnelState.Live) AppColors.status.success else colors.onSurface) },
                        onClick = { more = false; sharing = true },
                    )
                }
            }
        }
        if (progress in 1..99) LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val density = LocalDensity.current.density
            val frame = PreviewFrame.fit(constraints.maxWidth, constraints.maxHeight, density, width.cssWidth)
            AndroidView(
                factory = { ctx -> FrameLayout(ctx).apply { clipChildren = true; addView(webView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)) } },
                modifier = Modifier.fillMaxSize(),
                update = {
                    // FrameLayout gives a child its exact size even when wider than itself; the scale
                    // (from the top-left corner) brings it back to the screen, touches included.
                    val params = webView.layoutParams
                    if (params.width != frame.width || params.height != frame.height) {
                        params.width = frame.width; params.height = frame.height
                        webView.layoutParams = params
                    }
                    webView.pivotX = 0f; webView.pivotY = 0f
                    webView.scaleX = frame.scale; webView.scaleY = frame.scale
                },
            )
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

    if (sharing && port != null) TunnelSheet(port, title, onDismiss = { sharing = false }, onOpenTerminal = onOpenTerminal)

    if (showConsole) {
        val requests = log.count { it.network }
        AppSheet(
            "Console", onDismiss = { showConsole = false }, icon = Ph.Bug,
            subtitle = "${log.size - requests} messages · $requests failed requests · $errors errors",
            actions = {
                OutlinedButton(onClick = { log.clear() }, enabled = log.isNotEmpty(), modifier = Modifier.weight(1f)) { Text("Clear") }
                Button(onClick = {
                    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Console", PreviewLogs.format(log)))
                    Toast.makeText(context, "Console copied", Toast.LENGTH_SHORT).show()
                }, enabled = log.isNotEmpty(), modifier = Modifier.weight(1f)) { Text("Copy") }
            },
        ) {
            if (log.isEmpty()) Text("Nothing logged yet. Messages and failed requests show here.", color = colors.onSurfaceVariant)
            for (line in log.takeLast(200).asReversed()) {
                val tint = when (line.level) {
                    PreviewLogLevel.Error -> colors.error
                    PreviewLogLevel.Warning -> AppColors.status.warning
                    PreviewLogLevel.Info -> colors.onSurface
                }
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                        .background(if (line.level == PreviewLogLevel.Error) colors.errorContainer.copy(alpha = 0.4f) else Color.Transparent)
                        .padding(8.dp),
                ) {
                    Text(line.text, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = tint, maxLines = 12, overflow = TextOverflow.Ellipsis)
                    Text(if (line.network) "Request" else line.source, fontSize = 10.sp, color = colors.onSurfaceVariant)
                }
            }
        }
    }
}

private fun PreviewWidth.icon() = when (this) {
    PreviewWidth.Phone -> Ph.DeviceMobile
    PreviewWidth.Tablet -> Ph.DeviceTablet
    PreviewWidth.Desktop -> Ph.Laptop
}
