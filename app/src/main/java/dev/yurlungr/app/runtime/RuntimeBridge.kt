package dev.yurlungr.app.runtime

import androidx.core.net.toUri
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.FileObserver
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import kotlin.concurrent.thread

/**
 * Receives requests from inside the runtime: "open this URL" from the `xdg-open` /
 * `termux-open-url` shims (agent logins open OAuth pages this way) and "install this APK"
 * from `yurlungr-install`.
 */
object RuntimeBridge {
    private const val TAG = "RuntimeBridge"
    private const val MAX_REQUEST_BYTES = 8 * 1024
    private val main = Handler(Looper.getMainLooper())
    private var observer: FileObserver? = null

    /** Most recent URL a tool asked to open; the UI offers it again in case the browser didn't appear. */
    var lastUrl by mutableStateOf<String?>(null)
        private set

    /**
     * Starts watching the inbox, or restarts the watch: after a base-system reinstall the inbox is
     * a new directory, and a watch on the deleted one would never fire again.
     */
    fun start(context: Context) {
        val app = context.applicationContext
        val paths = RuntimePaths(app)
        observer?.stopWatching()
        observer = null
        if (!paths.isBootstrapInstalled) return
        val inbox = paths.inbox.apply { mkdirs() }

        @Suppress("DEPRECATION") // File-based constructor needs API 29; minSdk is 24.
        val watcher = object : FileObserver(inbox.path, MOVED_TO or CLOSE_WRITE or DELETE_SELF or MOVE_SELF) {
            override fun onEvent(event: Int, name: String?) {
                if (event and (DELETE_SELF or MOVE_SELF) != 0) {
                    // The inbox went away (reinstall): forget this watch so the next start() makes a new one.
                    main.post { if (observer === this) { stopWatching(); observer = null } }
                    return
                }
                if (name != null && !name.startsWith(".")) consume(app, File(inbox, name))
            }
        }
        observer = watcher
        watcher.startWatching()

        // Requests written while the app wasn't running.
        inbox.listFiles { f -> !f.name.startsWith(".") }?.forEach { consume(app, it) }
    }

    private fun consume(context: Context, file: File) {
        // Claim the request first: the startup scan and the watcher can both see the same file,
        // and only the one whose rename succeeds handles it. Dot names are ignored by both.
        val claimed = File(file.parentFile, ".claimed-${System.nanoTime()}-${file.name}")
        if (!file.renameTo(claimed)) return
        val kind = file.extension
        // A URL or a path: anything longer isn't a real request (and mustn't be read into memory).
        val value = runCatching {
            claimed.inputStream().use { input ->
                val buf = ByteArray(MAX_REQUEST_BYTES + 1)
                var n = 0
                while (n < buf.size) {
                    val r = input.read(buf, n, buf.size - n)
                    if (r < 0) break
                    n += r
                }
                if (n > MAX_REQUEST_BYTES) null else String(buf, 0, n, Charsets.UTF_8).trim()
            }
        }.getOrNull()
        claimed.delete()
        if (value.isNullOrEmpty() || kind !in KINDS) return
        // Anything in the runtime can write here: a loop mustn't flood notifications or prompts.
        if (!limiter.tryAcquire(kind)) {
            Log.w(TAG, "Dropping $kind request: more than ${RATE_MAX} in ${RATE_WINDOW_MS / 1000} s")
            return
        }
        main.post {
            when (kind) {
                "open" -> open(context, value)
                "install" -> install(context, File(value))
                "notify" -> TaskNotifications.notifyFromRuntime(context, value)
                "done" -> TaskNotifications.commandDone(context, value)
            }
        }
    }

    /**
     * Hands an APK from the runtime to Android's package installer (the user confirms). The APK
     * (anywhere in the app's files) is first copied into [ApkStaging.APK_OUT], the only folder
     * FileProvider shares (res/xml/file_paths.xml), so the grant can't reach anything else.
     */
    fun install(context: Context, apk: File) {
        val paths = RuntimePaths(context)
        val source = ApkStaging.installSource(paths.filesDir, apk)
        if (source == null) {
            Log.w(TAG, "Refusing to install $apk")
            return
        }
        // FileProvider resolves against Context.getFilesDir() (/data/user/0/...), not /data/data/.
        val outDir = File(context.filesDir, ApkStaging.APK_OUT)
        thread(name = "apk-stage", isDaemon = true) {
            val staged = try {
                ApkStaging.stage(source, outDir, System.currentTimeMillis())
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't stage $apk for install", e)
                main.post { Toast.makeText(context, "Couldn't prepare the APK: ${e.message}", Toast.LENGTH_LONG).show() }
                return@thread
            }
            main.post {
                try {
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", staged)
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW)
                            .setDataAndType(uri, "application/vnd.android.package-archive")
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Couldn't open installer for $apk", e)
                    Toast.makeText(context, "Couldn't open the installer: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private val KINDS = setOf("open", "install", "notify", "done")
    private const val RATE_MAX = 10
    private const val RATE_WINDOW_MS = 10_000L
    private val limiter = InboxRateLimiter(RATE_MAX, RATE_WINDOW_MS)

    fun open(context: Context, url: String) {
        val uri = url.toUri()
        // Only web links: the runtime must not be able to fire arbitrary intents.
        if (uri.scheme?.lowercase() !in setOf("http", "https")) {
            Log.w(TAG, "Ignoring non-http(s) URL: $url")
            return
        }
        lastUrl = url
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "No browser for $url", e)
        }
    }
}
