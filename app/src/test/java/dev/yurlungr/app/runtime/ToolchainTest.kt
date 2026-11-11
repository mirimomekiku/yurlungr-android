package dev.yurlungr.app.runtime

import dev.yurlungr.app.chat.failureLooksLikeMissingTool
import dev.yurlungr.app.projects.Git
import dev.yurlungr.app.projects.GhProblem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class ToolchainTest {
    @Test fun `a program that can't be started is a result, not an exception`() {
        val missing = CommandResult.launchFailed(IOException("Cannot run program \"/x/git\": error=2, No such file or directory"))
        assertEquals(CommandFailure.NotFound, missing.failure)
        assertTrue(missing.toolMissing)
        assertFalse(missing.ok)
        assertTrue(missing.describe("git status").startsWith("git status: the program isn't installed"))

        val denied = CommandResult.launchFailed(IOException("Cannot run program \"/x/bash\": error=13, Permission denied"))
        assertEquals(CommandFailure.NotExecutable, denied.failure)
        assertTrue(denied.toolMissing)
    }

    @Test fun `describe distinguishes timeouts, shell 127 and ordinary failures`() {
        assertEquals("git push timed out after 300 s", CommandResult(-1, "", timedOut = true, timeoutMs = 300_000).describe("git push"))
        assertEquals(CommandFailure.NotFound, CommandResult(127, "bash: npx: command not found").failure)
        assertEquals(CommandFailure.NotExecutable, CommandResult(126, "").failure)
        val failed = CommandResult(1, "", errorOutput = "fatal: not possible to fast-forward")
        assertEquals("git pull failed (exit 1)\nfatal: not possible to fast-forward", failed.describe("git pull"))
        assertNull(CommandResult(0, "ok").failure)
    }

    @Test fun `probe output is parsed per tool, and silent tools count as not runnable`() {
        val tools = Toolchain.tools.filter { it.id in setOf("git", "gh", "node") }
        val states = Toolchain.parseProbe("git\t0\tgit version 2.47.0\ngh\t127\t\n", tools).associateBy { it.tool.id }
        assertTrue(states.getValue("git").ok)
        assertEquals("git version 2.47.0", states.getValue("git").firstLine)
        assertTrue(states.getValue("gh").missing)
        assertEquals(126, states.getValue("node").exitCode)
        assertFalse(states.getValue("node").ok)
    }

    @Test fun `the probe script runs every tool with a timeout`() {
        val script = Toolchain.probeScript(Toolchain.tools)
        for (t in Toolchain.tools) assertTrue("${t.id} probed", script.contains("'${t.id}'"))
        assertTrue(script.contains("timeout -k 5 ${Toolchain.PROBE_TIMEOUT_S} bash -o pipefail -c"))
        assertTrue(script.trimEnd().endsWith("rm -rf \"\$d\""))
    }

    @Test fun `the shell manifest carries the package lists and checks`() {
        val sh = Toolchain.shellManifest()
        assertTrue(sh.contains("YURLUNGR_DEV_PACKAGES=(termux-exec git gh nodejs-lts ripgrep curl)"))
        assertTrue("gradle is installed for the SDK test build", sh.contains(Regex("YURLUNGR_SDK_PACKAGES=\\(.*\\bgradle\\b")))
        assertTrue(sh.contains("YURLUNGR_SDK_PLATFORM=${Toolchain.SDK_PLATFORM}"))
        assertTrue(sh.contains("YURLUNGR_CHECKS_DEVTOOLS=("))
        assertTrue(sh.contains("YURLUNGR_CHECKS_ANDROIDSDK=("))
        assertTrue(sh.contains("'git\tgit --version'"))
        // Optional agents are checked only when installed (the agents setup failed on every one before).
        assertTrue(sh.contains("declare -gA YURLUNGR_ONLY_IF=("))
        assertTrue(sh.contains("['qwen']='bin/qwen'"))
        assertTrue(sh.contains("['goose']='libexec/yurlungr/bin/goose'"))
        assertFalse("required tools are always checked", sh.contains("['git']="))
    }

    @Test fun `setup status files round-trip into a summary`() {
        val s = SetupStatus.parse("exit\t1\nlog\t/h/agents-setup.log\nfinished\t1700000000\nfailed\tgit\nfailed\tpackage index update\n")!!
        assertEquals(1, s.exitCode)
        assertEquals("/h/agents-setup.log", s.log)
        assertEquals(1_700_000_000_000L, s.finishedAt)
        assertEquals(listOf("git", "package index update"), s.failed)
        assertTrue(SetupStatus.summary(s).startsWith("Failed: git, package index update."))
        assertEquals("Stopped before it finished. Run it again to complete it.", SetupStatus.summary(s.copy(exitCode = 130, failed = emptyList())))
        assertNull(SetupStatus.parse("garbage"))
    }

    @Test fun `gh auth status states`() {
        fun auth(code: Int, err: String, hosts: Boolean = false, timedOut: Boolean = false) =
            Git.parseGhAuth(CommandResult(code, "", timedOut = timedOut, errorOutput = err), hosts)

        val ok = auth(0, "github.com\n  ✓ Logged in to github.com account octo (keyring)")
        assertTrue(ok.signedIn); assertEquals("octo", ok.account); assertNull(ok.problem)

        val out = auth(1, "You are not logged into any GitHub hosts. To log in, run: gh auth login")
        assertFalse(out.signedIn); assertNull(out.problem)

        val offline = auth(1, "error connecting to api.github.com", hosts = true)
        assertTrue("offline with a saved sign-in stays signed in", offline.signedIn)
        assertEquals(GhProblem.Unreachable, offline.problem)
        assertEquals(GhProblem.Unreachable, auth(-1, "", timedOut = true).problem)

        val expired = auth(1, "github.com\n  X Failed to log in to github.com account octo (default)\n  - The token in hosts.yml is invalid.")
        assertFalse(expired.signedIn); assertEquals(GhProblem.SignInExpired, expired.problem)

        val broken = Git.parseGhAuth(CommandResult.launchFailed(IOException("error=13, Permission denied")), false)
        assertEquals(GhProblem.Broken, broken.problem)
    }

    @Test fun `chat failures that mean a missing tool`() {
        assertTrue(failureLooksLikeMissingTool("Agent exited (code 127)\n\n/usr/bin/env: 'node': No such file or directory"))
        assertTrue(failureLooksLikeMissingTool("Cannot run program \"/x/bash\": error=2, No such file or directory"))
        assertTrue(failureLooksLikeMissingTool("CANNOT LINK EXECUTABLE \"codex\": library \"libssl.so.3\" not found"))
        assertFalse(failureLooksLikeMissingTool("Agent exited (code 1)\n\nRate limit exceeded"))
    }

    @Test fun `commit email validation`() {
        assertTrue(Git.isValidEmail("me@example.com"))
        assertTrue(Git.isValidEmail(" me+tag@sub.example.org "))
        assertFalse(Git.isValidEmail("me@"))
        assertFalse(Git.isValidEmail("@example.com"))
        assertFalse(Git.isValidEmail("me@example"))
        assertFalse(Git.isValidEmail("me @example.com"))
    }
}
