package dev.yurlungr.app.runtime

import android.system.Os
import android.system.OsConstants
import android.util.Log
import java.io.File
import kotlin.concurrent.thread

/**
 * Stops a process together with everything it started. Killing only the direct child leaves
 * its descendants running: shells' jobs, `timeout` (which moves itself into its own process
 * group), apt, npm, node. Descendants are found by walking `/proc` parent links *before*
 * anything is killed, because orphans are re-parented to init and can't be found afterwards.
 */
object ProcessTree {
    private const val TAG = "ProcessTree"

    /** The parent pid from a `/proc/<pid>/stat` line, or null. The name in parentheses may contain spaces or ')'. */
    fun parsePpid(stat: String): Int? {
        val afterName = stat.substring(stat.lastIndexOf(')') + 1).trim().split(' ')
        // Fields after the name: state, ppid, …
        return afterName.getOrNull(1)?.toIntOrNull()
    }

    /** All descendants of [root] in a pid → ppid map, children before grandchildren. */
    fun descendantsOf(root: Int, parents: Map<Int, Int>): List<Int> {
        val children = parents.entries.groupBy({ it.value }, { it.key })
        val out = mutableListOf<Int>()
        val queue = ArrayDeque(listOf(root))
        while (queue.isNotEmpty()) {
            val pid = queue.removeFirst()
            for (child in children[pid].orEmpty()) {
                if (child != root && child !in out) {
                    out += child
                    queue += child
                }
            }
        }
        return out
    }

    /** pid → ppid for the processes this app can see (its own UID's). */
    private fun snapshot(): Map<Int, Int> = File("/proc").listFiles().orEmpty().mapNotNull { dir ->
        val pid = dir.name.toIntOrNull() ?: return@mapNotNull null
        val ppid = runCatching { parsePpid(File(dir, "stat").readText()) }.getOrNull() ?: return@mapNotNull null
        pid to ppid
    }.toMap()

    private fun signal(pid: Int, sig: Int) {
        if (pid <= 1 || pid == Os.getpid()) return // never ourselves, init or (via 0) our own group
        runCatching { Os.kill(pid, sig) }
    }

    /**
     * Asks [root] and its descendants to stop (SIGHUP, then SIGTERM, like a closed terminal),
     * then SIGKILLs whatever is still there after [graceMs]. Returns at once; the kill runs on a
     * background thread. [root] must be > 0: pid 0 would mean this app's own process group.
     */
    fun terminate(root: Int, graceMs: Long = 1_500, alsoGroup: Boolean = false) {
        if (root <= 1) return
        val tree = listOf(root) + descendantsOf(root, snapshot())
        for (pid in tree) signal(pid, OsConstants.SIGHUP)
        for (pid in tree) signal(pid, OsConstants.SIGTERM)
        if (alsoGroup) runCatching { Os.kill(-root, OsConstants.SIGTERM) }
        thread(isDaemon = true, name = "process-tree-kill") {
            Thread.sleep(graceMs)
            // Children started during the grace period count too.
            val again = (tree + descendantsOf(root, snapshot())).distinct()
            for (pid in again) signal(pid, OsConstants.SIGKILL)
            if (alsoGroup) runCatching { Os.kill(-root, OsConstants.SIGKILL) }
            Log.d(TAG, "Stopped process tree of $root (${again.size} processes)")
        }
    }

    /** The pid of a [Process] started by ProcessBuilder (Android's UNIXProcess keeps it in a private field). */
    fun pidOf(process: Process): Int = runCatching {
        val field = process.javaClass.getDeclaredField("pid")
        field.isAccessible = true
        field.getInt(process)
    }.getOrDefault(0)
}
