package dev.yurlungr.app.web

import kotlin.math.roundToInt

/** How wide the previewed page lays out, in CSS pixels; null is the phone's own width. */
internal enum class PreviewWidth(val label: String, val cssWidth: Int?) {
    Phone("Phone", null), Tablet("Tablet", 768), Desktop("Desktop", 1280);

    /**
     * The user agent for this width from the WebView's [default] one, or null for the default.
     * Some servers pick their layout by user agent, so a wide preview also stops saying "Mobile".
     */
    fun userAgent(default: String): String? = when (this) {
        Phone -> null
        Tablet -> default.replace(" Mobile", "")
        Desktop -> default.replace(Regex("Android [^;)]*;?"), "X11; Linux x86_64;").replace(" Mobile", "")
    }
}

/**
 * The WebView's size in pixels and its scale. A wider layout is rendered at full width and
 * scaled down to fit the screen: the page sees a tablet or desktop viewport, the user sees all of it.
 */
internal data class PreviewFrame(val width: Int, val height: Int, val scale: Float) {
    companion object {
        fun fit(containerWidth: Int, containerHeight: Int, density: Float, cssWidth: Int?): PreviewFrame {
            val wanted = cssWidth?.let { (it * density).roundToInt() }
            if (wanted == null || wanted <= containerWidth || containerWidth <= 0) return PreviewFrame(containerWidth, containerHeight, 1f)
            val scale = containerWidth.toFloat() / wanted
            return PreviewFrame(wanted, (containerHeight / scale).roundToInt(), scale)
        }
    }
}

/**
 * Site data in the preview. The WebView keeps storage, cookies and service workers per origin, and an
 * origin here is just 127.0.0.1 and a port, which every project reuses (5173, 3000…): one project's
 * login, cached app shell or service worker would otherwise show up in the next one on that port.
 */
internal object PreviewSiteData {
    /** "http://127.0.0.1:5173" for a local [url]; null for anything else. */
    fun origin(url: String): String? = runCatching {
        val uri = java.net.URI(url)
        val host = uri.host?.lowercase() ?: return null
        if (host != "127.0.0.1" && host != "localhost") return null
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null
        val port = uri.port.takeIf { it > 0 } ?: if (scheme == "https") 443 else 80
        "$scheme://$host:$port"
    }.getOrNull()

    /** Clear before showing [owner] when the origin last showed a different, known project. */
    fun needsClear(previousOwner: String?, owner: String?): Boolean = owner != null && previousOwner != null && previousOwner != owner

    /** Cookie names in a `name=value; name2=value2` header (as CookieManager.getCookie returns it). */
    fun cookieNames(header: String?): List<String> =
        header.orEmpty().split(';').mapNotNull { part -> part.substringBefore('=').trim().takeIf { it.isNotEmpty() } }.distinct()

    /**
     * Run in the page: unregisters its service workers and empties its caches and storage (only the
     * page's own origin can reach them). With [reloadIfAny], reloads once when a service worker was
     * there, so the next load comes from the server rather than the old worker.
     */
    fun clearScript(reloadIfAny: Boolean): String = """
        (function(){
          try { localStorage.clear(); sessionStorage.clear(); } catch (e) {}
          if (window.caches) caches.keys().then(function(keys){ keys.forEach(function(k){ caches.delete(k); }); }).catch(function(){});
          if (!navigator.serviceWorker) return;
          navigator.serviceWorker.getRegistrations().then(function(rs){
            return Promise.all(rs.map(function(r){ return r.unregister(); })).then(function(){ ${if (reloadIfAny) "if (rs.length) location.reload();" else ""} });
          }).catch(function(){});
        })();
    """.trimIndent()
}

internal enum class PreviewLogLevel { Error, Warning, Info }

/** A console message or a failed request from the previewed page. */
internal data class PreviewLogEntry(val level: PreviewLogLevel, val text: String, val source: String, val network: Boolean = false)

internal object PreviewLogs {
    const val MAX = 500

    /** A failed request: a network error ([status] null) or an HTTP error status. Favicon misses are only warnings. */
    fun request(method: String, url: String, status: Int?, reason: String): PreviewLogEntry {
        val path = url.substringBefore('?')
        val level = if (status != null && status < 500 && path.endsWith("/favicon.ico")) PreviewLogLevel.Warning else PreviewLogLevel.Error
        val what = if (status != null) "$status ${reason.ifBlank { "HTTP error" }}" else reason.ifBlank { "Request failed" }
        return PreviewLogEntry(level, "$method $url → $what", "network", network = true)
    }

    /** The log as plain text, oldest first, for the clipboard. */
    fun format(entries: List<PreviewLogEntry>): String = entries.joinToString("\n") { entry ->
        "[${entry.level.name.lowercase()}] ${entry.text}" + if (entry.network) "" else " (${entry.source})"
    }
}
