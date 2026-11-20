package dev.yurlungr.app.runtime

import java.io.File

/**
 * How many packages can be upgraded, remembered between checks. Counting runs `apt list
 * --upgradable` (a bash and an apt start, a few seconds on a phone), and System checks refreshes on
 * every return to the app. The answer only changes when packages are installed or removed (dpkg's
 * status file) or the package lists are refreshed (apt's lists folder), so the count is kept until
 * either changes, or for [MAX_AGE_MS] at most.
 */
object UpgradableCount {
    const val MAX_AGE_MS = 6 * 60 * 60_000L

    internal data class Entry(val count: Int, val at: Long, val key: String)

    @Volatile private var cached: Entry? = null

    /** Changes whenever the answer can: dpkg's database or apt's package lists were written. */
    internal fun key(prefix: File): String =
        "${File(prefix, "var/lib/dpkg/status").lastModified()}:${File(prefix, "var/lib/apt/lists").lastModified()}"

    /**
     * Whether [entry] still answers for [key] at [now]. [allowRun] false (a quick refresh): any
     * count for the same files will do, however old, since counting again is what's being avoided.
     */
    internal fun usable(entry: Entry?, key: String, now: Long, allowRun: Boolean): Boolean =
        entry != null && entry.key == key && (!allowRun || now - entry.at in 0 until MAX_AGE_MS)

    /**
     * The count, from memory when it still holds; otherwise counted, if [allowRun]. Null when it
     * isn't known (not counted yet on a quick refresh, or apt failed).
     */
    suspend fun get(paths: RuntimePaths, allowRun: Boolean): Int? {
        val key = key(paths.prefix)
        cached?.takeIf { usable(it, key, System.currentTimeMillis(), allowRun) }?.let { return it.count }
        if (!allowRun) return null
        val n = CommandRunner.shell(paths, Storage.UPGRADABLE_SCRIPT, timeoutMs = 30_000)
            .output.trim().lines().lastOrNull()?.trim()?.toIntOrNull() ?: return null
        cached = Entry(n, System.currentTimeMillis(), key)
        return n
    }
}
