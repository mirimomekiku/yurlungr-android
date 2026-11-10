package dev.yurlungr.app.runtime

import android.content.Context
import android.os.Build
import android.system.Os
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipFile
import kotlin.coroutines.coroutineContext

/**
 * Downloads and installs the Termux bootstrap (base system: bash, apt, coreutils, …) into
 * [RuntimePaths.prefix]. Follows the same steps as Termux's `TermuxInstaller`:
 * extract into a staging dir, recreate symlinks from SYMLINKS.txt, mark executables,
 * atomically rename into place, then run the bootstrap's second-stage script.
 */
class BootstrapInstaller(private val context: Context, private val paths: RuntimePaths) {

    data class Release(val tag: String, val url: String, val size: Long, val sha256: String?)

    fun interface Progress {
        /** [fraction] is null when indeterminate. */
        fun update(message: String, fraction: Float?)
    }

    suspend fun install(progress: Progress) = withContext(Dispatchers.IO) {
        val arch = RuntimePaths.termuxArch()
            ?: error("Unsupported ABI: ${Build.SUPPORTED_ABIS.joinToString()}")

        progress.update("Finding latest bootstrap for $arch…", null)
        val release = resolveLatest(arch)

        val zip = File(context.cacheDir, "bootstrap-$arch.zip")
        try {
            val sha = download(release.url, zip) { done ->
                progress.update("Downloading ${release.tag}", if (release.size > 0) done.toFloat() / release.size else null)
            }
            if (release.sha256 == null) {
                // Older API responses have no asset digest; the download still came over TLS from GitHub.
                Log.w(TAG, "No published SHA-256 for ${release.tag}; not verified beyond TLS (got $sha)")
            } else if (!release.sha256.equals(sha, ignoreCase = true)) {
                error("Checksum mismatch for ${release.tag}: expected ${release.sha256}, got $sha")
            }
            extract(zip, progress)
        } finally {
            zip.delete()
        }

        runSecondStage(progress)
        RuntimeAssets.install(context, paths)
        progress.update("Done", 1f)
    }

    /** Newest apt-based bootstrap release in termux/termux-packages with an asset for [arch]. */
    private fun resolveLatest(arch: String): Release {
        val releases = JSONArray(httpGet("https://api.github.com/repos/termux/termux-packages/releases?per_page=30"))
        for (i in 0 until releases.length()) {
            val r = releases.getJSONObject(i)
            val tag = r.getString("tag_name")
            if (r.optBoolean("draft") || r.optBoolean("prerelease")) continue
            if (!tag.startsWith("bootstrap-") || !tag.contains("+apt")) continue
            val assets = r.getJSONArray("assets")
            for (j in 0 until assets.length()) {
                val a = assets.getJSONObject(j)
                if (a.getString("name") != "bootstrap-$arch.zip") continue
                return Release(
                    tag = tag,
                    url = a.getString("browser_download_url"),
                    size = a.optLong("size"),
                    sha256 = a.optString("digest").removePrefix("sha256:").takeIf { it.length == 64 },
                )
            }
        }
        error("No apt bootstrap for $arch found in termux-packages releases")
    }

