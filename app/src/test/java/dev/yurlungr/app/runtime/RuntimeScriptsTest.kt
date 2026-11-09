package dev.yurlungr.app.runtime

import org.junit.Assert.*
import org.junit.Test

class RuntimeScriptsTest {
    @Test fun `Windows and mixed line endings and BOM are repaired`() {
        assertEquals("#!/bin/bash\nset -u\necho ok\n", normalizeRuntimeScript("\uFEFF#!/bin/bash\r\nset -u\recho ok"))
    }

    @Test fun `Unix scripts including literal shell escapes are preserved`() {
        val script = "#!/bin/bash\nprintf '%s' '\\r'\n"
        assertEquals(script, normalizeRuntimeScript(script))
        assertEquals(script, normalizeRuntimeScript(normalizeRuntimeScript(script)))
    }

    @Test fun `empty and binary scripts are rejected`() {
        for (text in listOf("", "\uFEFF\r\n", "echo ok\u0000")) {
            assertTrue(runCatching { normalizeRuntimeScript(text) }.isFailure)
        }
    }

    @Test fun `setup parses before executing and preserves SDK test argument`() {
        assertEquals("bash -n \"\$PREFIX/libexec/yurlungr/agents-setup.sh\" && bash \"\$PREFIX/libexec/yurlungr/agents-setup.sh\"", setupScriptCommand("agents-setup.sh"))
        assertTrue(setupScriptCommand("android-sdk-setup.sh", test = true).endsWith(" --test"))
        assertTrue(runCatching { setupScriptCommand("../other.sh") }.isFailure)
    }
}
