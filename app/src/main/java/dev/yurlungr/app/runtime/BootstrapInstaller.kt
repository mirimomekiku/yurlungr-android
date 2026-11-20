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

        // Set for the whole install: until it completes, nothing may start from the prefix (it is
        // about to be replaced), and a kill leaves a prefix that looks installed but isn't. An
        // install that fails before touching the old prefix clears it again (unless it was
        // already there from an earlier interrupted install).
        val wasIncomplete = paths.bootstrapIncomplete.exists()
        // Old systems an earlier reinstall moved aside but didn't get to delete (the app was closed).
        deleteOldPrefixes(paths)
        // What's installed now, to offer it again afterwards (System checks). Before anything changes.
        if (paths.hasBaseSystem) {
            progress.update("Noting the installed packages", null)
            RestoreList.save(paths)
        }
        paths.bootstrapIncomplete.writeText("installing")
        var prefixReplaced = false
        try {
            progress.update("Finding latest bootstrap for $arch…", null)
            val release = resolveRelease(arch, progress)

            val zip = File(context.cacheDir, "bootstrap-$arch.zip")
            try {
                val sha = download(release.url, zip) { done ->
                    progress.update("Downloading ${release.tag}", if (release.size > 0) done.toFloat() / release.size else null)
                }
                val expected = expectedSha256(release.tag, arch, release.sha256)
                if (!expected.equals(sha, ignoreCase = true)) {
                    error("Checksum mismatch for ${release.tag}: expected $expected, got $sha")
                }
                Log.i(TAG, "Verified ${release.tag} ($arch): $sha${if (BOOTSTRAP_PINS.containsKey(release.tag)) " (pinned)" else ""}")
                extract(zip, progress) { prefixReplaced = true }
            } finally {
                zip.delete()
            }

            runSecondStage(progress)
            RuntimeAssets.install(context, paths)
            paths.bootstrapIncomplete.delete()
            progress.update("Done", 1f)
        } catch (e: Throwable) {
            // The old system is still intact (download or extract failed): it stays usable.
            if (!prefixReplaced && !wasIncomplete) paths.bootstrapIncomplete.delete()
            throw e
        }
    }

    /**
     * The release to install: the newest from GitHub's API or, when the API can't be used (it
     * allows 60 requests an hour per IP, often used up behind a carrier's shared address, or the
     * network failed), the newest pinned release, downloaded by its plain link and checked against
     * its pin. github.com's download links aren't rate-limited like the API.
     */
    private fun resolveRelease(arch: String, progress: Progress): Release = try {
        resolveLatest(arch)
    } catch (e: Exception) {
        val pinned = pinnedFallback(arch) ?: throw e
        Log.w(TAG, "GitHub API unavailable, using pinned ${pinned.tag}", e)
        progress.update("GitHub's release list is unavailable (${e.message ?: e.javaClass.simpleName}); using ${pinned.tag}", null)
        pinned
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

    /**
     * Extracts into a staging folder and only then swaps it in for the old prefix ([onReplaced]
     * marks the point of no return), so a full disk or a bad archive leaves the old system as it
     * was. Staging is removed on any failure.
     */
    private suspend fun extract(zip: File, progress: Progress, onReplaced: () -> Unit) {
        // Leftovers from an interrupted install are unusable; start clean.
        RuntimePaths.deleteNoFollow(paths.staging)
        paths.staging.mkdirs()
        try {
            extractInto(zip, progress)
        } catch (e: Throwable) {
            RuntimePaths.deleteNoFollow(paths.staging)
            throw e
        }
        progress.update("Replacing the base system", null)
        // Two renames instead of deleting the old system file by file first: a kill midway through
        // that left neither system. The old one is set aside, the new one moved in, and the old one
        // deleted afterwards, in the background.
        val old = File(paths.filesDir, "$OLD_PREFIX${System.currentTimeMillis()}")
        val hadPrefix = exists(paths.prefix)
        if (hadPrefix && !paths.prefix.renameTo(old)) {
            RuntimePaths.deleteNoFollow(paths.staging)
            error("Could not move the old base system aside")
        }
        if (!paths.staging.renameTo(paths.prefix)) {
            // Put the old one back: nothing replaced, still usable.
            if (hadPrefix && old.renameTo(paths.prefix)) {
                RuntimePaths.deleteNoFollow(paths.staging)
                error("Could not move the new base system into place; the old one is unchanged")
            }
            onReplaced()
            RuntimePaths.deleteNoFollow(paths.staging)
            error("Could not move the new base system into place")
        }
        onReplaced()
        if (hadPrefix) deleteInBackground(old)
        paths.home.mkdirs()
        paths.tmp.mkdirs()
    }

    /** Whether [file] exists, a dangling symlink included. */
    private fun exists(file: File): Boolean = file.exists() || runCatching { Os.lstat(file.path) }.isSuccess

    private suspend fun extractInto(zip: File, progress: Progress) {
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

        /** Old prefixes set aside by a reinstall, waiting to be deleted: `files/usr-old-<millis>`. */
        internal const val OLD_PREFIX = "usr-old-"

        /** Folders being deleted right now, so a second clean-up doesn't start on them. */
        private val deleting = HashSet<String>()

        /**
         * Deletes, in the background, old prefixes a reinstall set aside and didn't finish deleting
         * (the app was closed or killed). Safe to call any time, so also when System checks opens.
         */
        fun deleteOldPrefixes(paths: RuntimePaths) {
            paths.filesDir.listFiles { f -> f.name.startsWith(OLD_PREFIX) }.orEmpty().forEach(::deleteInBackground)
        }

        /**
         * Deletes [dir] on a background thread, making each folder writable first (packages can
         * install read-only ones) and never following symlinks (the prefix links into home and
         * shared storage).
         */
        internal fun deleteInBackground(dir: File) {
            synchronized(deleting) { if (!deleting.add(dir.path)) return }
            kotlin.concurrent.thread(name = "old-prefix-delete", isDaemon = true, priority = Thread.MIN_PRIORITY) {
                try {
                    RuntimePaths.deleteNoFollow(dir, makeWritable = true)
                    Log.i(TAG, "Deleted ${dir.name}")
                } catch (e: Exception) {
                    Log.w(TAG, "Couldn't delete ${dir.name}", e)
                } finally {
                    synchronized(deleting) { deleting.remove(dir.path) }
                }
            }
        }

        /**
         * The newest pinned release that has an archive for [arch], with its plain download link and
         * pin: what's installed when GitHub's API can't be used. Null if none is pinned for [arch].
         */
        internal fun pinnedFallback(arch: String, pins: Map<String, Map<String, String>> = BOOTSTRAP_PINS): Release? {
            val tag = pins.filter { arch in it.value }.keys.maxWithOrNull(compareBy({ releaseDate(it) }, { it })) ?: return null
            return Release(tag, downloadUrl(tag, arch), size = 0, sha256 = pins.getValue(tag).getValue(arch))
        }

        /** "2026.11.15" from "bootstrap-2026.11.15-r1+apt.android-7", for ordering releases. */
        private fun releaseDate(tag: String): String = Regex("""\d{4}\.\d{2}\.\d{2}""").find(tag)?.value.orEmpty()

        /** The asset's link on github.com (not the API); `+` in the tag percent-encoded, as GitHub's own links have it. */
        internal fun downloadUrl(tag: String, arch: String): String =
            "https://github.com/termux/termux-packages/releases/download/${tag.replace("+", "%2B")}/bootstrap-$arch.zip"

        /**
         * Known bootstrap archives: release tag → arch → SHA-256, copied from the asset digests
         * GitHub publishes for termux/termux-packages (read 2026-11-16). A release listed here
         * must match its pin, whatever the API says; add newer releases here as they're vetted.
         */
        internal val BOOTSTRAP_PINS: Map<String, Map<String, String>> = mapOf(
            "bootstrap-2026.11.15-r1+apt.android-7" to mapOf(
                "aarch64" to "388acf91cc247e7062d80cd8aa791fe72f360515fb78dc54ff45742120facae7",
                "arm" to "ff77211b7e71366b494d6b7d04211081b94de4a291ec5f783bc4ce77333a0c92",
                "i686" to "bb397db1a71c65ce8a0928b0c3015c0926f523726214a182e098570d15789afe",
                "x86_64" to "fb95765f2ffb4cf5648891199dedb820ed19a63334352fb0bc69b7b7c917e002",
            ),
            "bootstrap-2026.11.08-r1+apt.android-7" to mapOf(
                "aarch64" to "ad0df6ac4e8524684ac0b25ccf41459b0aaa515da9557a238d497b44f9034e46",
                "arm" to "12296b1e82c192ac8c59e69b2b18cd84c5f56c389c056f0502e227dbe841232e",
                "i686" to "bb72be24209d636087faf6dbe742e17ac0dca17fc28cc13b1d235c983ca7dfd2",
                "x86_64" to "41453bd2ec3f9690e2474085fbfe8d3406476b3cdd9627502d0ae4fb0d3eecb0",
            ),
            "bootstrap-2026.11.01-r1+apt.android-7" to mapOf(
                "aarch64" to "5a1429b6c68ee3bdc17b5fd6e47ba42933ec924cd7283fe74992574e1d5badae",
                "arm" to "99660c5a95733765c9967309a0c91c67335e9804b9e2027cfba71926fa4a3140",
                "i686" to "3ebf0424a9ffee25724c3ebb03d6e78bdd2c6d8e8fee9c421bb6db4efd0d143c",
                "x86_64" to "c0321730de9440ee8ea6f192151d5ecbe74f2391f07ffa9bf38584b4a0cbe616",
            ),
            "bootstrap-2026.10.25-r1+apt.android-7" to mapOf(
                "aarch64" to "964e0473a9fa881600927c0151b99896089fb595b81afdcd74b1a6743b8b5889",
                "arm" to "137d3a4a887194811dcc36e1c73c6f476105279648565b87491d67d0ab6abe3d",
                "i686" to "b2e6aeafd937df1890dff86eff4938894d19db2cd3f444e070cd2ac8e93adcd2",
                "x86_64" to "cb0ffc9f35dbb13971aad9683c393e6652bc95a4ba0eac905ca35065687f7de4",
            ),
        )

        /**
         * The SHA-256 the archive for [tag]/[arch] must have: the pinned one for a known release
         * (a pinned release with no pin for this arch is refused), else the digest from the API.
         * Fails closed: no pin and no published digest means no install.
         */
        internal fun expectedSha256(tag: String, arch: String, apiDigest: String?, pins: Map<String, Map<String, String>> = BOOTSTRAP_PINS): String {
            pins[tag]?.let { byArch ->
                return byArch[arch] ?: error("No pinned checksum for $tag on $arch; refusing to install it")
            }
            val digest = apiDigest?.trim()?.lowercase()?.takeIf { it.length == 64 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } }
            return digest ?: error("No published SHA-256 for $tag; refusing to install an unverified base system")
        }

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
