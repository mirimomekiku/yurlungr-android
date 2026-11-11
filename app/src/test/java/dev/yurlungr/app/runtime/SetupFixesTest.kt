package dev.yurlungr.app.runtime

import dev.yurlungr.app.chat.Checkpoints
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SetupFixesTest {
    @Test fun `app package actions take the setup lock and keep the tab's shell`() {
        val cmd = SetupLock.wrap("apt-get clean")
        assertTrue(cmd.contains("flock -n -E ${SetupLock.BUSY} \"\$PREFIX/var/lock/yurlungr-setup.lock\" bash -c 'apt-get clean'"))
        assertTrue("a subshell, so `exit` doesn't end a terminal tab", cmd.startsWith("(") && cmd.endsWith(")"))
        assertTrue(SetupLock.wrap("echo 'quoted'").contains("bash -c 'echo '\\''quoted'\\'''"))
        assertTrue(packagesUpgradeCommand().contains("full-upgrade") && packagesUpgradeCommand().contains("flock"))
    }

    @Test fun `setup steps time out as a group and keep edited config files`() {
        val common = File("src/main/assets/setup-common.sh").readText()
        assertFalse("--foreground signals only the first process", common.contains("timeout --foreground"))
        assertTrue(common.contains("stop_steps"))
        assertFalse(common.contains("force-confnew"))
        assertTrue(common.contains("run_logged with_timeout 1800 apt-get install"))
        assertTrue(common.contains("Acquire::http::Timeout"))
    }

    @Test fun `probes report a silently missing tool as not installed`() {
        val script = Toolchain.probeScript(listOf(Toolchain.tools.first { it.id == "nc" }))
        assertTrue(script.contains("s=127"))
    }

    @Test fun `NUL-separated name-status is read as pairs, odd names intact`() {
        val out = "M\u0000src/café.kt\u0000A\u0000a b/\"quoted\".txt\u0000D\u0000*.kt\u0000"
        assertEquals(listOf('M' to "src/café.kt", 'A' to "a b/\"quoted\".txt", 'D' to "*.kt"), Checkpoints.parseNameStatusZ(out))
        assertEquals(emptyList<Pair<Char, String>>(), Checkpoints.parseNameStatusZ(""))
    }
}
