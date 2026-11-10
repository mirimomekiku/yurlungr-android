package dev.yurlungr.app.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FileSaveTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun `replaces content and leaves no temp files`() {
        val file = tmp.newFile("notes.md").apply { writeText("old") }
        FileSave.writeAtomically(file, "new ✓")
        assertEquals("new ✓", file.readText())
        assertEquals(listOf("notes.md"), tmp.root.list()!!.toList())
    }

    @Test fun `creates a new file and missing folders`() {
        val file = File(tmp.root, "a/b/new.txt")
        FileSave.writeAtomically(file, "hi")
        assertEquals("hi", file.readText())
    }

    @Test fun `keeps the executable bit`() {
        val file = tmp.newFile("run.sh").apply { writeText("#!/bin/sh\n"); setExecutable(true, true) }
        if (!file.canExecute()) return // file system without exec bits (e.g. some Windows setups)
        FileSave.writeAtomically(file, "#!/bin/sh\necho hi\n")
        assertTrue(file.canExecute())
    }

    @Test fun `failed write keeps the original`() {
        val dir = tmp.newFolder("dir")
        // A directory can't be replaced by rename: the original must survive untouched.
        File(dir, "keep").writeText("x")
        runCatching { FileSave.writeAtomically(dir, "text") }
        assertTrue(dir.isDirectory)
        assertEquals("x", File(dir, "keep").readText())
        assertTrue(tmp.root.list()!!.none { it.endsWith(".yurlungr-save") })
    }

    @Test fun `stamp detects outside changes`() {
        val file = tmp.newFile("a.txt").apply { writeText("one") }
        val before = DiskStamp.of(file)
        assertEquals(before, DiskStamp.of(file))
        file.writeText("one two")
        assertNotEquals(before, DiskStamp.of(file))
    }

    @Test fun `stamp of a missing file`() {
        val stamp = DiskStamp.of(File(tmp.root, "missing"))
        assertFalse(stamp.exists)
        assertNotEquals(stamp, DiskStamp.of(tmp.newFile("missing")))
    }
}
