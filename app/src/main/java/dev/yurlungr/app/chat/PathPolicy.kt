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
     * Every path must be inside the project, not in its `.git` folder or any other file that
     * configures agents, editors or hooks (see [isSensitive]), and the project itself must not be
     * the runtime home (or a folder above it), whose dotfiles include agent credentials and shell
     * start-up files.
     */
    fun allowsAutoApproval(project: File, paths: List<String>): Boolean {
        val root = norm(canonical(project))
        if (isUnder(home, root)) return false
        return paths.all { raw ->
            val f = resolve(raw) ?: return@all false
            if (!allowsAccess(f) || !isUnder(norm(f), root)) return@all false
            !isSensitive(norm(f).removePrefix(root).trimStart('/'))
        }
    }

    companion object {
        /**
         * Folders whose files decide what agents, editors and git run: an "Edit files" rule that
         * covered them would let an agent widen its own permissions (add MCP servers, allow
         * commands, add hooks or tasks that run later). Matched as any path segment.
         */
        private val SENSITIVE_DIRS = setOf(".git", ".claude", ".gemini", ".codex", ".qwen", ".cursor", ".husky", ".githooks")

        /** Files with the same power, matched by name anywhere in the project. */
        private val SENSITIVE_FILES = setOf(
            ".mcp.json", ".envrc", ".pre-commit-config.yaml", "lefthook.yml", "lefthook.yaml", ".lefthook.yml",
        )

        /**
         * Whether [relative] (a path inside a project, '/'-separated) is a file a standing rule must
         * never cover: agent and tool configuration (`.claude/`, `.mcp.json`, `.gemini/`, `.codex/`,
         * `.qwen/`, `.cursor/`), `.vscode/tasks.json`, environment files (`.envrc`, `.env*`), and git
         * hooks (`.git/`, `.husky/`, `.githooks/`, hook managers' configs). Those still ask every time.
         */
        fun isSensitive(relative: String): Boolean {
            val parts = relative.split('/').filter { it.isNotEmpty() && it != "." }
            if (parts.isEmpty()) return false // the project folder itself (a search of the whole project)
            val name = parts.last()
            if (parts.any { it in SENSITIVE_DIRS }) return true
            if (name in SENSITIVE_FILES) return true
            if (name == ".env" || name.startsWith(".env.")) return true
            if (parts.size >= 2 && parts[parts.size - 2] == ".vscode" && name == "tasks.json") return true
            return false
        }


        /** True if [path] is [dir] or inside it (by whole path segments, so `/a/b` isn't inside `/a/bc`). */
        fun isUnder(path: String, dir: String): Boolean {
            val d = dir.trimEnd('/')
            return d.isEmpty() || path == d || path.startsWith("$d/")
        }

        private fun norm(f: File): String = f.path.replace(File.separatorChar, '/').trimEnd('/')

        private fun canonical(f: File): File = runCatching { f.canonicalFile }.getOrElse { f.absoluteFile.normalize() }
    }
}
