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
                "open" -> openFromRuntime(context, value)
                "install" -> install(context, File(value))
                "file" -> openFile(context, File(value))
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
                    val view = Intent(Intent.ACTION_VIEW)
                        .setDataAndType(uri, "application/vnd.android.package-archive")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                    if (inForeground()) {
                        context.startActivity(view)
                    } else {
                        // Android blocks activity starts from the background: the prompt waits in a notification.
                        TaskNotifications.prompt(
                            context, TaskNotifications.Prompt.Install,
                            "Install ${source.name}?", "Tap to open Android's installer. Asked for from the terminal or an agent.",
                            view, timeoutMs = ApkStaging.STAGED_TTL_MS,
                        )
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Couldn't open installer for $apk", e)
                    Toast.makeText(context, "Couldn't open the installer: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private val KINDS = setOf("open", "install", "notify", "done", "file")
    private const val RATE_MAX = 10
    private const val RATE_WINDOW_MS = 10_000L
    private val limiter = InboxRateLimiter(RATE_MAX, RATE_WINDOW_MS)

    /** The app is on screen; from the background Android silently drops activity starts. */
    private fun inForeground() = dev.yurlungr.app.chat.ChatSessions.appVisible

    /**
     * A link from `xdg-open` and friends: opened now while the app is on screen, otherwise offered
     * as a notification (an agent's sign-in page must not vanish because the user switched apps).
     */
    private fun openFromRuntime(context: Context, url: String) {
        val uri = url.toUri()
        if (uri.scheme?.lowercase() !in setOf("http", "https")) {
            Log.w(TAG, "Ignoring non-http(s) URL: $url")
            return
        }
        if (inForeground()) return open(context, url)
        lastUrl = url
        TaskNotifications.prompt(
            context, TaskNotifications.Prompt.Open,
            "Open ${uri.host ?: "link"}", url.take(300),
            Intent(Intent.ACTION_VIEW, uri),
        )
    }

    /**
     * A local file from `xdg-open` / `termux-open`: a copy goes to an app the user picks (see
     * [FileOpenStaging] for which files qualify), or into a notification while the app is in the
     * background.
     */
    fun openFile(context: Context, file: File) {
        val paths = RuntimePaths(context)
        val source = FileOpenStaging.openSource(paths.home, file)
        if (source == null) {
            Log.w(TAG, "Refusing to open $file")
            if (inForeground()) {
                Toast.makeText(context, "Only files in your home folder (not hidden ones, up to 100 MB) can be opened", Toast.LENGTH_LONG).show()
            }
            return
        }
        // FileProvider resolves against Context.getFilesDir() (/data/user/0/...), not /data/data/.
        val outDir = File(context.filesDir, FileOpenStaging.SHARE_OUT)
        thread(name = "file-stage", isDaemon = true) {
            val staged = try {
                FileOpenStaging.stage(source, outDir, System.currentTimeMillis())
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't stage $file to open", e)
                main.post { if (inForeground()) Toast.makeText(context, "Couldn't open ${source.name}: ${e.message}", Toast.LENGTH_LONG).show() }
                return@thread
            }
            val mime = FileOpenStaging.mimeType(source.name) { android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(it) }
            main.post {
                try {
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", staged)
                    val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    // A chooser: the runtime picks the file, the user picks the app it goes to.
                    val chooser = Intent.createChooser(view, "Open ${source.name}")
                    if (inForeground()) {
                        context.startActivity(chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    } else {
                        TaskNotifications.prompt(
                            context, TaskNotifications.Prompt.File,
                            "Open ${source.name}", "Tap to choose an app for it. Asked for from the terminal or an agent.",
                            chooser, timeoutMs = FileOpenStaging.TTL_MS,
                        )
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Couldn't open $file", e)
                    if (inForeground()) Toast.makeText(context, "Couldn't open ${source.name}: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

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
