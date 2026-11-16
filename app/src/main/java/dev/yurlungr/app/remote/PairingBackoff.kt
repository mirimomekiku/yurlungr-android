package dev.yurlungr.app.remote

/**
 * Failed pairing attempts per address, with exponential backoff: after a wrong code (or a bad
 * or timed-out hello) the address is refused for [baseMs], then twice that after the next
 * failure, up to [maxMs]. A successful pairing clears it. Guessing the code from one address
 * is slowed to a crawl, and a flood can't keep the pairing slots full. Thread-safe: checked on
 * the socket thread, updated on the main thread.
 */
class PairingBackoff(
    private val clock: () -> Long = System::currentTimeMillis,
    private val baseMs: Long = 2_000,
    private val maxMs: Long = 5 * 60_000,
    /** Entries kept at most (oldest dropped), so spoofed-looking address churn can't grow it. */
    private val maxEntries: Int = 256,
    /** A failure this long after the previous one starts the count again. */
    private val forgetAfterMs: Long = 60 * 60_000,
) {
    private class Entry(var failures: Int, var until: Long, var last: Long)

    private val entries = LinkedHashMap<String, Entry>()

    /** How long [address] must still wait, in ms (0 = it may try). */
    @Synchronized
    fun remainingMs(address: String?): Long {
        val e = entries[address ?: return 0] ?: return 0
        return (e.until - clock()).coerceAtLeast(0)
    }

    fun isBlocked(address: String?): Boolean = remainingMs(address) > 0

    @Synchronized
    fun failed(address: String?) {
        address ?: return
        val now = clock()
        val e = entries.remove(address)?.takeIf { now - it.last < forgetAfterMs } ?: Entry(0, 0, now)
        e.failures++
        e.last = now
        val shift = (e.failures - 1).coerceAtMost(30)
        e.until = now + (baseMs shl shift).coerceIn(baseMs, maxMs)
        entries[address] = e // re-inserted: newest last
        while (entries.size > maxEntries) entries.remove(entries.keys.first())
    }

    @Synchronized
    fun succeeded(address: String?) {
        entries.remove(address ?: return)
    }

    @Synchronized
    fun failures(address: String?): Int = entries[address]?.failures ?: 0
}
