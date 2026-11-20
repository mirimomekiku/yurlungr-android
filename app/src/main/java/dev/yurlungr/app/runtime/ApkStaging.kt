package dev.yurlungr.app.runtime

import java.io.File

/**
 * APKs from `yurlungr-install` are copied into [APK_OUT] (under the app's files folder) before
 * the package installer gets a FileProvider grant: that folder is all FileProvider shares.
 */
object ApkStaging {
    /** Folder under files/ that FileProvider shares (res/xml/file_paths.xml): staged APKs only. */
    const val APK_OUT = "apk-out"
    /** Staged APKs older than this are deleted (the installer has long since read them). */
    internal const val STAGED_TTL_MS = 60 * 60_000L

    /** [apk] resolved, if it's an existing .apk file inside [filesDir]; else null. */
    fun installSource(filesDir: File, apk: File): File? {
        val root = runCatching { filesDir.canonicalPath }.getOrNull() ?: return null
        val canonical = runCatching { apk.canonicalFile }.getOrNull() ?: return null
        if (!canonical.path.startsWith(root + File.separator) || !canonical.name.endsWith(".apk") || !canonical.isFile) return null
        return canonical
    }

    /** A safe, unique file name for a staged copy of [name]. */
    fun stagedName(name: String, now: Long): String {
        val base = name.removeSuffix(".apk").replace(Regex("[^A-Za-z0-9._-]"), "_").trim('.').take(80).ifEmpty { "app" }
        return "$now-$base.apk"
    }

    /** Copies [source] into [outDir] (dropping stale copies first) and returns the copy. */
    fun stage(source: File, outDir: File, now: Long): File {
        outDir.mkdirs()
        outDir.listFiles()?.filter { now - it.lastModified() > STAGED_TTL_MS }?.forEach { it.delete() }
        val dest = File(outDir, stagedName(source.name, now))
        source.inputStream().use { input -> dest.outputStream().use { input.copyTo(it, 64 * 1024) } }
        return dest
    }
}
