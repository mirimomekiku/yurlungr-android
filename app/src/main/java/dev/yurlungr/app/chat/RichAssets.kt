package dev.yurlungr.app.chat

import android.content.Context
import android.webkit.WebResourceResponse
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import kotlin.coroutines.coroutineContext

/** One file of the diagram and math libraries, as published on npm. */
internal data class RichFile(val path: String, val size: Long, val sha256: String) {
    val url: String get() = RichBundle.urlFor(path)
}

/**
 * The Mermaid and KaTeX files chats draw diagrams and math with. They aren't in the APK (3.7 MB):
 * they're downloaded once, on request, at exactly these versions, and every file must match its
 * size and SHA-256 below (computed from the copies the app used to bundle, which are byte for byte
 * the published npm files).
 */
internal object RichBundle {
    const val MERMAID = "mermaid@10.9.3"
    const val KATEX = "katex@0.16.11"
    private const val CDN = "https://cdn.jsdelivr.net/npm/"

    /** Versioned, so an update to newer libraries never mixes old and new files. */
    const val DIR = "richtext-mermaid-10.9.3-katex-0.16.11"
    private const val DIR_PREFIX = "richtext-"
    const val MARKER = ".complete"

    /**
     * What the drawing WebView loads the libraries from. Not a real site: every request to it is
     * answered from the downloaded folder ([pathFor]), and everything else is refused.
     */
    const val BASE_URL = "https://appassets.androidplatform.net/richtext/"

    val files: List<RichFile> = listOf(
        RichFile("mermaid.min.js", 3336760, "5a8ec91820bd55afef049068489369910e5d6ce70c8103952f27e29d3e76e8bc"),
        RichFile("katex.min.js", 275414, "e6bfe5deebd4c7ccd272055bab63bd3ab2c73b907b6e6a22d352740a81381fd4"),
        RichFile("katex.min.css", 23335, "717bc9ae7853b61f0f76455dddf0ecd4f527a783f42de2ac24684899c1c46258"),
        RichFile("fonts/KaTeX_AMS-Regular.woff2", 28076, "0cdd387c9590a1a9f9794560022dbb59654a7d86f187aa0c81495ad42d3a7308"),
        RichFile("fonts/KaTeX_Caligraphic-Bold.woff2", 6912, "de7701e42cf1f4cf0b766c03fb27977207eee2f4fd5d76fa82188406da43ea4c"),
        RichFile("fonts/KaTeX_Caligraphic-Regular.woff2", 6908, "5d53e70ad607c2352162dec9e0923fb54ecdafaccbf604cd8dcf7d00facb989b"),
        RichFile("fonts/KaTeX_Fraktur-Bold.woff2", 11348, "74444efd593c005e3f4573b44524704c0af0a937fe911cca9e94068d0d140d3f"),
        RichFile("fonts/KaTeX_Fraktur-Regular.woff2", 11316, "51814d270d06ff0255dba0799994fa4d8c84d11f09951d47595f4abb1f3602dc"),
        RichFile("fonts/KaTeX_Main-Bold.woff2", 25324, "0f60d1b897938ec918c8ce073092411baf9438f6739465693ff18b0f9d20b021"),
        RichFile("fonts/KaTeX_Main-BoldItalic.woff2", 16780, "99cd42a3c072d918f2f44984a807cf7aa16e13545fd0875fc07c6c65f99e715b"),
        RichFile("fonts/KaTeX_Main-Italic.woff2", 16988, "97479ca6cce906abc961ecac96faa5f9ca2e61b8e7670d475826bcdee9a7c267"),
        RichFile("fonts/KaTeX_Main-Regular.woff2", 26272, "c2342cd8b869e01752a9321dc17213fc40d4d04c79688c1d43f2cf316abd7866"),
        RichFile("fonts/KaTeX_Math-BoldItalic.woff2", 16400, "dc47344dbb6cb5b655c8460d561f4df5f501b90c804ad3c6cec65fe322351ab1"),
        RichFile("fonts/KaTeX_Math-Italic.woff2", 16440, "7af58c5ec8f132a2ddde9027c6d7814decce4d3b822a11192a42a20e2e973264"),
        RichFile("fonts/KaTeX_SansSerif-Bold.woff2", 12216, "e99ae51144bf1232efcc1bfe5add36262c6866b0faab24fa75740e1b98577a62"),
        RichFile("fonts/KaTeX_SansSerif-Italic.woff2", 12028, "00b26ac825e2095056396e0553b8ac26d3f8ad158c3826e28b4c45b385c4714a"),
        RichFile("fonts/KaTeX_SansSerif-Regular.woff2", 10344, "68e8c73ef42afd3ccec58bf0fba302cce448938e7fc020a5e31f8a952eee1342"),
        RichFile("fonts/KaTeX_Script-Regular.woff2", 9644, "036d4e95149b69ff9bcc0cd55771efeb25ffa3947293e69acd78d5ac328c684b"),
        RichFile("fonts/KaTeX_Size1-Regular.woff2", 5468, "6b47c40166b6dbe21a5dfca7718413f2147fd2399be1ba605d8ad39cedf25dfe"),
        RichFile("fonts/KaTeX_Size2-Regular.woff2", 5208, "d04c54219f9eaec6d4d4fd42dfb28785975a4794d6b2fc71e566b9cd6db842dd"),
        RichFile("fonts/KaTeX_Size3-Regular.woff2", 3624, "73d591271b1604960cb10bb90fee021670af7297017e0e98480b332d11f51995"),
        RichFile("fonts/KaTeX_Size4-Regular.woff2", 4928, "a4af7d414440a1c1790825cfb700cf9cf43b0f2c4b04f0ebc523011ad9853ec0"),
        RichFile("fonts/KaTeX_Typewriter-Regular.woff2", 13568, "71d517d67827787cfabdf186914cc3358eda539e37931941f2b2fd4a21f68c0b"),
    )

