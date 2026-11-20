package dev.yurlungr.app.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit

class RuntimeFixesTest {
    @get:Rule val tmp = TemporaryFolder()

    // ---- Notifications ----------------------------------------------------------------------

    @Test fun `runtime notices cycle through a few ids, never more`() {
        val ids = (0 until 100).map { NoticeIds.pooled(NoticeIds.RUNTIME_BASE, NoticeIds.RUNTIME_POOL, it) }.toSet()
        assertEquals(NoticeIds.RUNTIME_POOL, ids.size)
        assertTrue(NoticeIds.RUNTIME_POOL <= 5)
        // A wrapped-around counter stays in the pool.
        assertTrue(NoticeIds.pooled(NoticeIds.RUNTIME_BASE, NoticeIds.RUNTIME_POOL, Int.MIN_VALUE) in ids)
        // Pools don't overlap each other.
        val pools = listOf(NoticeIds.RUNTIME_BASE, NoticeIds.OPEN_BASE, NoticeIds.INSTALL_BASE, NoticeIds.FILE_BASE)
            .map { base -> (0 until 10).map { NoticeIds.pooled(base, maxOf(NoticeIds.RUNTIME_POOL, NoticeIds.PROMPT_POOL), it) }.toSet() }
        for (i in pools.indices) for (j in pools.indices) if (i != j) assertTrue(pools[i].intersect(pools[j]).isEmpty())
        // Runtime service's notification id is 1.
        assertFalse(1 in ids)
    }

    @Test fun `finished-command notices are one per terminal tab`() {
        assertEquals(NoticeIds.done("a"), NoticeIds.done("a"))
        assertTrue(NoticeIds.done("a") != NoticeIds.done("b"))
    }

    // ---- Android's process killer -----------------------------------------------------------

    @Test fun `signal 9 exits say Android killed the process`() {
        assertEquals(PhantomHint.MESSAGE, PhantomHint.forExit(137))
        assertEquals(PhantomHint.MESSAGE, PhantomHint.forExit(-9))
        for (code in listOf(0, 1, 9, 124, 129, 130, 143)) assertNull(PhantomHint.forExit(code))
        assertTrue(PhantomHint.MESSAGE.contains(PhantomHint.CHECK_TITLE.lowercase()))
        // Quoted in single quotes in the terminal's exit line.
        assertFalse(PhantomHint.MESSAGE.contains('\''))
    }

    @Test fun `a setup killed by signal 9 says so`() {
        val killed = SetupStatus(137, "/log", 0, listOf("nodejs"))
        assertTrue(SetupStatus.summary(killed).startsWith(PhantomHint.MESSAGE))
        assertTrue(SetupStatus.summary(SetupStatus(1, "/log", 0, listOf("nodejs"))).startsWith("Failed: nodejs"))
        val common = File("src/main/assets/setup-common.sh").readText()
        assertTrue(common.contains("SIGKILLED=1"))
        assertTrue(common.contains("exit 137"))
    }

    // ---- Base system -------------------------------------------------------------------------

    @Test fun `without the API the newest pinned release is used, by its plain link and pin`() {
        val pins = mapOf(
            "bootstrap-2026.10.25-r1+apt.android-7" to mapOf("aarch64" to "a".repeat(64)),
            "bootstrap-2026.11.15-r1+apt.android-7" to mapOf("aarch64" to "b".repeat(64), "arm" to "c".repeat(64)),
            "bootstrap-2026.11.08-r1+apt.android-7" to mapOf("aarch64" to "d".repeat(64)),
        )
        val r = BootstrapInstaller.pinnedFallback("aarch64", pins)!!
        assertEquals("bootstrap-2026.11.15-r1+apt.android-7", r.tag)
        assertEquals("https://github.com/termux/termux-packages/releases/download/bootstrap-2026.11.15-r1%2Bapt.android-7/bootstrap-aarch64.zip", r.url)
        assertEquals("b".repeat(64), r.sha256)
        // The pin is what it's checked against.
        assertEquals("b".repeat(64), BootstrapInstaller.expectedSha256(r.tag, "aarch64", r.sha256, pins))
        assertNull(BootstrapInstaller.pinnedFallback("x86_64", pins))
        // The real pins cover every architecture the app installs on.
        for (arch in listOf("aarch64", "arm", "i686", "x86_64")) assertNotNull(BootstrapInstaller.pinnedFallback(arch))
    }

