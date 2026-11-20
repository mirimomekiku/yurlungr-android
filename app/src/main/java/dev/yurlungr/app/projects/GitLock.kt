package dev.yurlungr.app.projects

import java.io.File

/**
 * Whether a git process is working in a repository, from /proc. Git leaves `.git/index.lock`
 * behind when it's killed (a timeout, the app closed mid-commit), and then refuses every write
 * until it's removed; but while a git is really running the lock is its own, and removing it
 * would corrupt the index. The runtime's processes run as the app's user, so their /proc
 * entries are readable.
 */
object GitLock {
    /** True when a process named git (or git-…) has [repo] (or a folder inside it) as its working folder or `-C` argument. */
    fun gitRunningIn(repo: File): Boolean {
        val root = runCatching { repo.canonicalPath }.getOrDefault(repo.absolutePath)
        val self = android.os.Process.myPid()
        return File("/proc").listFiles().orEmpty().any { proc ->
            val pid = proc.name.toIntOrNull() ?: return@any false
            if (pid == self) return@any false
            val argv = runCatching { File(proc, "cmdline").readBytes() }.getOrNull()?.let(::splitCmdline) ?: return@any false
            val cwd = runCatching { File(proc, "cwd").canonicalPath }.getOrNull()
            isGitIn(argv, cwd, root)
        }
    }

    /** /proc/N/cmdline: arguments separated (and ended) by NUL. */
    fun splitCmdline(bytes: ByteArray): List<String> =
        String(bytes, Charsets.UTF_8).split('\u0000').dropLastWhile { it.isEmpty() }

    /** Whether [argv] is a git command working in [root], judged by its [cwd] or its `-C` folder. */
    fun isGitIn(argv: List<String>, cwd: String?, root: String): Boolean {
        val name = argv.firstOrNull()?.substringAfterLast('/') ?: return false
        if (name != "git" && !name.startsWith("git-")) return false
        fun inside(path: String?) = path != null && (path.trimEnd('/') == root.trimEnd('/') || path.startsWith(root.trimEnd('/') + "/"))
        val dashC = argv.indices.firstOrNull { argv[it] == "-C" }?.let { argv.getOrNull(it + 1) }
        return inside(cwd) || inside(dashC?.let { runCatching { File(it).canonicalPath }.getOrDefault(it) })
    }
}
