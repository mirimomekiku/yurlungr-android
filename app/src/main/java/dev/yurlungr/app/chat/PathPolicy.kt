package dev.yurlungr.app.chat

import java.io.File

/**
 * Where an agent may read and write through ACP's `fs/read_text_file` and `fs/write_text_file`, and which paths a standing
 * approval rule may cover.
 *
 * Agents run with the app's own uid, so without this an agent (or text injected into it from a
 * cloned repo) could rewrite the app's preferences, for example to add approval rules, trust a
 * remote device or allow remote skip mode. Everything under the app's data folder is off limits
 * except the runtime itself (`files/`), and inside that, the app's own bookkeeping.
 *
 * [dataDir] is `/data/data/<package>` and [filesDir] is its `files/` folder. Both are passed in
 * so this stays a plain JVM class that tests can use.
 */
class PathPolicy(dataDir: File, filesDir: File, home: File) {
    private val data = norm(canonical(dataDir))
    private val files = norm(canonical(filesDir))
    private val home = norm(canonical(home))

    /** App bookkeeping inside `files/` that the runtime's tools never need to touch. */
    private val internal = listOf("checkpoints", "unsaved").map { "$files/$it" }

    /** The canonical file for an agent-supplied path, or null when it isn't absolute. */
    fun resolve(path: String): File? {
        if (path.isEmpty() || path.indexOf('\u0000') >= 0) return null
        val f = File(path)
        return if (f.isAbsolute) canonical(f) else null
    }

    /** Whether `fs/read_text_file` / `fs/write_text_file` may touch [file] (already canonical). */
    fun allowsAccess(file: File): Boolean {
        val p = norm(file)
        if (!isUnder(p, data)) return true // shared storage, /system, other places Android already limits
        if (!isUnder(p, files)) return false // shared_prefs, databases, app_chats, cache…
        return internal.none { isUnder(p, it) }
    }

    /**
     * Whether a standing rule for [project] may approve a tool call that names [paths] without asking.
     * Every path must be inside the project, not in its `.git` folder, and the project itself must
     * not be the runtime home (or a folder above it), whose dotfiles include agent credentials and
     * shell start-up files.
     */
    fun allowsAutoApproval(project: File, paths: List<String>): Boolean {
        val root = norm(canonical(project))
        if (isUnder(home, root)) return false
        return paths.all { raw ->
            val f = resolve(raw) ?: return@all false
            if (!allowsAccess(f) || !isUnder(norm(f), root)) return@all false
            val rel = norm(f).removePrefix(root).trimStart('/')
            rel.split('/').none { it == ".git" }
        }
    }

    companion object {
        /** True if [path] is [dir] or inside it (by whole path segments, so `/a/b` isn't inside `/a/bc`). */
        fun isUnder(path: String, dir: String): Boolean {
            val d = dir.trimEnd('/')
            return d.isEmpty() || path == d || path.startsWith("$d/")
        }

        private fun norm(f: File): String = f.path.replace(File.separatorChar, '/').trimEnd('/')

        private fun canonical(f: File): File = runCatching { f.canonicalFile }.getOrElse { f.absoluteFile.normalize() }
    }
}
