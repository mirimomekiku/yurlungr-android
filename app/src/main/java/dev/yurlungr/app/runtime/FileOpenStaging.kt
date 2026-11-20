package dev.yurlungr.app.runtime

import java.io.File

/**
 * Files `xdg-open` / `termux-open` hand to Android apps: a copy goes into [SHARE_OUT], the folder
 * FileProvider shares for Share (res/xml/file_paths.xml, also `FilePreview.SHARE_OUT`), so the
 * receiving app's grant reaches that copy and nothing else. Only ordinary files in the home folder
 * qualify: not hidden ones or those in hidden folders (`~/.ssh`, agent credentials, `.git`), and
 * not huge ones.
 */
object FileOpenStaging {
    const val SHARE_OUT = "share-out"
    const val MAX_BYTES = 100L * 1024 * 1024
    /** Copies older than this are deleted (the app they went to has long since read them). */
    const val TTL_MS = 60 * 60_000L

    /** [file] resolved, if it's an existing, non-hidden file of at most [MAX_BYTES] inside [home]; else null. */
    fun openSource(home: File, file: File): File? {
        val root = runCatching { home.canonicalPath }.getOrNull() ?: return null
        val canonical = runCatching { file.canonicalFile }.getOrNull() ?: return null
        if (!canonical.path.startsWith(root + File.separator)) return null
        val relative = canonical.path.removePrefix(root + File.separator)
        if (relative.split(File.separatorChar).any { it.startsWith(".") }) return null
        if (!canonical.isFile || canonical.length() > MAX_BYTES) return null
        return canonical
    }

    /** A safe name for the copy, keeping the original's (the receiving app shows it). */
    fun stagedName(name: String): String =
        name.replace(Regex("[^A-Za-z0-9._ -]"), "_").trim('.', ' ').take(120).ifEmpty { "file" }

    /** Copies [source] into its own folder under [outDir], dropping stale copies first. */
    fun stage(source: File, outDir: File, now: Long): File {
        outDir.mkdirs()
        outDir.listFiles()?.filter { now - it.lastModified() > TTL_MS }?.forEach { it.deleteRecursively() }
        val dir = File(outDir, "open-$now").apply { mkdirs() }
        val dest = File(dir, stagedName(source.name))
        source.inputStream().use { input -> dest.outputStream().use { input.copyTo(it, 64 * 1024) } }
        return dest
    }

    /** Source and text files Android's MIME table doesn't know: shown as text rather than not at all. */
    private val TEXT = setOf(
        "md", "markdown", "txt", "log", "csv", "tsv", "json", "yaml", "yml", "toml", "ini", "cfg", "conf", "env",
        "sh", "bash", "zsh", "py", "js", "mjs", "cjs", "ts", "tsx", "jsx", "kt", "kts", "java", "go", "rs", "rb", "php",
        "c", "h", "cc", "cpp", "hpp", "cs", "swift", "dart", "lua", "sql", "gradle", "properties", "xml", "diff", "patch",
    )

    /** The MIME type for [name]: from [lookup] (Android's table) by extension, text for code, else a generic binary type. */
    fun mimeType(name: String, lookup: (String) -> String?): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext.isEmpty()) return "application/octet-stream"
        return lookup(ext) ?: if (ext in TEXT) "text/plain" else "application/octet-stream"
    }
}
