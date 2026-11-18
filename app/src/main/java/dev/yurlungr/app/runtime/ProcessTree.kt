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
    fun parsePpid(stat: String): Int? = fieldsAfterName(stat).getOrNull(1)?.toIntOrNull()

    /** The start time (field 22, clock ticks since boot) from a `/proc/<pid>/stat` line, or null. */
    fun parseStartTime(stat: String): Long? = fieldsAfterName(stat).getOrNull(22 - 3)?.toLongOrNull()

    /** Fields 3 on (state, ppid, …): those after the last ')', since the name may contain spaces or ')'. */
    private fun fieldsAfterName(stat: String): List<String> {
        val close = stat.lastIndexOf(')').takeIf { it >= 0 } ?: return emptyList()
        return stat.substring(close + 1).trim().split(' ')
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

    /** When [pid] started, or null if it's gone (or unreadable). */
    private fun startTime(pid: Int): Long? = runCatching { parseStartTime(File("/proc/$pid/stat").readText()) }.getOrNull()

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
        // Recorded now so the delayed SIGKILL can't hit an unrelated process that reused a pid.
        val started = tree.associateWith(::startTime)
        for (pid in tree) signal(pid, OsConstants.SIGHUP)
        for (pid in tree) signal(pid, OsConstants.SIGTERM)
        if (alsoGroup) runCatching { Os.kill(-root, OsConstants.SIGTERM) }
        thread(isDaemon = true, name = "process-tree-kill") {
            Thread.sleep(graceMs)
            val same = { pid: Int -> startTime(pid).let { it != null && it == started[pid] } }
            val rootSame = same(root)
            // Children started during the grace period count too, while root is still the same process.
            val again = (tree.filter(same) + if (rootSame) descendantsOf(root, snapshot()) else emptyList()).distinct()
            for (pid in again) signal(pid, OsConstants.SIGKILL)
            // Not if root's pid now belongs to another process, which may lead a group of its own.
            if (alsoGroup && (rootSame || startTime(root) == null)) runCatching { Os.kill(-root, OsConstants.SIGKILL) }
            Log.d(TAG, "Stopped process tree of $root (${again.size} processes)")
        }
    }

    /**
     * Stops [pid] alone (SIGTERM, then SIGKILL after [graceMs] if it's still there), but only while
     * it is the process that started at [startTime]: a pid reused since then is never signalled.
     * False when it already changed or is gone.
     */
    fun stopExact(pid: Int, startTime: Long, graceMs: Long = 2_000): Boolean {
        if (pid <= 1 || pid == Os.getpid() || startTime(pid) != startTime) return false
        signal(pid, OsConstants.SIGTERM)
        thread(isDaemon = true, name = "process-stop") {
            Thread.sleep(graceMs)
            if (startTime(pid) == startTime) signal(pid, OsConstants.SIGKILL)
        }
        return true
    }

    /** The pid of a [Process] started by ProcessBuilder (Android's UNIXProcess keeps it in a private field). */
    fun pidOf(process: Process): Int = runCatching {
        val field = process.javaClass.getDeclaredField("pid")
        field.isAccessible = true
        field.getInt(process)
    }.getOrDefault(0)
}
