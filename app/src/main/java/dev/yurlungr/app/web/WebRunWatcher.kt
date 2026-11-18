package dev.yurlungr.app.web

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Whether a server needs restarting after a save, or reloads itself (Vite, Next, `uvicorn --reload`…). */
internal object RestartRule {
    private val selfReloading = listOf(
        // Dev servers with hot reload or their own file watching.
        "\\b(vite|nuxt|nuxi|astro|react-scripts|parcel|webpack-dev-server|expo|remix|gatsby|docusaurus|eleventy|vue-cli-service|storybook)\\b",
        "\\bnext\\s+dev\\b", "\\bng\\s+serve\\b", "\\bwebpack\\s+serve\\b", "\\bsvelte-kit\\s+dev\\b",
        // Watchers that restart the program themselves.
        "\\b(nodemon|ts-node-dev|node-dev|watchexec|entr|cargo-watch|live-server|browser-sync)\\b",
        "\\bcargo\\s+watch\\b", "\\btsx\\s+watch\\b", "\\bair\\b", "\\bfastapi\\s+dev\\b",
        "--watch\\b", "--reload\\b", "--hot\\b", "--debug\\b", "\\bFLASK_DEBUG=(1|true)\\b",
        // Reload code on their own in development, or read files on every request.
        "\\bmanage\\.py\\s+runserver\\b(?!.*--noreload)", "\\brails\\s+(s|server)\\b", "\\bphx\\.server\\b",
        "\\bphp\\b", "\\bhugo\\s+server\\b", "\\bjekyll\\s+serve\\b", "\\bhttp\\.server\\b", "\\bhttp-server\\b",
    ).map { Regex(it, RegexOption.IGNORE_CASE) }

    fun reloadsItself(command: String): Boolean = selfReloading.any { it.containsMatchIn(command) }

    /**
     * On for servers that don't pick up changes themselves (Flask without debug, uvicorn without
     * --reload, Go, Rust, `node server.js`); off for hot-reloading dev servers and static sites.
     * `npm run x` style commands are judged by the script they run.
     */
    fun defaultFor(project: WebProject, mode: WebLaunchMode, script: String, custom: String): Boolean {
        if (project.kind == WebKind.Static || mode == WebLaunchMode.Framework) return false
        val command = if (mode == WebLaunchMode.Custom) custom else project.scriptCommands[script].orEmpty()
        val resolved = Regex("^\\s*(npm|pnpm|yarn|bun)\\s+(run\\s+)?([\\w:.-]+)\\s*$").find(command)
            ?.groupValues?.get(3)?.let(project.scriptCommands::get) ?: command
        return resolved.isNotBlank() && !reloadsItself(resolved)
    }
}

/** A cheap polling view of a project's files: what to skip, a snapshot, and what changed. */
internal object FileWatch {
    data class Stamp(val modified: Long, val size: Long)
    data class Changes(val added: List<String>, val removed: List<String>, val modified: List<String>) {
        val any get() = added.isNotEmpty() || removed.isNotEmpty() || modified.isNotEmpty()
    }

    const val MAX_FILES = 5_000
    private const val MAX_DEPTH = 12
    private val IGNORED_DIRS = setOf(
        "node_modules", "build", "dist", "target", "__pycache__", "venv", "env", "out", "coverage",
        "vendor", "tmp", "logs", "uploads",
    )
    private val IGNORED_SUFFIXES = listOf(
        ".log", ".pyc", ".pyo", ".swp", ".swo", ".tmp", "~", ".db", ".sqlite", ".sqlite3", "-journal",
        "-wal", "-shm", ".pid", ".lock",
    )

    /** Dependency, build and cache folders, plus every dot folder (.git, .venv, .gradle, .next…). */
    fun ignoredDir(name: String): Boolean = name.startsWith('.') || name in IGNORED_DIRS

    /** Files a running server or editor writes by itself (logs, databases, swap files): changing them must not restart it. */
    fun ignoredFile(name: String): Boolean = name == ".DS_Store" || name.startsWith(".#") || IGNORED_SUFFIXES.any { name.endsWith(it, ignoreCase = true) }

    /** Relative path → stamp for up to [max] files under [root], in a stable order. Blocking. */
    fun snapshot(root: File, max: Int = MAX_FILES): Map<String, Stamp> {
        val out = LinkedHashMap<String, Stamp>()
        val visited = HashSet<String>()
        fun walk(dir: File, prefix: String, depth: Int) {
            if (out.size >= max || depth > MAX_DEPTH) return
            // Symlinked folders could loop or leave the project.
            if (!visited.add(runCatching { dir.canonicalPath }.getOrDefault(dir.path))) return
            val children = dir.listFiles()?.sortedBy { it.name } ?: return
            for (child in children) {
                if (out.size >= max) return
                val name = child.name
                if (child.isDirectory) { if (!ignoredDir(name)) walk(child, "$prefix$name/", depth + 1) }
                else if (!ignoredFile(name)) out["$prefix$name"] = Stamp(child.lastModified(), child.length())
            }
        }
        walk(root, "", 0)
        return out
    }

    fun diff(before: Map<String, Stamp>, after: Map<String, Stamp>): Changes = Changes(
        added = after.keys.filter { it !in before },
        removed = before.keys.filter { it !in after },
        modified = after.filter { (path, stamp) -> before[path]?.let { it != stamp } == true }.keys.toList(),
    )
}

/**
 * Restarts a run when its project's files change: polls every second, waits for half a second of
 * quiet so a burst of saves restarts it once, and only restarts a server that's up. Stops with the run.
 */
internal object WebRunWatcher {
    private const val POLL_MS = 1_000L
    private const val QUIET_MS = 500L
    private const val MIN_GAP_MS = 3_000L

    fun watch(scope: CoroutineScope, run: WebRun, restart: () -> Unit): Job = scope.launch(Dispatchers.IO) {
        val root = run.config.project.directory
        var last = FileWatch.snapshot(root)
        var pending = false
        var lastRestart = 0L
        while (isActive && !run.finalized) {
            delay(POLL_MS)
            val now = FileWatch.snapshot(root)
            if (FileWatch.diff(last, now).any) {
                last = now
                // Saves often come in bursts (formatters, git checkouts): wait for them to settle, within reason.
                for (attempt in 1..10) {
                    delay(QUIET_MS)
                    val again = FileWatch.snapshot(root)
                    if (!FileWatch.diff(last, again).any) break
                    last = again
                }
                pending = true
            }
            if (pending && run.state == WebRunState.Running && System.currentTimeMillis() - lastRestart >= MIN_GAP_MS) {
                pending = false
                lastRestart = System.currentTimeMillis()
                withContext(Dispatchers.Main) { if (!run.finalized) restart() }
            }
        }
    }
}
