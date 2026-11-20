package dev.yurlungr.app.chat

import kotlinx.coroutines.ensureActive
import java.io.File
import kotlin.coroutines.coroutineContext

/** Project files for the composer's @-mentions: a bounded, cancellable walk and the ranking of matches. */
object FileMentions {
    private val SKIP = setOf(".git", "node_modules", "build", ".gradle", ".idea", "dist", "target", ".next", "__pycache__", ".venv")

    /**
     * Relative paths of the project's files, at most [maxFiles], looking at most [maxEntries]
     * directory entries and [maxDepth] folders down: a home folder or a huge monorepo stays quick.
     * Stops as soon as the caller is cancelled (the user typed on).
     */
    suspend fun list(root: File, maxFiles: Int = 5_000, maxEntries: Int = 50_000, maxDepth: Int = 8): List<String> {
        val out = ArrayList<String>()
        var seen = 0
        val dirs = ArrayDeque<Pair<File, Int>>().apply { add(root to 0) }
        // Breadth first: with a budget, files near the top (the likely ones) are always found.
        while (dirs.isNotEmpty() && out.size < maxFiles && seen < maxEntries) {
            coroutineContext.ensureActive()
            val (dir, depth) = dirs.removeFirst()
            for (f in dir.listFiles().orEmpty().sortedBy { it.name }) {
                if (++seen > maxEntries || out.size >= maxFiles) break
                if (f.name in SKIP || f.name.startsWith(".") && f.name != ".github") continue
                if (f.isDirectory) {
                    if (depth < maxDepth) dirs.add(f to depth + 1)
                } else {
                    out += f.relativeTo(root).path
                }
            }
        }
        return out
    }

    /** The best [limit] of [files] for [query]: names that start with it first, then shorter paths. */
    fun match(files: List<String>, query: String, limit: Int = 8): List<String> {
        val q = query.lowercase()
        return files.asSequence()
            .filter { q.isEmpty() || it.lowercase().contains(q) }
            .sortedWith(compareBy({ !it.substringAfterLast('/').lowercase().startsWith(q) }, { it.length }))
            .take(limit)
            .toList()
    }
}
