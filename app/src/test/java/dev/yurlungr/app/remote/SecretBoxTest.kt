package dev.yurlungr.app.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

class SecretBoxTest {
    private fun softwareKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val key = softwareKey()
    private val box = SecretBox({ key })

    @Test fun `sealed values round trip and never contain the secret`() {
        val secret = "ABCD-EFGH-JKMN-PQRS-TUVW"
        val sealed = box.seal(secret)
        assertTrue(sealed.startsWith(SecretBox.PREFIX))
        assertFalse(sealed.contains(secret))
        assertEquals(secret, box.open(sealed))
        // A fresh IV each time.
        assertNotEquals(sealed, box.seal(secret))
        assertEquals("", box.open(box.seal("")))
    }

    @Test fun `plaintext from older versions is read and migrated`() {
        val r = box.read("ab".repeat(32))
        assertEquals("ab".repeat(32), r.value)
        val rewrite = r.rewrite!!
        assertTrue(box.isSealed(rewrite))
        assertEquals("ab".repeat(32), box.open(rewrite))
        // Already sealed: nothing to rewrite.
        assertNull(box.read(rewrite).rewrite)
        assertEquals(SecretBox.Read(null, null), box.read(null))
    }

    @Test fun `a sealed value under another key or tampered with does not open`() {
        val sealed = box.seal("secret")
        assertNull(SecretBox({ softwareKey() }).open(sealed))
        val blob = kotlin.io.encoding.Base64.decode(sealed.removePrefix(SecretBox.PREFIX))
        blob[blob.size - 1] = (blob[blob.size - 1].toInt() xor 1).toByte()
        assertNull(box.open(SecretBox.PREFIX + kotlin.io.encoding.Base64.encode(blob)))
        assertNull(box.open(SecretBox.PREFIX + "not base64!"))
        assertNull(box.open(SecretBox.PREFIX))
    }

    @Test fun `an unavailable keystore keeps working unsealed and migrates later`() {
        var available = false
        val logged = mutableListOf<String>()
        val flaky = SecretBox({ if (available) key else throw IllegalStateException("no keystore") }) { m, _ -> logged += m }
        assertEquals("code", flaky.seal("code"))
        assertTrue(logged.isNotEmpty())
        val r = flaky.read("code")
        assertEquals("code", r.value)
        assertNull(r.rewrite)
        available = true
        val later = flaky.read("code")
        assertEquals("code", later.value)
        assertEquals("code", box.open(later.rewrite!!))
    }

    @Test fun `trusted device secrets are migrated and unreadable ones dropped`() {
        val plain = TrustedDevice("a", "Pixel", "ab".repeat(32), 1, 2)
        val sealed = TrustedDevice("b", "Galaxy", box.seal("cd".repeat(32)), 1, 2)
        val lost = TrustedDevice("c", "Old", SecretBox({ softwareKey() }).seal("ef".repeat(32)), 1, 2)
        val (list, migrate) = RemoteTrust.openAll(listOf(plain, sealed, lost), box)
        assertTrue(migrate)
        assertEquals(listOf(plain, sealed.copy(secret = "cd".repeat(32))), list)
        assertFalse(RemoteTrust.openAll(listOf(sealed), box).second)
    }

    @Test fun `saved hosts seal codes and secrets and migrate plaintext`() {
        val legacy = """[{"id":"h1","name":"Host","address":"100.64.0.1","code":"ABCD-EFGH","deviceId":"d1","secret":"${"ab".repeat(32)}"}]"""
        val (hosts, migrate) = RemoteHosts.decode(legacy, box)
        assertTrue(migrate)
        val host = RemoteHost("h1", "Host", "100.64.0.1", "ABCD-EFGH", "d1", "ab".repeat(32))
        assertEquals(listOf(host), hosts)
        val stored = RemoteHosts.encode(hosts, box)
        assertFalse(stored.contains("ABCD-EFGH"))
        assertFalse(stored.contains("ab".repeat(32)))
        assertEquals(listOf(host) to false, RemoteHosts.decode(stored, box))
    }
}
