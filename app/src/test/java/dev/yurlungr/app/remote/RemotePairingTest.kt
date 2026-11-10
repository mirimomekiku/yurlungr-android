package dev.yurlungr.app.remote

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.GeneralSecurityException

class RemotePairingTest {
    private val code = "ABCD-EFGH-JKMN-PQRS-TUVW"
    private val hostNonce = RemotePairing.newNonce()
    private val clientNonce = RemotePairing.newNonce()

    @Test fun `proofs depend on the code but not its formatting`() {
        val proof = RemotePairing.clientProof(code, hostNonce, clientNonce)
        assertTrue(RemotePairing.matches(proof, RemotePairing.clientProof("abcd efgh jkmn pqrs tuvw", hostNonce, clientNonce)))
        assertFalse(RemotePairing.matches(proof, RemotePairing.clientProof("ABCD-EFGH-JKMN-PQRS-TUVX", hostNonce, clientNonce)))
    }

    @Test fun `client and host proofs differ, and are bound to both nonces`() {
        val client = RemotePairing.clientProof(code, hostNonce, clientNonce)
        assertFalse(RemotePairing.matches(client, RemotePairing.hostProof(code, hostNonce, clientNonce)))
        assertFalse(RemotePairing.matches(client, RemotePairing.clientProof(code, RemotePairing.newNonce(), clientNonce)))
        assertFalse(RemotePairing.matches(client, RemotePairing.clientProof(code, hostNonce, RemotePairing.newNonce())))
        assertFalse(RemotePairing.matches(client, null))
    }

    @Test fun `paired sides exchange messages both ways`() {
        val host = RemotePairing.channel(code, hostNonce, clientNonce, isHost = true)
        val client = RemotePairing.channel(code, hostNonce, clientNonce, isHost = false)
        assertEquals("""{"t":"open"}""", host.open(client.seal("""{"t":"open"}""")))
        assertEquals("ünïcode ✓", client.open(host.seal("ünïcode ✓")))
        repeat(3) { assertEquals("n$it", host.open(client.seal("n$it"))) }
    }

    @Test(expected = GeneralSecurityException::class)
    fun `a frame sealed with another code is rejected`() {
        val host = RemotePairing.channel(code, hostNonce, clientNonce, isHost = true)
        val wrong = RemotePairing.channel("ZZZZ-EFGH-JKMN-PQRS-TUVW", hostNonce, clientNonce, isHost = false)
        host.open(wrong.seal("hi"))
    }

    @Test(expected = GeneralSecurityException::class)
    fun `a replayed frame is rejected`() {
        val host = RemotePairing.channel(code, hostNonce, clientNonce, isHost = true)
        val client = RemotePairing.channel(code, hostNonce, clientNonce, isHost = false)
        val frame = client.seal("delete")
        host.open(frame)
        host.open(frame)
    }

    @Test(expected = GeneralSecurityException::class)
    fun `a reflected frame is rejected`() {
        val client = RemotePairing.channel(code, hostNonce, clientNonce, isHost = false)
        client.open(client.seal("echo")) // the host->client key differs from client->host
    }

    @Test(expected = GeneralSecurityException::class)
    fun `a modified frame is rejected`() {
        val host = RemotePairing.channel(code, hostNonce, clientNonce, isHost = true)
        val client = RemotePairing.channel(code, hostNonce, clientNonce, isHost = false)
        val frame = client.seal("send")
        frame[0] = (frame[0].toInt() xor 1).toByte()
        host.open(frame)
    }

    @Test fun `hex round trip and validation`() {
        assertArrayEquals(hostNonce, RemotePairing.fromHex(RemotePairing.toHex(hostNonce), RemotePairing.NONCE_BYTES))
        assertNull(RemotePairing.fromHex("abc", RemotePairing.NONCE_BYTES))
        assertNull(RemotePairing.fromHex("zz".repeat(RemotePairing.NONCE_BYTES), RemotePairing.NONCE_BYTES))
        assertNull(RemotePairing.fromHex(null, 1))
    }

    @Test fun `folder names can't leave the projects folder`() {
        assertTrue(RemoteProtocol.isValidFolder("my-app"))
        assertTrue(RemoteProtocol.isValidFolder(".config"))
        assertFalse(RemoteProtocol.isValidFolder(".."))
        assertFalse(RemoteProtocol.isValidFolder("."))
        assertFalse(RemoteProtocol.isValidFolder("..."))
        assertFalse(RemoteProtocol.isValidFolder("a/b"))
    }

    @Test fun `peers are limited to Tailscale and, if allowed, private LAN ranges`() {
        assertTrue(RemoteProtocol.isAllowedPeer("100.101.1.2", allowLan = false))
        assertFalse(RemoteProtocol.isAllowedPeer("192.168.1.5", allowLan = false))
        assertTrue(RemoteProtocol.isAllowedPeer("192.168.1.5", allowLan = true))
        assertTrue(RemoteProtocol.isAllowedPeer("10.0.0.3", allowLan = true))
        assertTrue(RemoteProtocol.isAllowedPeer("172.20.0.3", allowLan = true))
        assertFalse(RemoteProtocol.isAllowedPeer("172.32.0.3", allowLan = true))
        assertFalse(RemoteProtocol.isAllowedPeer("8.8.8.8", allowLan = true))
        assertFalse(RemoteProtocol.isAllowedPeer("100.128.0.1", allowLan = true))
        assertFalse(RemoteProtocol.isAllowedPeer("fe80::1", allowLan = true))
        assertFalse(RemoteProtocol.isAllowedPeer(null, allowLan = true))
    }
}
