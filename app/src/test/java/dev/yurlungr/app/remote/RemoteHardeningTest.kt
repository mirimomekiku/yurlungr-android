package dev.yurlungr.app.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class RemoteHardeningTest {
    private fun frame(payload: Long, masked: Boolean = true): ByteArray {
        val mask = if (masked) 0x80 else 0
        val head = when {
            payload < 126 -> byteArrayOf(0x81.toByte(), (mask or payload.toInt()).toByte())
            payload <= 0xFFFF -> byteArrayOf(0x81.toByte(), (mask or 126).toByte(), (payload shr 8).toByte(), payload.toByte())
            else -> byteArrayOf(0x81.toByte(), (mask or 127).toByte()) + ByteArray(8) { i -> (payload shr (56 - 8 * i)).toByte() }
        }
        return head + (if (masked) ByteArray(4) else ByteArray(0))
    }

    @Test fun `small frames pass and their payload is skipped`() {
        val g = UnpairedFrameGuard(64 * 1024)
        val hello = frame(300) + ByteArray(300) + frame(10) + ByteArray(10)
        assertTrue(g.accept(ByteBuffer.wrap(hello)))
    }

    @Test fun `an oversized frame is refused from its header alone`() {
        assertFalse(UnpairedFrameGuard(64 * 1024).accept(ByteBuffer.wrap(frame(64L * 1024 * 1024))))
        assertFalse(UnpairedFrameGuard(64 * 1024).accept(ByteBuffer.wrap(frame(70_000))))
        // After a legitimate small one, in the same read.
        assertFalse(UnpairedFrameGuard(64 * 1024).accept(ByteBuffer.wrap(frame(5) + ByteArray(5) + frame(1L shl 40))))
    }

    @Test fun `a header split across reads is still checked`() {
        val g = UnpairedFrameGuard(64 * 1024)
        val big = frame(64L * 1024 * 1024)
        assertTrue(g.accept(ByteBuffer.wrap(big.copyOfRange(0, 3))))
        assertFalse(g.accept(ByteBuffer.wrap(big.copyOfRange(3, big.size))))
    }

    /** Feeds [reads] the way Guarded.decode does during the handshake; false once a frame is refused. */
    private fun handshakeThenFrames(vararg reads: ByteArray): Boolean {
        val end = HandshakeEnd()
        val g = UnpairedFrameGuard(64 * 1024)
        return reads.all { r ->
            val buf = ByteBuffer.wrap(r)
            val frames = end.after(buf)
            assertEquals("the read itself isn't moved", 0, buf.position())
            frames == null || g.accept(frames)
        }
    }

    private val request = "GET / HTTP/1.1\r\nHost: x\r\nUpgrade: websocket\r\n\r\n".toByteArray()

    @Test fun `frames after the handshake are checked even when its blank line is split across reads`() {
        val big = frame(64L * 1024 * 1024)
        // In the same read as the handshake.
        assertFalse(handshakeThenFrames(request + big))
        // The blank line split at every point, the frame header right after it.
        for (cut in request.size - 4..request.size) {
            assertFalse("cut at $cut", handshakeThenFrames(request.copyOfRange(0, cut), request.copyOfRange(cut, request.size) + big))
        }
        // Byte by byte.
        assertFalse(handshakeThenFrames(*(request + big).map { byteArrayOf(it) }.toTypedArray()))
        // Small frames still pass, and a handshake that hasn't ended checks nothing yet.
        assertTrue(handshakeThenFrames(request.copyOfRange(0, 20), request.copyOfRange(20, request.size - 1), byteArrayOf('\n'.code.toByte()) + frame(10) + ByteArray(10)))
        assertTrue(handshakeThenFrames("GET / HTTP/1.1\r\nHost: x\r\n\r".toByteArray()))
    }

    @Test fun `the handshake end matches overlapping carriage returns`() {
        val end = HandshakeEnd()
        assertEquals(null, end.after(ByteBuffer.wrap("a\r\n\r\r".toByteArray())))
        val rest = end.after(ByteBuffer.wrap("\n\r\nXY".toByteArray()))!!
        assertEquals(3, rest.position())
        assertTrue(end.done)
    }

    @Test fun `trusted devices round trip and bad input is ignored`() {
        val list = listOf(TrustedDevice("a1", "Pixel", "ab".repeat(32), 1L, 2L))
        assertEquals(list, RemoteTrust.decode(RemoteTrust.encode(list)))
        assertEquals(emptyList<TrustedDevice>(), RemoteTrust.decode("not json"))
        assertEquals(emptyList<TrustedDevice>(), RemoteTrust.decode("""[{"name":"no id or secret"}]"""))
        assertEquals(64, RemoteTrust.randomHex(RemoteTrust.SECRET_BYTES).length)
    }

    @Test fun `a device secret works as the pairing key`() {
        val secret = RemoteTrust.randomHex(RemoteTrust.SECRET_BYTES)
        val hn = RemotePairing.newNonce(); val cn = RemotePairing.newNonce()
        assertTrue(RemotePairing.matches(RemotePairing.clientProof(secret, hn, cn), RemotePairing.clientProof(secret, hn, cn)))
        assertFalse(RemotePairing.matches(RemotePairing.clientProof("OTHER-CODE", hn, cn), RemotePairing.clientProof(secret, hn, cn)))
        val host = RemotePairing.channel(secret, hn, cn, isHost = true)
        val client = RemotePairing.channel(secret, hn, cn, isHost = false)
        assertEquals("hi", host.open(client.seal("hi")))
    }
}