    @Test fun `old prefixes are only the app's own leftovers`() {
        assertEquals("usr-old-", BootstrapInstaller.OLD_PREFIX)
        // Never the live prefix or its staging folder.
        assertFalse("usr".startsWith(BootstrapInstaller.OLD_PREFIX))
        assertFalse("usr-staging".startsWith(BootstrapInstaller.OLD_PREFIX))
    }

    // ---- Restoring after a reinstall --------------------------------------------------------

    private val status = """
        Package: bash
        Status: install ok installed
        Installed-Size: 100

        Package: nodejs
        Status: install ok installed

        Package: libuv
        Status: install ok installed

        Package: golang
        Status: deinstall ok config-files

        Package: ripgrep
        Status: install ok installed
    """.trimIndent()

    private val extended = """
        Package: libuv
        Architecture: aarch64
        Auto-Installed: 1

        Package: ripgrep
        Architecture: aarch64
        Auto-Installed: 0
    """.trimIndent()

    @Test fun `the saved list has hand-installed packages, npm globals, setups and agents`() {
        val saved = RestoreList.snapshot(status, extended, listOf("@openai/codex", "npm", "corepack", "typescript", "Bad Name"),
            setOf("agents-setup", "dev-tools-setup", "python-setup", "agent-install-goose", "android-sdk-setup"))
        assertEquals(listOf("bash", "nodejs", "ripgrep"), saved.filter { it.first == "pkg" }.map { it.second })
        assertEquals(listOf("@openai/codex", "typescript"), saved.filter { it.first == "npm" }.map { it.second })
        // Plain package packs (python) are covered by the packages; dev tools too.
        assertEquals(listOf("agents-setup", "android-sdk-setup"), saved.filter { it.first == "setup" }.map { it.second })
        assertEquals(listOf("goose"), saved.filter { it.first == "agent" }.map { it.second })
        assertEquals(saved, RestoreList.parse(saved.joinToString("\n") { "${it.first} ${it.second}" } + "\nnonsense line here\n"))
    }

    @Test fun `only what's still missing is offered`() {
        val saved = RestoreList.parse("pkg bash\npkg nodejs\npkg ripgrep\nnpm typescript\nsetup agents-setup\nagent goose\nagent unknown\n")
        val plan = RestoreList.plan(saved, installed = setOf("bash"), npmInstalled = emptySet(), completed = setOf("agent-install-goose"))
        assertEquals(listOf("nodejs", "ripgrep"), plan.packages)
        assertEquals(listOf("typescript"), plan.npm)
        assertEquals(listOf(ToolSetup.Agents), plan.setups)
        assertEquals(emptyList<String>(), plan.agents)
        assertEquals(3, plan.count)
        val done = RestoreList.plan(saved, setOf("bash", "nodejs", "ripgrep"), setOf("typescript"), setOf("agents-setup", "agent-install-goose"))
        assertTrue(done.isEmpty)
    }

    @Test fun `the restore command runs the packages first, then setups and agents, and stops on Ctrl-C`() {
        val plan = RestoreList.Plan(listOf("nodejs"), emptyList(), listOf(ToolSetup.Agents), listOf("goose"))
        val cmd = RestoreList.command(File("/data/data/com.termux/files/${RestoreList.FILE}"), plan)
        val order = listOf("restore-packages.sh", "agents-setup.sh", "agent-install.sh\" goose").map { cmd.lastIndexOf(it) }
        assertEquals(order.sorted(), order)
        assertTrue(cmd.contains("bash -n \"\$PREFIX/libexec/yurlungr/restore-packages.sh\""))
        assertTrue(cmd.contains("[ \$c -ge 129 ] && exit \$c"))
        assertTrue(File("src/main/assets/restore-packages.sh").readText().contains("setup_init \"\$HOME/restore-packages.log\""))
    }

