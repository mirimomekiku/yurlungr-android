package dev.yurlungr.app.projects

import dev.yurlungr.app.runtime.SetupStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FixPromptTest {
    private val noisyLog = buildString {
        repeat(300) { appendLine("npm http fetch GET 200 https://registry.npmjs.org/pkg-$it 12ms (cache hit)") }
        appendLine("npm ERR! code 1")
        appendLine("npm ERR! path /home/projects/app/node_modules/bcrypt")
        appendLine("gyp: Undefined variable android_ndk_path in binding.gyp while trying to load binding.gyp")
        repeat(300) { appendLine("npm verb stack line $it") }
        appendLine("npm ERR! A complete log of this run can be found in: /home/.npm/_logs/debug.log")
    }

    @Test fun `excerpt keeps errors with context and the end, and stays under the cap`() {
        val excerpt = LogExcerpt.excerpt(noisyLog, cap = 1500)
        assertTrue(excerpt.length <= 1500)
        assertTrue(excerpt.contains("Undefined variable android_ndk_path"))
        assertTrue(excerpt.contains("A complete log of this run"))
        assertTrue(excerpt.contains("pkg-299")) // context before the first error
        assertFalse(excerpt.contains("pkg-100"))
        assertTrue(excerpt.startsWith("…"))
    }

    @Test fun `excerpt strips colors and carriage-return redraws`() {
        val excerpt = LogExcerpt.excerpt("\u001B[31mError:\u001B[0m boom\nprogress 10%\rprogress 100%\n")
        assertEquals("Error: boom\nprogress 100%", excerpt)
    }

    @Test fun `isError ignores success summaries and identifiers`() {
        assertTrue(LogExcerpt.isError("Traceback (most recent call last):"))
        assertTrue(LogExcerpt.isError("npm ERR! code ELIFECYCLE"))
        assertTrue(LogExcerpt.isError("1 test FAILED"))
        assertFalse(LogExcerpt.isError("Found 0 errors. Watching for file changes."))
        assertFalse(LogExcerpt.isError("compiled errorHandler.ts"))
        assertFalse(LogExcerpt.isError("BUILD SUCCESSFUL in 3s"))
    }

    @Test fun `prompt has what ran, the stack, the hint, the log and the ask`() {
        val report = FailureReport("Dependency setup", "npm install", listOf("Node.js (npm)", "Express"), noisyLog, "Failed: npm install.")
        val fixes = KnownFixes.match(noisyLog)
        val prompt = FixPrompt.build(report, fixes)
        assertTrue(prompt.startsWith("Dependency setup failed in this project on my phone."))
        assertTrue(prompt.contains("What ran: `npm install`"))
        assertTrue(prompt.contains("Stack: Node.js (npm), Express"))
        assertTrue(prompt.contains("Status: Failed: npm install."))
        assertTrue(prompt.contains("node-gyp looks for the Android NDK"))
        assertTrue(prompt.contains("GYP_DEFINES=android_ndk_path=''"))
        assertTrue(prompt.contains("Undefined variable android_ndk_path"))
        assertTrue(prompt.contains("Make this work on this phone"))
        assertTrue(prompt.contains("explain what you changed"))
        assertTrue(prompt.length < FixPrompt.LOG_CAP + 2000)
    }

    @Test fun `log fence can't be closed by backticks in the log`() {
        val log = "Error: bad\n```\nmore\n````\n"
        val prompt = FixPrompt.build(FailureReport("Checks", null, emptyList(), log), emptyList())
        assertTrue(prompt.contains("\n`````\n"))
        assertFalse(prompt.contains("What ran"))
        assertEquals("```", FixPrompt.fence("plain"))
    }

    @Test fun `versions prompt lists each mismatch`() {
        val w = VersionWarning(RuntimeRequirement("node", "18", ".nvmrc", "18"), "22.11.0")
        val prompt = FixPrompt.versions(listOf(w))
        assertTrue(prompt.contains("- Node.js: the project wants 18 (.nvmrc); installed is 22.11.0."))
    }

    private val failed = SetupStatus(1, "/home/project-setup.log", 1_700_000_000_000, listOf("npm ci || npm install"))

    @Test fun `setup failure is reported for its own project only`() {
        val dir = File("/home/projects/app")
        val log = "project\t/home/projects/app\nnpm ERR! code 1\n"
        val project = SetupFailure.projectOf(log)
        assertEquals("/home/projects/app", project)
        val report = SetupFailure.report(failed, project, log, dir, listOf("Node.js (npm)"))
        assertNotNull(report)
        assertEquals("npm ci || npm install", report!!.command)
        assertFalse(report.log.contains("project\t"))
        assertNull(SetupFailure.report(failed, project, log, File("/home/projects/other"), emptyList()))
        assertNull(SetupFailure.report(failed, null, log, dir, emptyList()))
    }

    @Test fun `success, running and user stops are not failures`() {
        val dir = File("/p")
        for (code in listOf(0, SetupStatus.RUNNING, 75, 130, 143, 129)) {
            assertNull(SetupFailure.report(failed.copy(exitCode = code), "/p", "", dir, emptyList()))
        }
        assertNull(SetupFailure.report(null, "/p", "", dir, emptyList()))
    }
}
