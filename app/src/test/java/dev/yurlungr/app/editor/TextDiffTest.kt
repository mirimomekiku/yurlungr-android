package dev.yurlungr.app.editor

import dev.yurlungr.app.projects.DiffLine
import dev.yurlungr.app.projects.ParsedDiff
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextDiffTest {
    @Test fun `same text has no diff`() {
        assertEquals("", TextDiff.unified("a\nb\n", "a\nb\n"))
    }

    @Test fun `a changed line comes with its context`() {
        val old = (1..10).joinToString("\n") { "line $it" }
        val new = old.replace("line 5", "line five")
        assertEquals(
            "@@ -2,7 +2,7 @@\n line 2\n line 3\n line 4\n-line 5\n+line five\n line 6\n line 7\n line 8\n",
            TextDiff.unified(old, new),
        )
    }

    @Test fun `far apart changes get their own hunks, and the viewer reads them`() {
        val old = (1..30).joinToString("\n") { "l$it" }
        val new = old.replace("l2\n", "l2\nadded\n").replace("l28", "changed")
        val diff = TextDiff.unified(old, new)
        assertEquals(2, diff.lines().count { it.startsWith("@@") })
        val parsed = ParsedDiff.parse(diff)
        assertEquals(2, parsed.added)
        assertEquals(1, parsed.removed)
        val added = parsed.lines.first { it.kind == DiffLine.Kind.Added }
        assertEquals("added", added.text)
        assertEquals(3, added.newNo)
    }

    @Test fun `insertions and deletions at the ends`() {
        assertEquals("@@ -1,1 +1,2 @@\n+top\n a\n", TextDiff.unified("a", "top\na"))
        assertEquals("@@ -1,2 +1,1 @@\n a\n-b\n", TextDiff.unified("a\nb", "a"))
        assertTrue(TextDiff.unified("", "x").contains("+x"))
    }
}
