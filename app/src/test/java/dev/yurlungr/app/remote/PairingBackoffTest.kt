package dev.yurlungr.app.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingBackoffTest {
    private var now = 1_000_000L
    private val backoff = PairingBackoff(clock = { now }, baseMs = 2_000, maxMs = 60_000, maxEntries = 3, forgetAfterMs = 600_000)

    @Test fun `each failure doubles the wait, up to the cap`() {
        assertFalse(backoff.isBlocked("10.0.0.2"))
        backoff.failed("10.0.0.2")
        assertEquals(2_000, backoff.remainingMs("10.0.0.2"))
        now += 2_000
        assertFalse(backoff.isBlocked("10.0.0.2"))
        backoff.failed("10.0.0.2")
        assertEquals(4_000, backoff.remainingMs("10.0.0.2"))
        repeat(10) { backoff.failed("10.0.0.2") }
        assertEquals(60_000, backoff.remainingMs("10.0.0.2"))
        // Other addresses are unaffected.
        assertFalse(backoff.isBlocked("10.0.0.3"))
        assertFalse(backoff.isBlocked(null))
    }

    @Test fun `success clears it and a long quiet spell starts over`() {
        repeat(3) { backoff.failed("a") }
        backoff.succeeded("a")
        assertFalse(backoff.isBlocked("a"))
        assertEquals(0, backoff.failures("a"))

        repeat(3) { backoff.failed("b") }
        now += 600_000
        backoff.failed("b")
        assertEquals(1, backoff.failures("b"))
        assertEquals(2_000, backoff.remainingMs("b"))
    }

    @Test fun `the table stays bounded, dropping the oldest`() {
        listOf("a", "b", "c", "d").forEach(backoff::failed)
        assertFalse(backoff.isBlocked("a"))
        assertTrue(backoff.isBlocked("b"))
        assertTrue(backoff.isBlocked("d"))
    }
}