    private fun httpGet(url: String): String {
        val conn = open(url).apply { setRequestProperty("Accept", "application/vnd.github+json") }
        try {
            if (conn.responseCode !in 200..299) error("GET $url failed: HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    /** Streams [url] to [dest], returning the hex SHA-256 of the bytes written. */
    private suspend fun download(url: String, dest: File, onBytes: (Long) -> Unit): String {
        val conn = open(url)
        try {
            if (conn.responseCode !in 200..299) error("Download failed: HTTP ${conn.responseCode}")
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
                        out.write(buf, 0, n)
                        digest.update(buf, 0, n)
                        total += n
                        if (total - lastReport > 256 * 1024) {
                            lastReport = total
                            onBytes(total)
                        }
                    }
                }
            }
            onBytes(total)
            return digest.digest().joinToString("") { "%02x".format(it) }
        } finally {
            conn.disconnect()
        }
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "yurlungr-android")
        }

    private suspend fun extract(zip: File, progress: Progress) {
        // Leftovers from an interrupted install are unusable; start clean.
        RuntimePaths.deleteNoFollow(paths.staging)
        RuntimePaths.deleteNoFollow(paths.prefix)
        paths.staging.mkdirs()
        val stagingRoot = paths.staging.canonicalPath + File.separator
        val symlinks = mutableListOf<Pair<String, String>>()

        ZipFile(zip).use { zf ->
            val total = zf.size().coerceAtLeast(1)
            var index = 0
            for (entry in zf.entries()) {
                coroutineContext.ensureActive()
                if (++index % 100 == 0) progress.update("Extracting base system", index.toFloat() / total)

                val name = entry.name
                if (name == "SYMLINKS.txt") {
                    zf.getInputStream(entry).bufferedReader().forEachLine { line ->
                        if (line.isBlank()) return@forEachLine
                        val parts = line.split("←")
                        require(parts.size == 2) { "Malformed symlink line: $line" }
                        require(isContainedRelativePath(parts[1])) { "Symlink escapes prefix: ${parts[1]}" }
                        symlinks += parts[0] to File(paths.staging, parts[1]).path
                    }
                    continue
                }

                val target = File(paths.staging, name)
                require(target.canonicalPath.startsWith(stagingRoot)) { "Zip entry escapes prefix: $name" }
                if (entry.isDirectory) {
                    target.mkdirs()
                    continue
                }
                target.parentFile?.mkdirs()
                zf.getInputStream(entry).use { input ->
                    FileOutputStream(target).use { input.copyTo(it, 64 * 1024) }
                }
                if (isExecutable(name)) Os.chmod(target.path, "700".toInt(8))
            }
        }

        require(symlinks.isNotEmpty()) { "No SYMLINKS.txt in bootstrap zip" }
        progress.update("Creating ${symlinks.size} symlinks", null)
        for ((linkTarget, linkPath) in symlinks) {
            val parent = File(linkPath).parentFile ?: continue
            parent.mkdirs()
            // An earlier link in the list could make this folder point outside the prefix.
            require((parent.canonicalPath + File.separator).startsWith(stagingRoot)) { "Symlink escapes prefix: $linkPath" }
            Os.symlink(linkTarget, linkPath)
        }

        if (!paths.staging.renameTo(paths.prefix)) error("Could not move staging dir into prefix")
        paths.home.mkdirs()
        paths.tmp.mkdirs()
    }

    private fun isExecutable(name: String) =
        name.startsWith("bin/") || name.startsWith("libexec") ||
            name.startsWith("lib/apt/apt-helper") || name.startsWith("lib/apt/methods") ||
            name in SECOND_STAGE_PATHS

    private suspend fun runSecondStage(progress: Progress) {
        // Runs package postinst scripts. It takes a lock, so `login`'s fallback won't repeat it.
        val script = SECOND_STAGE_PATHS.map { File(paths.prefix, it) }.firstOrNull { it.isFile } ?: return
        progress.update("Running bootstrap second stage…", null)
        val result = CommandRunner.run(
            paths,
            listOf(paths.bash.path, script.path),
            timeoutMs = 10 * 60_000,
            onLine = { progress.update(it, null) },
        )
        if (!result.ok) {
            // A half-configured prefix would be used on next launch; remove it so setup can retry.
            RuntimePaths.deleteNoFollow(paths.prefix)
            error("Bootstrap second stage failed (exit ${result.exitCode}):\n${result.output.takeLast(2000)}")
        }
    }

    companion object {
        private const val TAG = "BootstrapInstaller"

        /** A relative path that stays inside its base folder: not absolute, no ".." segment. */
        internal fun isContainedRelativePath(path: String): Boolean =
            path.isNotEmpty() && !path.startsWith("/") && path.split('/').none { it == ".." }

        /** Current bootstraps use the first path; Termux app v0.118 expected the second. */
        private val SECOND_STAGE_PATHS = listOf(
            "etc/termux/termux-bootstrap/second-stage/termux-bootstrap-second-stage.sh",
            "etc/termux/bootstrap/termux-bootstrap-second-stage.sh",
        )
    }
}
