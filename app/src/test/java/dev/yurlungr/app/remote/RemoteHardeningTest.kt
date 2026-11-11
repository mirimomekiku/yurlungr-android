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
