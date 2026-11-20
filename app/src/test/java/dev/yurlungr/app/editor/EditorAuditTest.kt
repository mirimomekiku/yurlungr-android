package dev.yurlungr.app.editor

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class EditorAuditTest {
    @get:Rule val tmp = TemporaryFolder()

    private val kept = OpenFiles.Kept("new text\n\"quoted\" ✓", "old\n", DiskStamp(true, 4, 1_700_000_000_000))

    @Test fun `unsaved buffers round-trip`() {
        val encoded = UnsavedBackups.encode("/p/a.kt", kept)
        assertEquals("/p/a.kt" to kept, UnsavedBackups.decode(encoded))
    }

    @Test fun `unreadable backups decode to null`() {
        assertNull(UnsavedBackups.decode(""))
        assertNull(UnsavedBackups.decode("not json"))
        assertNull(UnsavedBackups.decode("""{"v": 99, "path": "/p", "text": "", "saved": "", "exists": true, "length": 0, "modified": 0}"""))
        assertNull(UnsavedBackups.decode("""{"v": 1, "path": "/p"}"""))
    }

    @Test fun `backup names are stable per file`() {
        val a = UnsavedBackups.nameFor(File(tmp.root, "a.kt").path)
        assertEquals(a, UnsavedBackups.nameFor(File(tmp.root, "x/../a.kt").path))
        assertNotEquals(a, UnsavedBackups.nameFor(File(tmp.root, "b.kt").path))
        assertTrue(a.matches(Regex("[0-9a-f]{40}")))
    }

    @Test fun `a backup comes back only while it differs from the file`() {
        val dir = tmp.newFolder("unsaved")
        val path = File(tmp.root, "a.kt").path
        UnsavedBackups.write(dir, path, kept)
        assertEquals(kept, UnsavedBackups.restore(dir, path, diskText = "old\n"))
        // Saved meanwhile (same text on disk): nothing to restore, and the backup goes.
        assertNull(UnsavedBackups.restore(dir, path, diskText = kept.text))
        assertTrue(dir.list()!!.isEmpty())
        UnsavedBackups.write(dir, path, kept)
        UnsavedBackups.delete(dir, path)
        assertNull(UnsavedBackups.restore(dir, path, diskText = "old\n"))
    }

    @Test fun `prettier output is brought back to LF`() {
        assertEquals("a\nb\n", Formatter.normalize("a\r\nb\r\n"))
        assertEquals("a\nb", Formatter.normalize("a\nb"))
    }

    @Test fun `an untrusted project's prettier config and plugins are never loaded`() {
        val file = java.io.File("/home/projects/repo/src/app.ts")
        val input = java.io.File("/cache/in-1.txt")
        val output = java.io.File("/cache/out-1.txt")
        val trusted = Formatter.command(file, input, output, trusted = true)
        assertTrue(trusted, !trusted.contains("--no-config"))
        val untrusted = Formatter.command(file, input, output, trusted = false)
        assertTrue(untrusted, untrusted.contains(" --no-config --no-editorconfig --stdin-filepath '/home/projects/repo/src/app.ts'"))
        assertTrue(untrusted, untrusted.startsWith("npx --yes prettier@"))
    }

    @Test fun `first visible gutter line is found by binary search`() {
        val tops = floatArrayOf(0f, 20f, 40f, 100f, 120f) // line 2 wraps
        fun first(y: Float) = firstLineAtOrBelow(tops.size, y) { tops[it] }
        assertEquals(0, first(-40f))
        assertEquals(0, first(0f))
        assertEquals(1, first(1f))
        assertEquals(3, first(41f))
        assertEquals(3, first(100f))
        assertEquals(5, first(500f))
        assertEquals(0, firstLineAtOrBelow(0, 10f) { error("no lines") })
        // Logarithmic: a million lines take ~20 lookups, not a walk from the top.
        var lookups = 0
        assertEquals(500_000, firstLineAtOrBelow(1_000_000, 500_000f) { lookups++; it.toFloat() })
        assertTrue(lookups <= 21)
    }

    @Test fun `only file URIs become locations`() {
        assertEquals(File("/p/a.kt"), LspClient.fileOf("file:///p/a.kt"))
        assertNull(LspClient.fileOf("jdt://contents/java.base/java/lang/String.class"))
        assertNull(LspClient.fileOf("untitled:Untitled-1"))
        assertNull(LspClient.fileOf("not a uri"))
    }

    @Test fun `LSP frames carry the byte length`() {
        val msg = JsonObject(mapOf("m" to JsonPrimitive("é")))
        val frame = String(LspClient.frame(msg), Charsets.UTF_8)
        val body = msg.toString()
        assertEquals("Content-Length: ${body.toByteArray().size}\r\n\r\n$body", frame)
    }
}
