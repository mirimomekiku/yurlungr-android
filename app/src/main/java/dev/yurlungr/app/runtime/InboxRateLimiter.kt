package dev.yurlungr.app.runtime

/**
 * At most [max] requests per [windowMs] for each kind (open, install, notify, done), sliding
 * window. Runtime processes can write to the inbox as fast as they like; extras are dropped so
 * a loop can't flood notifications, browser tabs or install prompts. Thread-safe.
 */
class InboxRateLimiter(
    private val max: Int = 10,
    private val windowMs: Long = 10_000,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val recent = HashMap<String, ArrayDeque<Long>>()

    /** True if a request of [kind] may go ahead now (and counts it). */
    @Synchronized
    fun tryAcquire(kind: String): Boolean {
        val now = clock()
        val times = recent.getOrPut(kind) { ArrayDeque() }
        while (times.isNotEmpty() && now - times.first() >= windowMs) times.removeFirst()
        if (times.size >= max) return false
        times.addLast(now)
        return true
    }
}
