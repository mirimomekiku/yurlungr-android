package dev.yurlungr.app.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BootstrapPinTest {
    private val a = "a".repeat(64)
    private val b = "b".repeat(64)
    private val pins = mapOf("bootstrap-pinned" to mapOf("aarch64" to a))

    @Test fun `a pinned release must match its pin, whatever the API says`() {
        assertEquals(a, BootstrapInstaller.expectedSha256("bootstrap-pinned", "aarch64", b, pins))
        assertEquals(a, BootstrapInstaller.expectedSha256("bootstrap-pinned", "aarch64", null, pins))
        assertThrows(IllegalStateException::class.java) { BootstrapInstaller.expectedSha256("bootstrap-pinned", "arm", a, pins) }
    }

    @Test fun `an unknown release needs a published digest`() {
        assertEquals(b, BootstrapInstaller.expectedSha256("bootstrap-new", "aarch64", b.uppercase(), pins))
        assertThrows(IllegalStateException::class.java) { BootstrapInstaller.expectedSha256("bootstrap-new", "aarch64", null, pins) }
        assertThrows(IllegalStateException::class.java) { BootstrapInstaller.expectedSha256("bootstrap-new", "aarch64", "", pins) }
        assertThrows(IllegalStateException::class.java) { BootstrapInstaller.expectedSha256("bootstrap-new", "aarch64", "z".repeat(64), pins) }
    }

    @Test fun `the shipped pins are well formed`() {
        assertTrue(BootstrapInstaller.BOOTSTRAP_PINS.isNotEmpty())
        BootstrapInstaller.BOOTSTRAP_PINS.forEach { (tag, byArch) ->
            assertTrue(tag, tag.startsWith("bootstrap-") && tag.contains("+apt"))
            assertEquals(setOf("aarch64", "arm", "i686", "x86_64"), byArch.keys)
            byArch.values.forEach { assertTrue(it, Regex("[0-9a-f]{64}").matches(it)) }
        }
    }
}
