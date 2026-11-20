package dev.yurlungr.app.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TunnelSessionsTest {
    private val banner = """
        2026-11-16T10:00:00Z INF Thank you for trying Cloudflare Tunnel.
        2026-11-16T10:00:01Z INF Requesting new quick Tunnel on trycloudflare.com...
        2026-11-16T10:00:02Z INF |  https://quiet-river-lamp-4242.trycloudflare.com                                      |
    """.trimIndent()

    @Test fun `the command only targets the loopback address`() {
        assertEquals("exec cloudflared tunnel --no-autoupdate --url http://127.0.0.1:5173", TunnelSessions.command(5173))
        assertTrue(runCatching { TunnelSessions.command(0) }.isFailure)
        assertTrue(runCatching { TunnelSessions.command(70000) }.isFailure)
    }

    @Test fun `the link is read from cloudflared's output once printed`() {
        assertNull(TunnelSessions.parseUrl("INF Requesting new quick Tunnel…"))
        assertEquals("https://quiet-river-lamp-4242.trycloudflare.com", TunnelSessions.parseUrl(banner))
        // Other hosts mentioned in the log are not links to share.
        assertNull(TunnelSessions.parseUrl("see https://developers.cloudflare.com/cloudflare-one/ for docs"))
    }

    @Test fun `the link counts as live only after the edge registers the tunnel`() {
        assertFalse(TunnelSessions.isRegistered(banner))
        assertTrue(TunnelSessions.isRegistered(banner + "\n2026-11-16T10:00:05Z INF Registered tunnel connection connIndex=0"))
    }

    @Test fun `a failure shows the error line without its timestamp and level`() {
        val out = "2026-11-16T10:00:00Z INF Requesting new quick Tunnel\n2026-11-16T10:00:03Z ERR failed to request quick Tunnel: Post \"https://api.trycloudflare.com\": dial tcp: lookup failed\nexiting"
        val line = TunnelSessions.failureLine(out)
        assertTrue(line, line.startsWith("failed to request quick Tunnel"))
        assertEquals("cloudflared exited", TunnelSessions.failureLine(""))
    }

    @Test fun `access keys are long, random and what the supervisor accepts`() {
        val a = TunnelLinks.newKey()
        val b = TunnelLinks.newKey()
        assertTrue(a, Regex("^[A-Za-z0-9_-]{16,128}$").matches(a))
        assertEquals(32, a.length)
        assertTrue(a != b)
    }

    @Test fun `the shared link carries the key, and a link without one is left alone`() {
        assertEquals("https://calm-river.trycloudflare.com/?key=abc", TunnelLinks.withKey("https://calm-river.trycloudflare.com", "abc"))
        assertEquals("https://calm-river.trycloudflare.com/?key=abc", TunnelLinks.withKey("https://calm-river.trycloudflare.com/", "abc"))
        assertEquals("https://calm-river.trycloudflare.com", TunnelLinks.withKey("https://calm-river.trycloudflare.com", null))
        val gated = Tunnel(5173, "Vite · shop", key = "k").apply { url = "https://x.trycloudflare.com" }
        assertEquals("https://x.trycloudflare.com/?key=k", gated.shareUrl)
        assertNull(Tunnel(5173, "Vite · shop").shareUrl)
    }

    @Test fun `links expire after the chosen time, or never`() {
        val live = 1_000_000L
        assertEquals(live + 3_600_000L, TunnelLinks.expiresAt(live, TunnelLifetime.Hour1))
        assertEquals(live + 15 * 60_000L, TunnelLinks.expiresAt(live, TunnelLifetime.Minutes15))
        assertEquals(live + 4 * 3_600_000L, TunnelLinks.expiresAt(live, TunnelLifetime.Hours4))
        assertNull(TunnelLinks.expiresAt(live, TunnelLifetime.UntilStopped))
        val ends = live + 3_600_000L
        assertFalse(TunnelLinks.expired(ends - 1, ends))
        assertTrue(TunnelLinks.expired(ends, ends))
        assertFalse("until stopped never expires", TunnelLinks.expired(Long.MAX_VALUE, null))
    }

    @Test fun `time left reads naturally and rounds up`() {
        val ends = 10_000_000L
        assertEquals("1 h left", TunnelLinks.remaining(ends - 3_600_000L, ends))
        assertEquals("1 h 5 min left", TunnelLinks.remaining(ends - 65 * 60_000L, ends))
        assertEquals("45 min left", TunnelLinks.remaining(ends - 45 * 60_000L, ends))
        assertEquals("45 min left", TunnelLinks.remaining(ends - 44 * 60_000L - 1, ends))
        assertEquals("Less than a minute left", TunnelLinks.remaining(ends - 59_000L, ends))
        assertEquals("4 h left", TunnelLinks.remaining(ends - 4 * 3_600_000L, ends))
        assertNull(TunnelLinks.remaining(ends, null))
    }

    @Test fun `the notification names what's shared and when it ends`() {
        assertNull(TunnelNotifications.describe(emptyList(), 0))
        val now = 5_000_000L
        val one = Tunnel(5173, "Vite · shop").apply { state = TunnelState.Live; expiresAt = now + 30 * 60_000L }
        assertEquals("Public link on · port 5173" to "Vite · shop · 30 min left", TunnelNotifications.describe(listOf(one), now))
        val forever = Tunnel(8000, "Django · api").apply { state = TunnelState.Live }
        assertEquals("2 public links on" to "Vite · shop, Django · api · 30 min left", TunnelNotifications.describe(listOf(one, forever), now))
        assertEquals("Public link on · port 8000" to "Django · api · Until you stop it", TunnelNotifications.describe(listOf(forever), now))
        val starting = Tunnel(3000, "Next · blog")
        assertEquals("Next · blog · Connecting", TunnelNotifications.describe(listOf(starting), now)!!.second)
    }

    @Test fun `the API host in an error is not taken for the public link`() {
        val err = "ERR failed to request quick Tunnel: Post \"https://api.trycloudflare.com/tunnel\": dial tcp"
        assertNull(TunnelSessions.parseUrl(err))
        assertEquals("https://calm-river-bird.trycloudflare.com", TunnelSessions.parseUrl(err + "\n|  https://calm-river-bird.trycloudflare.com  |"))
    }
}
