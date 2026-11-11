package dev.yurlungr.app.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class JdksTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun jdk(prefix: File, v: Int) = File(prefix, "lib/jvm/java-$v-openjdk/bin/java").apply { parentFile.mkdirs(); writeText("") }

    @Test fun `installed JDKs are found by folder, newest is the fallback default`() {
        val prefix = tmp.newFolder("usr")
        assertNull(Jdks.current(prefix))
        jdk(prefix, 21); jdk(prefix, 17)
        File(prefix, "lib/jvm/java-21-openjdk-x").mkdirs() // not a JDK home
        assertEquals(listOf(17, 21), Jdks.installed(prefix).map { it.version })
        assertEquals(21, Jdks.current(prefix)?.version)
    }

    @Test fun `manual or automatic comes from dpkg's alternatives`() {
        val prefix = tmp.newFolder("usr")
        val alt = File(prefix, "var/lib/dpkg/alternatives/java").apply { parentFile.mkdirs() }
        alt.writeText("auto\n/x/bin/java\n")
        assertFalse(Jdks.isManual(prefix))
        alt.writeText("manual\n/x/bin/java\n")
        assertTrue(Jdks.isManual(prefix))
    }

    @Test fun `switching uses update-alternatives on the JDK's java`() {
        val prefix = File("/p")
        val jdk = Jdk(17, File("/p/lib/jvm/java-17-openjdk"))
        assertEquals(listOf(File("/p/bin/update-alternatives").path, "--set", "java", File("/p/lib/jvm/java-17-openjdk/bin/java").path), Jdks.useCommand(prefix, jdk))
        assertEquals("--auto", Jdks.autoCommand(prefix)[1])
    }

    @Test fun `gradle properties list every JDK, keep other lines, and are stable`() {
        val jdks = listOf(Jdk(17, File("/j/17")), Jdk(21, File("/j/21")))
        val before = "android.aapt2FromMavenOverride=/x/aapt2\norg.gradle.jvmargs=-Xmx2g\norg.gradle.java.installations.paths=/old\n"
        val after = Jdks.withGradleToolchains(before, jdks)
        assertTrue(after.contains("android.aapt2FromMavenOverride=/x/aapt2\n"))
        assertTrue(after.contains("org.gradle.jvmargs=-Xmx2g\n"))
        assertTrue(after.contains("${Jdks.GRADLE_PATHS}=${File("/j/17").path},${File("/j/21").path}\n"))
        assertTrue(after.contains("${Jdks.GRADLE_AUTO_DOWNLOAD}=false\n"))
        assertFalse(after.contains("/old"))
        assertEquals(after, Jdks.withGradleToolchains(after, jdks))
        assertEquals("${Jdks.GRADLE_PATHS}=${File("/j/17").path},${File("/j/21").path}\n${Jdks.GRADLE_AUTO_DOWNLOAD}=false\n", Jdks.withGradleToolchains("", jdks))
    }

    @Test fun `user tool folders are on PATH, after the runtime, and profile loading is silent`() {
        assertTrue(RuntimeEnv.USER_BINS.containsAll(listOf(".local/bin", ".cargo/bin", "go/bin", ".bun/bin", ".dotnet/tools", RuntimeEnv.PNPM_HOME)))
        assertTrue(RuntimeEnv.PROFILE_SNIPPET.contains(">/dev/null 2>&1"))
    }

    @Test fun `JVM tools get a longer probe, and the probe uses each tool's own limit`() {
        val kotlinc = Toolchain.tools.first { it.id == "kotlinc" }
        assertEquals(Toolchain.SLOW_PROBE_S, kotlinc.timeoutS)
        val script = Toolchain.probeScript(listOf(kotlinc, Toolchain.tools.first { it.id == "git" }))
        assertTrue(script.contains("timeout -k 5 ${Toolchain.SLOW_PROBE_S} bash"))
        assertTrue(script.contains("timeout -k 5 ${Toolchain.PROBE_TIMEOUT_S} bash"))
        assertTrue(Toolchain.probeBudgetMs(4, Toolchain.SLOW_PROBE_S) > Toolchain.probeBudgetMs(4))
    }

    @Test fun `the SDK and the Java pack use JDK 21, which Termux's JVM tools need`() {
        assertTrue("openjdk-21" in Toolchain.SDK_PACKAGES)
        assertFalse("openjdk-17" in Toolchain.SDK_PACKAGES)
        assertTrue("openjdk-21" in ToolSetup.Jvm.packages)
    }
}
