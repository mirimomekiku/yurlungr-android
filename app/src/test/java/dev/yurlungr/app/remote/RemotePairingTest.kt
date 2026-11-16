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

    @Test fun `peers are limited to Tailscale and, if allowed, this phone's own local subnets`() {
        val none = emptyList<RemoteProtocol.Subnet>()
        assertTrue(RemoteProtocol.isAllowedPeer("100.101.1.2", none))
        assertFalse(RemoteProtocol.isAllowedPeer("192.168.1.5", none))
        assertFalse(RemoteProtocol.isAllowedPeer("10.0.0.3", none))

        val wifi = RemoteProtocol.lanSubnets(listOf("wlan0" to listOf("192.168.1.20" to 24)))
        assertTrue(RemoteProtocol.isAllowedPeer("192.168.1.5", wifi))
        assertTrue(RemoteProtocol.isAllowedPeer("100.101.1.2", wifi))
        assertFalse(RemoteProtocol.isAllowedPeer("192.168.2.5", wifi))
        // Other private ranges aren't trusted just for being private.
        assertFalse(RemoteProtocol.isAllowedPeer("10.0.0.3", wifi))
        assertFalse(RemoteProtocol.isAllowedPeer("172.20.0.3", wifi))
        assertFalse(RemoteProtocol.isAllowedPeer("8.8.8.8", wifi))
        assertFalse(RemoteProtocol.isAllowedPeer("100.128.0.1", wifi))
        assertFalse(RemoteProtocol.isAllowedPeer("fe80::1", wifi))
        assertFalse(RemoteProtocol.isAllowedPeer(null, wifi))
        assertFalse(RemoteProtocol.isAllowedPeer("192.168.1.256", wifi))
    }

    @Test fun `local subnets leave out cellular, VPN and public interfaces`() {
        val subnets = RemoteProtocol.lanSubnets(listOf(
            "wlan0" to listOf("10.1.2.3" to 24),
            "rmnet_data0" to listOf("10.200.0.7" to 8),
            "ccmni1" to listOf("10.64.1.1" to 30),
            "v4-rmnet_data0" to listOf("192.0.0.4" to 32),
            "tun0" to listOf("10.8.0.2" to 24),
            "ap0" to listOf("192.168.43.1" to 24),
            "eth0" to listOf("203.0.113.5" to 24),
            // A /8 on Wi-Fi is narrowed to the phone's /16.
            "swlan0" to listOf("172.16.5.1" to 8),
        ))
        assertTrue(RemoteProtocol.isAllowedPeer("10.1.2.99", subnets))
        assertFalse(RemoteProtocol.isAllowedPeer("10.1.3.99", subnets))
        assertFalse(RemoteProtocol.isAllowedPeer("10.200.0.8", subnets))
        assertFalse(RemoteProtocol.isAllowedPeer("10.64.1.2", subnets))
        assertFalse(RemoteProtocol.isAllowedPeer("10.8.0.3", subnets))
        assertTrue(RemoteProtocol.isAllowedPeer("192.168.43.50", subnets))
        assertFalse(RemoteProtocol.isAllowedPeer("203.0.113.6", subnets))
        assertTrue(RemoteProtocol.isAllowedPeer("172.16.200.1", subnets))
        assertFalse(RemoteProtocol.isAllowedPeer("172.17.0.1", subnets))
    }

    @Test fun `remote config toggles that turn approvals off are recognised`() {
        assertTrue(RemoteProtocol.isRiskyConfigToggle("autoApprove", null))
        assertTrue(RemoteProtocol.isRiskyConfigToggle("auto_approve_edits", "Auto-approve edits"))
        assertTrue(RemoteProtocol.isRiskyConfigToggle("opt1", "Skip permission prompts"))
        assertTrue(RemoteProtocol.isRiskyConfigToggle("bypassPermissions", null))
        assertTrue(RemoteProtocol.isRiskyConfigToggle("yolo", null))
        assertTrue(RemoteProtocol.isRiskyConfigToggle("dangerously-skip", null))
        assertTrue(RemoteProtocol.isRiskyConfigToggle("x", "Require approval"))
        assertTrue(RemoteProtocol.isRiskyConfigToggle("sandbox", "Sandbox"))
        assertFalse(RemoteProtocol.isRiskyConfigToggle("thinking", "Extended thinking"))
        assertFalse(RemoteProtocol.isRiskyConfigToggle("fast", "Fast mode"))
    }
}