    private val byPath = files.associateBy { it.path }

    val totalBytes: Long = files.sumOf { it.size }

    /** "3.7 MB": what the download costs, for buttons and Settings. */
    fun sizeLabel(bytes: Long = totalBytes): String = String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))

    /** The pinned CDN address of [path]: mermaid from its package, everything else from KaTeX's dist. */
    fun urlFor(path: String): String =
        if (path == "mermaid.min.js") "${CDN}$MERMAID/dist/$path" else "${CDN}$KATEX/dist/$path"

    /** The bundle file a WebView request for [url] names, or null for anything else (refused). */
    fun pathFor(url: String): RichFile? {
        if (!url.startsWith(BASE_URL)) return null
        val path = url.removePrefix(BASE_URL).substringBefore('#').substringBefore('?')
        return byPath[path]
    }

    fun mimeType(path: String): String = when (path.substringAfterLast('.')) {
        "js" -> "text/javascript"
        "css" -> "text/css"
        "woff2" -> "font/woff2"
        else -> "application/octet-stream"
    }

    /** Whether downloaded bytes are exactly the pinned file. */
    fun matches(file: RichFile, size: Long, sha256: String): Boolean =
        size == file.size && sha256.equals(file.sha256, ignoreCase = true)

    /** Folders of older bundle versions (or interrupted downloads) among [names], to delete. */
    fun staleDirs(names: List<String>): List<String> = names.filter { it.startsWith(DIR_PREFIX) && it != DIR }

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
}

/**
 * Downloads, keeps and serves [RichBundle]. Status is Compose state, so blocks waiting to draw
 * update when a download finishes; all disk and network work runs off the main thread.
 */
object RichAssets {
    enum class Status { Unknown, Missing, Downloading, Ready, Failed }

    var status by mutableStateOf(Status.Unknown)
        private set
    /** 0..1 while [Status.Downloading]. */
    var progress by mutableFloatStateOf(0f)
        private set
    var failure by mutableStateOf<String?>(null)
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var checking = false

    private fun dir(context: Context) = File(context.noBackupFilesDir, RichBundle.DIR)

    /** Looks on disk once per process; cheap to call from every block. */
    fun check(context: Context) {
        if (status != Status.Unknown || checking) return
        checking = true
        val app = context.applicationContext
        scope.launch {
            val ready = withContext(Dispatchers.IO) { isComplete(dir(app)) }
            checking = false
            if (status == Status.Unknown) status = if (ready) Status.Ready else Status.Missing
        }
    }