    // ---- Opening files ------------------------------------------------------------------------

    @Test fun `only visible files in home, not too big, are opened`() {
        val home = tmp.newFolder("home")
        val pdf = File(home, "projects/app/docs/guide.pdf").apply { parentFile!!.mkdirs(); writeText("pdf") }
        assertEquals(pdf.canonicalFile, FileOpenStaging.openSource(home, pdf))
        assertEquals(pdf.canonicalFile, FileOpenStaging.openSource(home, File(home, "projects/app/../app/docs/guide.pdf")))
        val key = File(home, ".ssh/id_ed25519").apply { parentFile!!.mkdirs(); writeText("key") }
        assertNull(FileOpenStaging.openSource(home, key))
        assertNull(FileOpenStaging.openSource(home, File(home, "projects/app/.env").apply { writeText("x") }))
        assertNull(FileOpenStaging.openSource(home, File(home, "projects/app/.git/config").apply { parentFile!!.mkdirs(); writeText("x") }))
        assertNull(FileOpenStaging.openSource(home, File(home, "projects/app")))
        assertNull(FileOpenStaging.openSource(home, tmp.newFile("outside.pdf")))
        assertNull(FileOpenStaging.openSource(home, File(tmp.root, "home-other/x.pdf").apply { parentFile!!.mkdirs(); writeText("x") }))
        // A link inside home pointing out of it counts as where it points.
        val outside = tmp.newFile("secret.txt")
        val link = File(home, "link.txt")
        java.nio.file.Files.createSymbolicLink(link.toPath(), outside.toPath())
        assertNull(FileOpenStaging.openSource(home, link))
    }

    @Test fun `staged copies keep a safe name and old ones are dropped`() {
        val out = tmp.newFolder("files", FileOpenStaging.SHARE_OUT)
        val stale = File(out, "old").apply { mkdirs(); setLastModified(0) }
        val src = tmp.newFile("Report (final).pdf").apply { writeText("data") }
        val copy = FileOpenStaging.stage(src, out, 2 * FileOpenStaging.TTL_MS)
        assertEquals("Report _final_.pdf", copy.name)
        assertEquals("data", copy.readText())
        assertFalse(stale.exists())
    }

    @Test fun `mime types come from Android's table, then text for code`() {
        val table = mapOf("pdf" to "application/pdf", "png" to "image/png")
        assertEquals("application/pdf", FileOpenStaging.mimeType("a.PDF", table::get))
        assertEquals("text/plain", FileOpenStaging.mimeType("Main.kt", table::get))
        assertEquals("application/octet-stream", FileOpenStaging.mimeType("blob.bin", table::get))
        assertEquals("application/octet-stream", FileOpenStaging.mimeType("Makefile", table::get))
    }

    // ---- System checks cost -------------------------------------------------------------------

    @Test fun `the update count is reused until the package files change`() {
        val e = UpgradableCount.Entry(3, at = 1_000, key = "1:2")
        assertTrue(UpgradableCount.usable(e, "1:2", 1_000 + 60_000, allowRun = true))
        assertFalse(UpgradableCount.usable(e, "1:2", 1_000 + UpgradableCount.MAX_AGE_MS, allowRun = true))
        // Quick refreshes take any count for the same files.
        assertTrue(UpgradableCount.usable(e, "1:2", 1_000 + UpgradableCount.MAX_AGE_MS * 3, allowRun = false))
        assertFalse(UpgradableCount.usable(e, "9:2", 1_100, allowRun = false))
        assertFalse(UpgradableCount.usable(null, "1:2", 0, allowRun = true))
    }

