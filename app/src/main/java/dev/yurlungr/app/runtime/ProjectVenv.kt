package dev.yurlungr.app.runtime

import java.io.File

/**
 * A project's Python virtual environment (`.venv`), active for every process started in the
 * project: terminals, web runs, checks and agents get VIRTUAL_ENV and its bin first on PATH, as
 * if `source .venv/bin/activate` had run.
 */
object ProjectVenv {
    const val DIR = ".venv"

    /** Folders walked up from the working directory, at most (a project's own subfolders). */
    private const val MAX_LEVELS = 8

    /**
     * The venv for processes in [cwd]: the nearest `.venv` in [cwd] or a folder above it, up to the
     * project folder (the child of [projects] that holds [cwd]), never above it. Outside
     * [projects], only [cwd] itself is looked at.
     */
    fun find(cwd: File, projects: File, prefixBin: File): File? {
        // Not canonical: VIRTUAL_ENV should read like the paths the user sees (/data/data/…).
        val dir = cwd.absoluteFile.normalize()
        val root = projects.absoluteFile.normalize()
        // The projects folder itself is no project.
        if (dir.path == root.path) return null
        val inside = dir.path.startsWith(root.path + File.separator)
        var d: File? = dir
        var levels = 0
        while (d != null && levels <= MAX_LEVELS) {
            val venv = File(d, DIR)
            if (usable(venv, d, prefixBin)) return venv
            // Stop at the project folder (directly under projects/), or right away outside projects/.
            if (!inside || d.parentFile?.path == root.path) break
            d = d.parentFile
            levels++
        }
        return null
    }

    /**
     * A venv made on this phone: it has pyvenv.cfg whose `home` is the runtime's own bin (so a
     * venv copied from a computer, or a folder that only looks like one, isn't put on PATH), and
     * it's really inside its project (not a link out of it).
     */
    internal fun usable(venv: File, owner: File, prefixBin: File): Boolean {
        val cfg = File(venv, "pyvenv.cfg")
        if (!cfg.isFile || cfg.length() > 16_384) return false
        if (!File(venv, "bin").isDirectory) return false
        val canonical = runCatching { venv.canonicalFile }.getOrNull() ?: return false
        if (canonical.parentFile?.path != runCatching { owner.canonicalFile.path }.getOrNull()) return false
        val home = runCatching { cfg.readLines() }.getOrDefault(emptyList())
            .firstNotNullOfOrNull { line -> line.split('=', limit = 2).takeIf { it.size == 2 && it[0].trim() == "home" }?.get(1)?.trim() }
            ?: return false
        fun same(a: File, b: File) = runCatching { a.canonicalPath == b.canonicalPath }.getOrDefault(false)
        return File(home).isAbsolute && same(File(home), prefixBin)
    }

    /** [env] with [venv] activated. */
    fun activate(env: Map<String, String>, venv: File): Map<String, String> {
        val bin = File(venv, "bin").path
        val path = env["PATH"].orEmpty().split(':').filter { it.isNotEmpty() && it != bin }
        return env + mapOf("VIRTUAL_ENV" to venv.path, "PATH" to (listOf(bin) + path).joinToString(":"))
    }
}