    /** Starts the one-time download, unless it's done or already running. */
    fun download(context: Context) {
        if (job?.isActive == true || status == Status.Ready) return
        val app = context.applicationContext
        status = Status.Downloading
        progress = 0f
        failure = null
        job = scope.launch {
            val result = runCatching { withContext(Dispatchers.IO) { fetchAll(app) } }
            if (result.isSuccess) {
                status = Status.Ready
            } else {
                val e = result.exceptionOrNull()
                if (e is kotlinx.coroutines.CancellationException) return@launch
                failure = when (e) {
                    is java.net.UnknownHostException -> "you seem to be offline"
                    is java.net.SocketTimeoutException -> "the connection timed out"
                    else -> e?.message ?: e.toString()
                }
                status = Status.Failed
            }
        }
    }

    /** Deletes the downloaded files; chats show diagrams and math as code again. */
    fun remove(context: Context) {
        val app = context.applicationContext
        job?.cancel()
        job = null
        status = Status.Missing
        failure = null
        scope.launch(Dispatchers.IO) {
            dir(app).deleteRecursively()
            cleanStale(app)
        }
    }

    /**
     * The answer to a WebView request (called on the WebView's own thread): a downloaded file
     * that's on the list and still the right size, else 404. Never a network load.
     */
    internal fun response(context: Context, url: String): WebResourceResponse {
        val entry = RichBundle.pathFor(url)
        val file = entry?.let { File(dir(context), it.path) }
        if (entry == null || file == null || file.length() != entry.size) {
            return WebResourceResponse("text/plain", "utf-8", 404, "Not found", emptyMap(), ByteArrayInputStream(ByteArray(0)))
        }
        val mime = RichBundle.mimeType(entry.path)
        val encoding = if (mime.startsWith("text/")) "utf-8" else null
        return WebResourceResponse(mime, encoding, 200, "OK", emptyMap(), FileInputStream(file))
    }

    private fun isComplete(dir: File): Boolean =
        File(dir, RichBundle.MARKER).isFile && RichBundle.files.all { File(dir, it.path).length() == it.size }

    private fun cleanStale(context: Context) {
        val root = context.noBackupFilesDir
        RichBundle.staleDirs(root.list().orEmpty().toList()).forEach { File(root, it).deleteRecursively() }
    }

    /** Downloads every file into a staging folder, verifies each, then moves the folder into place. */
    private suspend fun fetchAll(context: Context) {
        val root = context.noBackupFilesDir
        val staging = File(root, RichBundle.DIR + ".part")
        staging.deleteRecursively()
        cleanStale(context)
        try {
            var done = 0L
            for (entry in RichBundle.files) {
                val dest = File(staging, entry.path)
                dest.parentFile?.mkdirs()
                fetch(entry, dest) { bytes ->
                    val fraction = ((done + bytes).toFloat() / RichBundle.totalBytes).coerceIn(0f, 1f)
                    withContext(Dispatchers.Main) { progress = fraction }
                }
                done += entry.size
            }
            File(staging, RichBundle.MARKER).writeText(RichBundle.DIR)
            val target = dir(context)
            target.deleteRecursively()
            if (!staging.renameTo(target)) error("Couldn't save the files")
        } finally {
            staging.deleteRecursively()
        }
    }

    private suspend fun fetch(entry: RichFile, dest: File, onBytes: suspend (Long) -> Unit) {
        val conn = (URL(entry.url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 60_000
            // HttpURLConnection never follows a redirect from https to http; the hash checks the rest.
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "yurlungr-android")
        }
        try {
            if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode} for ${entry.path}")
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            var lastReport = 0L
            conn.inputStream.use { input ->
                FileOutputStream(dest).use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        // Bigger than the pinned file can only be the wrong file.
                        if (total > entry.size) error("${entry.path} is larger than expected")
                        out.write(buf, 0, n)
                        digest.update(buf, 0, n)
                        if (total - lastReport > 128 * 1024) {
                            lastReport = total
                            onBytes(total)
                        }
                    }
                }
            }
            val sha = RichBundle.hex(digest.digest())
            if (!RichBundle.matches(entry, total, sha)) error("${entry.path} didn't match its checksum")
            onBytes(total)
        } finally {
            conn.disconnect()
        }
    }
}