    @Test fun `the probe pool keeps at most a few running and prints every tool`() {
        val bash = listOf("/bin/bash", "/usr/bin/bash").map(::File).firstOrNull { it.canExecute() }
        assumeTrue("needs bash", bash != null)
        // Each probe records how many are running, then lingers a little.
        val dir = tmp.newFolder("probe")
        val tools = (1..9).map { i ->
            Tool("t$i", "T$i", "mkdir '${dir.path}/run.$i' && n=\$(ls -d '${dir.path}'/run.* | wc -l) && sleep 0.2 && rmdir '${dir.path}/run.$i' && echo \"running \$n\"", ToolSetup.Utils)
        } + Tool("gone", "Gone", "definitely-not-a-command-xyz --version", ToolSetup.Utils)
        val script = Toolchain.probeScript(tools)
        val p = ProcessBuilder(bash!!.path, "-c", script).apply { environment()["TMPDIR"] = tmp.newFolder("t").path }.redirectErrorStream(true).start()
        assertTrue(p.waitFor(60, TimeUnit.SECONDS))
        val out = p.inputStream.bufferedReader().readText()
        val states = Toolchain.parseProbe(out, tools)
        for (s in states.dropLast(1)) {
            assertEquals(out, 0, s.exitCode)
            val running = s.firstLine.removePrefix("running ").toInt()
            assertTrue("at most ${Toolchain.PROBE_PARALLEL} at once: $out", running <= Toolchain.PROBE_PARALLEL)
        }
        assertEquals(127, states.last().exitCode)
    }

    // ---- Setups --------------------------------------------------------------------------------

    @Test fun `setups get longer to stop than other commands`() {
        assertTrue(SetupLock.isSetupCommand(setupScriptCommand("agents-setup.sh")))
        assertTrue(SetupLock.isSetupCommand(packagesUpgradeCommand()))
        assertTrue(SetupLock.isSetupCommand(agentInstallCommand("goose")))
        assertFalse(SetupLock.isSetupCommand("npm run build"))
        assertFalse(SetupLock.isSetupCommand(null))
        assertTrue(SetupLock.STOP_GRACE_MS in 3_000..15_000)
    }

    @Test fun `interrupted installs are repaired with apt too`() {
        val common = File("src/main/assets/setup-common.sh").readText()
        val prepare = common.substringAfter("apt_prepare() {").substringBefore("\n}\n")
        assertTrue(prepare.contains("dpkg --force-confdef --force-confold --configure -a"))
        assertTrue(prepare.contains("apt-get -f install \"\${APT_OPTS[@]}\""))
        assertTrue(prepare.indexOf("--configure -a") < prepare.indexOf("apt-get -f install"))
    }

    @Test fun `removing a pack stops at anything that isn't the pack's own`() {
        val text = File("src/main/assets/pack-remove.sh").readText()
        assertTrue(text.contains("wanted=\" \${remove[*]} \""))
        assertTrue(text.contains("case \"\$wanted\" in *\" \$g \"*) continue ;; esac"))
        assertTrue("other dependents are blocked, not removed", text.contains("theirs+=\" \$g\""))
        assertTrue(text.indexOf("setup_finish \"\"", text.indexOf("theirs+=")) < text.indexOf("apt-get remove -y"))
    }

    @Test fun `agent installs clean up and keep a working copy`() {
        val text = File("src/main/assets/agent-install.sh").readText()
        assertTrue(text.contains("cleanup_on_exit \"\$WORK\""))
        // The interrupted-update copy is restored before the swap deletes it.
        assertTrue(text.indexOf("mv \"\$DEST.old\" \"\$DEST\" && ok") < text.indexOf("rm -rf \"\$DEST.old\""))
        assertTrue(text.contains("warn \"No published checksum"))
        val common = File("src/main/assets/setup-common.sh").readText()
        assertTrue(common.contains("cleanup_paths; [ \"\$STEP_GROUPS\""))
    }
}
