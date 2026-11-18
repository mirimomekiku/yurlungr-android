package dev.yurlungr.app.runtime

import dev.yurlungr.app.setup.CheckStatus
import dev.yurlungr.app.setup.DoctorChecks
import dev.yurlungr.app.setup.Fix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HardeningTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun `ppid is read after the last parenthesis, whatever the process name`() {
        assertEquals(1, ProcessTree.parsePpid("1234 (bash) S 1 1234 1234 0 -1"))
        assertEquals(77, ProcessTree.parsePpid("88 (weird) name (x)) R 77 88 88 0"))
        assertNull(ProcessTree.parsePpid("garbage"))
    }

    @Test fun `start time is field 22, counted after the last parenthesis`() {
        // Fields 3..21, then starttime (22) and vsize.
        val rest = "S 1 1234 1234 0 -1 4194560 100 0 0 0 5 3 0 0 20 0 1 0 987654 12345678"
        assertEquals(987654L, ProcessTree.parseStartTime("1234 (bash) $rest"))
        assertEquals(987654L, ProcessTree.parseStartTime("88 (a b) (c)) $rest"))
        assertEquals(1, ProcessTree.parsePpid("88 (a b) (c)) $rest"))
        assertNull(ProcessTree.parseStartTime("1234 (bash) S 1 1234"))
        assertNull(ProcessTree.parseStartTime("garbage"))
    }

    @Test fun `descendants include grandchildren but never the root or unrelated processes`() {
        // 10 → 11 → 12, 10 → 13; 20 is unrelated; 1 is init.
        val parents = mapOf(10 to 1, 11 to 10, 12 to 11, 13 to 10, 20 to 1)
        assertEquals(listOf(11, 13, 12), ProcessTree.descendantsOf(10, parents))
        assertEquals(emptyList<Int>(), ProcessTree.descendantsOf(20, parents))
    }

    @Test fun `setup status knows running, interrupted, hung-up and out-of-space runs`() {
        val running = SetupStatus.parse("exit\t-1\npid\t4242\nlog\t/h/x.log\nfinished\t1\n")!!
        assertTrue(running.unfinished)
        assertEquals(4242, running.pid)
        assertEquals("Running now.", SetupStatus.summary(running, running = true))
        assertTrue(SetupStatus.summary(running, running = false).startsWith("Didn't finish"))
        assertTrue(SetupStatus.summary(running.copy(exitCode = 129)).contains("terminal was closed"))
        assertTrue(SetupStatus.summary(running.copy(exitCode = 74)).contains("free storage"))
        assertFalse(SetupStatus.isAlive(0))
    }

    @Test fun `log tail strips colours and progress redraws, and says when it was cut`() {
        val log = tmp.newFile("setup.log")
        log.writeText("\u001B[32m  ✓ git\u001B[0m\ndownloading 10%\rdownloading 100%\nend\n")
        assertEquals("  ✓ git\ndownloading 100%\nend\n", SetupStatus.readLogTail(log))

        log.writeText("x".repeat(100) + "\nlast line\n")
        val tail = SetupStatus.readLogTail(log, maxBytes = 20)
        assertTrue(tail.startsWith("… (earlier output omitted)\n"))
        assertTrue(tail.endsWith("last line\n"))
        assertTrue(SetupStatus.readLogTail(java.io.File(tmp.root, "missing.log")).startsWith("No log yet"))
    }

    @Test fun `managed agent notes keep the user's text`() {
        val block = "<!-- yurlungr:begin (managed by the Yurlungr app; edits inside are overwritten) -->\nnew\n<!-- yurlungr:end -->\n"
        assertEquals(block, RuntimeAssets.mergeManagedBlock("", block))
        assertEquals("mine\n\n$block", RuntimeAssets.mergeManagedBlock("mine\n", block))
        val old = "top\n<!-- yurlungr:begin (managed by the Yurlungr app; edits inside are overwritten) -->\nold\n<!-- yurlungr:end -->\nbottom\n"
        assertEquals("top\n" + block.trimEnd('\n') + "\nbottom\n", RuntimeAssets.mergeManagedBlock(old, block))
    }

    @Test fun `bootstrap symlink paths must stay inside the prefix`() {
        assertTrue(BootstrapInstaller.isContainedRelativePath("bin/sh"))
        assertFalse(BootstrapInstaller.isContainedRelativePath("/etc/passwd"))
        assertFalse(BootstrapInstaller.isContainedRelativePath("../../x"))
        assertFalse(BootstrapInstaller.isContainedRelativePath("bin/../../x"))
        assertFalse(BootstrapInstaller.isContainedRelativePath(""))
    }

    @Test fun `every optional pack has its own script, packages, tools and fix`() {
        val packs = ToolSetup.entries.filter { it.optional }
        assertEquals(44, packs.size)
        assertEquals(packs.size, packs.mapNotNull { it.script }.toSet().size)
        assertEquals(Toolchain.tools.map { it.id }.toSet().size, Toolchain.tools.size)
        val manifest = Toolchain.shellManifest()
        for (p in packs) {
            assertTrue("${p.name} packages", p.packages.isNotEmpty())
            assertTrue("${p.name} tools", Toolchain.tools.any { it.setup == p })
            assertTrue(manifest.contains("YURLUNGR_PACKAGES_${p.name.uppercase()}=("))
            assertTrue(manifest.contains("YURLUNGR_CHECKS_${p.name.uppercase()}=("))
            assertEquals(p, Fix.entries.first { it.setup == p }.setup)
            assertTrue(setupScriptCommand(p.script!!).contains(p.script!!))
            // The script's asset ships with the app; installed under the pack's script name, it finds its pack.
            val script = java.io.File("src/main/assets/${p.asset}")
            assertTrue("${p.asset} exists", script.isFile)
            assertTrue("${p.script} maps to its pack", manifest.contains("  ['${p.script}']=${p.name.uppercase()}\n"))
            assertTrue(manifest.contains("YURLUNGR_TITLE_${p.name.uppercase()}="))
            if (p.asset != "pack-setup.sh") {
                assertTrue("${p.script} uses its pack", script.readText().contains("YURLUNGR_PACKAGES_${p.name.uppercase()}[@]"))
            }
        }
        assertEquals(listOf(ToolSetup.Ubuntu, ToolSetup.Flutter, ToolSetup.Browser), packs.filter { it.asset != "pack-setup.sh" })
    }

    @Test fun `the Ubuntu helper keeps its contract`() {
        val text = java.io.File("src/main/assets/yurlungr-ubuntu.sh").readText()
        assertEquals("#!/usr/bin/env bash", text.lineSequence().first().trimEnd()) // RuntimeAssets swaps it for an absolute path
        assertTrue(text.contains("installed-rootfs/ubuntu"))
        assertTrue(text.contains("exit 69"))
        assertTrue(text.contains("--bind \"\$HOME:\$HOME\""))
        assertTrue(text.contains("bash -lc \"\$script\""))
        assertTrue(text.contains("for name in PORT "))
        // Flutter's check is the SDK's launcher inside Ubuntu.
        assertEquals("$UBUNTU_ROOTFS/opt/flutter/bin/flutter", ToolSetup.Flutter.marker)
        assertEquals("var/lib/proot-distro/installed-rootfs/ubuntu", UBUNTU_ROOTFS)
        val flutter = java.io.File("src/main/assets/flutter-setup.sh").readText()
        for (part in listOf("--depth 1 --branch stable", "/etc/profile.d/flutter.sh", "flutter precache --web", "--enable-web")) assertTrue(part, flutter.contains(part))
    }

    @Test fun `the generic pack script keeps the per-pack behavior`() {
        val text = java.io.File("src/main/assets/pack-setup.sh").readText()
        // Log and status file named after the script it's installed as (go-setup.log, go-setup.status).
        assertTrue(text.contains("NAME=\$(basename \"\$0\" .sh)"))
        assertTrue(text.contains("setup_init \"\$HOME/\$NAME.log\""))
        assertTrue(text.contains("YURLUNGR_PACK_OF_SCRIPT[\$NAME.sh]"))
        for (part in listOf("YURLUNGR_SPACE_MB_", "YURLUNGR_TITLE_", "YURLUNGR_PACKAGES_", "YURLUNGR_CHECKS_")) {
            assertTrue(part, text.contains(part))
        }
        for (call in listOf("require_space", "apt_prepare && install_pkgs", "run_checks", "setup_finish \"\$title ready.")) {
            assertTrue(call, text.contains(call))
        }
        // The pack name is used in eval: only A-Z gets through.
        assertTrue(text.contains("''|*[!A-Z]*)"))
    }

    @Test fun `a missing optional tool is info with an Install fix, not a warning`() {
        val py = Toolchain.tools.first { it.id == "python" }
        DoctorChecks.toolCheck(ToolState(py, 127, "")).let {
            assertEquals(CheckStatus.Info, it.status)
            assertEquals(Fix.InstallPython, it.fix)
            assertFalse(it.required)
        }
        assertEquals(CheckStatus.Pass, DoctorChecks.toolCheck(ToolState(py, 0, "Python 3.12.0")).status)
    }

    @Test fun `tool states map to checks with the right fix`() {
        val git = Toolchain.tools.first { it.id == "git" }
        val claude = Toolchain.tools.first { it.id == "claude" }
        assertEquals(CheckStatus.Pass, DoctorChecks.toolCheck(ToolState(git, 0, "git version 2.47")).status)
        DoctorChecks.toolCheck(ToolState(git, 127, "")).let {
            assertEquals(CheckStatus.Fail, it.status) // essential
            assertEquals(Fix.InstallDevTools, it.fix)
        }
        DoctorChecks.toolCheck(ToolState(claude, 127, "CANNOT LINK EXECUTABLE")).let {
            // Installed (its files exist) but won't run: a repair, not "not installed".
            assertEquals(CheckStatus.Fail, it.status)
            assertTrue(it.detail.startsWith("Installed but can't run"))
            assertEquals(Fix.InstallAgents, it.fix)
        }
        assertEquals(CheckStatus.Warn, DoctorChecks.toolCheck(ToolState(git, 124, "")).status)
    }

    @Test fun `probes run a few at a time, with a kill timeout`() {
        val script = Toolchain.probeScript(Toolchain.tools)
        val waits = script.lines().count { it == "wait" }
        assertEquals((Toolchain.tools.size + Toolchain.PROBE_PARALLEL - 1) / Toolchain.PROBE_PARALLEL + if (Toolchain.tools.size % Toolchain.PROBE_PARALLEL == 0) 1 else 0, waits)
        assertTrue(script.contains("timeout -k 5 "))
        assertTrue(script.contains("mkdir -p"))
        assertFalse("no head: SIGPIPE would fail pipefail probes", Toolchain.tools.any { "head" in it.probe })
        assertTrue(Toolchain.probeBudgetMs(Toolchain.tools.size) > Toolchain.PROBE_TIMEOUT_S * 1000L * 2)
    }
}
