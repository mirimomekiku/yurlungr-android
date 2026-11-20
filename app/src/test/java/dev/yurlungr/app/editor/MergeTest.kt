package dev.yurlungr.app.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MergeTest {
    private fun conflicts(text: String) = Merge.parse(text).filterIsInstance<MergePart.Conflict>()

    @Test fun `only git's exact seven-character markers count`() {
        assertTrue(Merge.isMarker("<<<<<<< HEAD", '<'))
        assertTrue(Merge.isMarker("=======", '='))
        assertTrue(Merge.isMarker(">>>>>>>", '>'))
        assertFalse(Merge.isMarker("========", '='))
        assertFalse(Merge.isMarker(">>>>>>>> quoted mail", '>'))
        assertFalse(Merge.isMarker("<<<<<<<HEAD", '<'))
        assertFalse(Merge.isMarker("<<<<<<", '<'))
        assertFalse(Merge.hasConflicts("Title\n========\n>>>>>>>> quote\n"))
    }

    @Test fun `a Markdown underline inside a conflict stays content`() {
        val text = "intro\n<<<<<<< HEAD\nTitle\n========\nours\n=======\nOther title\n========\n>>>>>>> topic\nend\n"
        val c = conflicts(text).single()
        assertEquals("Title\n========\nours", c.ours)
        assertEquals("Other title\n========", c.theirs)
        assertEquals("intro\nOther title\n========\nend\n", Merge.render(Merge.parse(text), listOf(c.theirs)))
    }

    @Test fun `deeply quoted text is not a closing marker`() {
        val text = "<<<<<<< HEAD\n>>>>>>>> ours quoting\n=======\n>>>>>>>>> theirs quoting\n>>>>>>> topic\n"
        val c = conflicts(text).single()
        assertEquals(">>>>>>>> ours quoting", c.ours)
        assertEquals(">>>>>>>>> theirs quoting", c.theirs)
        assertEquals("topic", c.theirsLabel)
    }

    @Test fun `each line keeps its own ending, CRLF and LF mixed`() {
        val text = "a\r\nb\n<<<<<<< HEAD\r\nours 1\r\nours 2\n=======\r\ntheirs\r\n>>>>>>> topic\r\nc\n"
        val parts = Merge.parse(text)
        val c = parts.filterIsInstance<MergePart.Conflict>().single()
        assertEquals("ours 1\nours 2", c.ours)
        assertEquals("theirs", c.theirs)
        // Unresolved: byte for byte what was there.
        assertEquals(text, Merge.render(parts, listOf(null)))
        assertEquals("a\r\nb\nours 1\r\nours 2\nc\n", Merge.render(parts, listOf(c.ours)))
        assertEquals("a\r\nb\ntheirs\r\nc\n", Merge.render(parts, listOf(c.theirs)))
        assertEquals("a\r\nb\nours 1\r\nours 2\ntheirs\r\nc\n", Merge.render(parts, listOf(Merge.both(c))))
        // An edited resolution takes the ending of the conflict's first marker line.
        assertEquals("a\r\nb\nx\r\ny\r\nc\n", Merge.render(parts, listOf("x\ny")))
    }

    @Test fun `a file without a final newline still ends without one`() {
        val text = "top\n<<<<<<< HEAD\nours\n=======\ntheirs\n>>>>>>> topic"
        val parts = Merge.parse(text)
        val c = parts.filterIsInstance<MergePart.Conflict>().single()
        assertFalse(c.endsWithNewline)
        assertEquals("top\ntheirs", Merge.render(parts, listOf(c.theirs)))
        assertEquals("top\nours\ntheirs", Merge.render(parts, listOf(Merge.both(c))))
        assertEquals("top\nedited", Merge.render(parts, listOf("edited")))
        assertEquals(text, Merge.render(parts, listOf(null)))
        // Plain text without a final newline round-trips too.
        assertEquals("a\nb", Merge.render(Merge.parse("a\nb"), emptyList()))
    }

    @Test fun `diff3 style keeps the base and its label`() {
        val text = "<<<<<<< ours\nmine\n||||||| merged common ancestors\noriginal\n=======\nyours\n>>>>>>> theirs\n"
        val c = conflicts(text).single()
        assertEquals("mine", c.ours)
        assertEquals("original", c.base)
        assertEquals("yours", c.theirs)
        assertEquals(text, Merge.render(Merge.parse(text), listOf(null)))
        assertNull(conflicts("<<<<<<< a\nx\n=======\ny\n>>>>>>> b\n").single().base)
    }

    @Test fun `empty sides and both`() {
        val text = "<<<<<<< HEAD\n=======\nadded\n>>>>>>> topic\nrest\n"
        val parts = Merge.parse(text)
        val c = parts.filterIsInstance<MergePart.Conflict>().single()
        assertEquals("", c.ours)
        assertEquals("added", Merge.both(c))
        assertEquals("added\nrest\n", Merge.render(parts, listOf(Merge.both(c))))
        assertEquals("rest\n", Merge.render(parts, listOf(c.ours)))
    }

    @Test fun `unterminated or interrupted markers stay as text`() {
        val open = "<<<<<<< HEAD\nours\n=======\ntheirs\n"
        assertEquals(listOf<MergePart>(MergePart.Text(open)), Merge.parse(open))
        // A second opening marker before any separator: the first wasn't a conflict.
        val twice = "<<<<<<< stray\n<<<<<<< HEAD\nx\n=======\ny\n>>>>>>> topic\n"
        val parts = Merge.parse(twice)
        assertEquals(MergePart.Text("<<<<<<< stray\n"), parts.first())
        assertEquals("x", (parts[1] as MergePart.Conflict).ours)
        assertEquals(twice, Merge.render(parts, listOf(null)))
    }

    @Test fun `several conflicts resolve independently`() {
        val text = "<<<<<<< HEAD\na\n=======\nb\n>>>>>>> t\nmid\n<<<<<<< HEAD\nc\n=======\nd\n>>>>>>> t\n"
        val parts = Merge.parse(text)
        assertEquals(2, parts.count { it is MergePart.Conflict })
        assertEquals("a\nmid\n<<<<<<< HEAD\nc\n=======\nd\n>>>>>>> t\n", Merge.render(parts, listOf("a", null)))
    }
}
