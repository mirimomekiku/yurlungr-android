package dev.yurlungr.app.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RuntimeBridgeTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun `inbox requests are limited per kind within a sliding window`() {
        var now = 0L
        val limiter = InboxRateLimiter(max = 10, windowMs = 10_000, clock = { now })
        repeat(10) { assertTrue(limiter.tryAcquire("notify")) }
        assertFalse(limiter.tryAcquire("notify"))
        // Another kind has its own budget.
        assertTrue(limiter.tryAcquire("open"))
        now += 9_999
        assertFalse(limiter.tryAcquire("notify"))
        now += 1
        assertTrue(limiter.tryAcquire("notify"))
    }

    @Test fun `only existing apks inside the app files folder are installed`() {
        val files = tmp.newFolder("files")
        val apk = File(files, "home/projects/app/build/app-debug.apk").apply { parentFile!!.mkdirs(); writeText("apk") }
        assertEquals(apk.canonicalFile, ApkStaging.installSource(files, apk))
        assertEquals(apk.canonicalFile, ApkStaging.installSource(files, File(files, "home/projects/../projects/app/build/app-debug.apk")))
        assertNull(ApkStaging.installSource(files, File(files, "home/missing.apk")))
        val notApk = File(files, "home/notes.txt").apply { writeText("x") }
        assertNull(ApkStaging.installSource(files, notApk))
        val outside = tmp.newFile("outside.apk")
        assertNull(ApkStaging.installSource(files, outside))
        assertNull(ApkStaging.installSource(files, File(files, "../outside.apk")))
        // A sibling folder whose name merely starts the same.
        val sibling = File(tmp.root, "files-other/x.apk").apply { parentFile!!.mkdirs(); writeText("x") }
        assertNull(ApkStaging.installSource(files, sibling))
    }

    @Test fun `staged apk names are safe`() {
        assertEquals("5-app-debug.apk", ApkStaging.stagedName("app-debug.apk", 5))
        assertEquals("5-my_app__1_.apk", ApkStaging.stagedName("my app (1).apk", 5))
        assertEquals("5-app.apk", ApkStaging.stagedName("...apk", 5))
    }

    @Test fun `apks are staged into the shared folder and stale copies dropped`() {
        val out = tmp.newFolder("files", ApkStaging.APK_OUT)
        val stale = File(out, "old.apk").apply { writeText("old"); setLastModified(0) }
        val apk = tmp.newFile("app.apk").apply { writeText("payload") }
        val now = 2 * 60 * 60_000L
        val staged = ApkStaging.stage(apk, out, now)
        assertEquals(out, staged.parentFile)
        assertEquals("payload", staged.readText())
        assertFalse(stale.exists())
    }

    @Test fun `runtime notices are labelled`() {
        val (title, text) = TaskNotifications.runtimeNotice("Build done\nAll tests passed")!!
        assertEquals("${TaskNotifications.RUNTIME_LABEL}: Build done", title)
        assertEquals("All tests passed", text)
        assertNull(TaskNotifications.runtimeNotice("\nno title"))
    }
}
