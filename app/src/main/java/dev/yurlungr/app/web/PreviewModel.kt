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
