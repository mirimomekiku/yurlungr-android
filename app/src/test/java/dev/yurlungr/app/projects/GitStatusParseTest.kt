package dev.yurlungr.app.projects

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitStatusParseTest {
    private fun z(vararg tokens: String) = tokens.joinToString("\u0000", postfix = "\u0000")

    @Test
    fun `branch with upstream and ahead-behind`() {
        val s = Git.parseStatus(z("## main...origin/main [ahead 2, behind 1]", " M src/App.kt", "?? notes.md"))
        assertTrue(s.isRepo)
        assertEquals("main", s.branch)
        assertEquals("origin/main", s.upstream)
        assertEquals(2, s.ahead)
        assertEquals(1, s.behind)
        assertFalse(s.unborn)
        assertEquals(listOf("src/App.kt", "notes.md"), s.changes.map { it.path })
        assertEquals("modified", s.changes[0].label)
        assertEquals("new", s.changes[1].label)
        assertTrue(s.changes[1].untracked)
    }

    @Test
    fun `branch without upstream`() {
        val s = Git.parseStatus(z("## feature/login", "A  a.txt"))
        assertEquals("feature/login", s.branch)
        assertNull(s.upstream)
        assertEquals("added", s.changes.single().label)
    }

    @Test
    fun `unborn repository`() {
        val s = Git.parseStatus(z("## No commits yet on main", "?? README.md"))
        assertTrue(s.unborn)
        assertEquals("main", s.branch)
    }

    @Test
    fun `renames carry the original path as the next token`() {
        val s = Git.parseStatus(z("## main", "R  new name.kt", "old name.kt", " D gone.txt"))
        assertEquals(2, s.changes.size)
        val rename = s.changes[0]
        assertEquals("new name.kt", rename.path)
        assertEquals("old name.kt", rename.origPath)
        assertEquals("renamed", rename.label)
        assertEquals("deleted", s.changes[1].label)
    }

    @Test
    fun `detached head and conflicts`() {
        val s = Git.parseStatus(z("## HEAD (no branch)", "UU merge.kt"))
        assertEquals("detached HEAD", s.branch)
        assertEquals("conflict", s.changes.single().label)
    }

    @Test
    fun `trailing newline from the runner is ignored`() {
        val s = Git.parseStatus(z("## main", " M a") + "\n")
        assertEquals(listOf("a"), s.changes.map { it.path })
    }

    @Test
    fun `shell quoting`() {
        assertEquals("'plain'", Git.shellQuote("plain"))
        assertEquals("'it'\\''s'", Git.shellQuote("it's"))
    }
}
