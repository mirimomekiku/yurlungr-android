package dev.yurlungr.app.projects

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChangeGroupsTest {
    @Test fun `a file can be both staged and have further edits`() {
        val mm = GitChange("a.kt", 'M', 'M')
        assertTrue(mm.isStaged)
        assertTrue(mm.hasUnstaged)
    }

    @Test fun `untracked files are only in Changes, staged-only files only in Staged`() {
        val new = GitChange("n.kt", '?', '?')
        assertFalse(new.isStaged)
        assertTrue(new.hasUnstaged)
        val added = GitChange("b.kt", 'A', ' ')
        assertTrue(added.isStaged)
        assertFalse(added.hasUnstaged)
    }

    @Test fun `conflicts belong to neither group`() {
        val c = GitChange("c.kt", 'U', 'U')
        assertEquals("conflict", c.label)
        assertFalse(c.isStaged)
        assertFalse(c.hasUnstaged)
    }

    @Test fun `folders count the changed files inside them at any depth`() {
        val state = FileTreeGitState(emptySet(), listOf(
            GitChange("src/a.kt", ' ', 'M'), GitChange("src/ui/b.kt", '?', '?'), GitChange("README.md", ' ', 'M'),
        ))
        assertEquals(2, state.changedInside["src"])
        assertEquals(1, state.changedInside["src/ui"])
        assertEquals(null, state.changedInside["README.md"])
    }
}
