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

    @Test fun `strict UTF-8 decoding rejects Latin-1 instead of replacing it`() {
        assertEquals("café ✓", FileSave.decodeUtf8("café ✓".toByteArray(Charsets.UTF_8)))
        assertEquals(null, FileSave.decodeUtf8("café".toByteArray(Charsets.ISO_8859_1)))
        assertEquals("﻿bom", FileSave.decodeUtf8(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte(), 'b'.code.toByte(), 'o'.code.toByte(), 'm'.code.toByte())))
    }

    @Test fun `line endings use the majority and flag mixed files`() {
        assertEquals(LineEndings(crlf = false, mixed = false), LineEndings.detect("a\nb\n"))
        assertEquals(LineEndings(crlf = true, mixed = false), LineEndings.detect("a\r\nb\r\n"))
        assertEquals(LineEndings(crlf = false, mixed = true), LineEndings.detect("a\r\nb\nc\n"))
        assertEquals(LineEndings(crlf = true, mixed = true), LineEndings.detect("a\r\nb\r\nc\n"))
        assertEquals(LineEndings(crlf = false, mixed = false), LineEndings.detect("no newline"))
    }

    @Test fun `stamp of a missing file`() {
        val stamp = DiskStamp.of(File(tmp.root, "missing"))
        assertFalse(stamp.exists)
        assertNotEquals(stamp, DiskStamp.of(tmp.newFile("missing")))
    }
}
