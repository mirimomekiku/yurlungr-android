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
}
