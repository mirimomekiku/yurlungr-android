package dev.yurlungr.app.editor

import androidx.compose.ui.text.TextRange
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class EditHistoryTest {
    @Test fun `an edit is only the changed middle`() {
        assertEquals(TextEdit(3, "", "X"), TextEdit.between("abcdef", "abcXdef"))
        assertEquals(TextEdit(1, "bc", ""), TextEdit.between("abcd", "ad"))
        assertEquals(TextEdit(0, "", "aa"), TextEdit.between("", "aa"))
        // Repeated characters: still a valid edit.
        val e = TextEdit.between("aaa", "aaaa")
        assertEquals("aaaa", e.redo("aaa"))
        assertEquals("aaa", e.undo("aaaa"))
    }

    @Test fun `undo and redo walk back and forth through grouped typing`() {
        val h = EditHistory()
        var text = ""
        fun type(new: String, at: Long, boundary: Boolean = false) {
            h.record(text, TextRange(text.length), new, TextRange(new.length), boundary, now = at)
            text = new
        }
        type("h", 0); type("he", 100); type("hel", 200) // one burst
        type("hel lo", 5_000) // after a pause: a new step
        type("HEL lo", 5_100, boundary = true) // a paste or replace: its own step
        assertEquals(3, h.undo.size)
        var (t, sel) = h.undo(text)!!; assertEquals("hel lo", t); assertEquals(TextRange(6), sel); text = t
        h.undo(text)!!.let { text = it.first }; assertEquals("hel", text)
        h.undo(text)!!.let { text = it.first }; assertEquals("", text)
        assertNull(h.undo(text))
        h.redo(text)!!.let { text = it.first }; assertEquals("hel", text)
        h.redo(text)!!.let { text = it.first; sel = it.second }; assertEquals("hel lo", text)
        // A new edit drops the redo branch.
        type("hel lo!", 9_000, boundary = true)
        assertNull(h.redo(text))
    }

    @Test fun `history stays bounded on big edits`() {
        val h = EditHistory()
        var text = ""
        val chunk = "x".repeat(1024 * 1024)
        repeat(10) { i -> val new = text + chunk; h.record(text, TextRange(0), new, TextRange(0), boundary = true, now = i * 10_000L); text = new }
        assertTrue("kept ${h.undo.size}", h.undo.size <= 4)
    }

    @Test fun `incremental line index matches a full scan`() {
        val rnd = Random(7)
        var text = "one\ntwo\n\nthree\nfour"
        var starts = LineIndex.full(text)
        repeat(2_000) {
            val at = rnd.nextInt(text.length + 1)
            val cut = minOf(rnd.nextInt(6), text.length - at)
            val ins = listOf("", "a", "\n", "b\nc", "\n\n", "xyz").random(rnd)
            val new = text.substring(0, at) + ins + text.substring(at + cut)
            starts = LineIndex.incremental(text, starts, new)
            text = new
            assertArrayEquals("after edit at $at: ${text.replace("\n", "⏎")}", LineIndex.full(text), starts)
        }
    }
}
