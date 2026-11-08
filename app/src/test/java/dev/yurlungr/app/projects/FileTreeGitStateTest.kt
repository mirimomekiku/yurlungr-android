package dev.yurlungr.app.projects

import org.junit.Assert.*
import org.junit.Test

class FileTreeGitStateTest {
    @Test fun `ignored directories apply to children but not similarly named siblings`() {
        val state = FileTreeGitState(setOf("build/", "src/private.env"), emptyList())
        assertTrue(state.isIgnored("build"))
        assertTrue(state.isIgnored("build/deep/cache.bin"))
        assertTrue(state.isIgnored("src/private.env"))
        assertFalse(state.isIgnored("builder/file.kt"))
        assertFalse(state.isIgnored("src/private.env.example"))
        assertFalse(state.isIgnored("src"))
    }

    @Test fun `ignored filter preserves ancestors and not ignored filter hides only ignored paths`() {
        val state = FileTreeGitState(setOf("src/generated/cache/", "src/local.env"), emptyList())
        assertTrue(state.includes("src", true, FileTreeFilter.Ignored))
        assertTrue(state.includes("src/generated", true, FileTreeFilter.Ignored))
        assertTrue(state.includes("src/generated/cache/data", false, FileTreeFilter.Ignored))
        assertFalse(state.includes("src/clean.kt", false, FileTreeFilter.Ignored))
        assertTrue(state.includes("src", true, FileTreeFilter.NotIgnored))
        assertFalse(state.includes("src/generated/cache", true, FileTreeFilter.NotIgnored))
    }

    @Test fun `uncommitted includes staged untracked renamed conflicts and deleted descendants`() {
        val changes = listOf(
            GitChange("src/edit.kt", ' ', 'M'), GitChange("new.txt", '?', '?'),
            GitChange("docs/new.md", 'R', ' ', "old.md"), GitChange("src/conflict.kt", 'U', 'U'),
            GitChange("deleted/file.kt", 'D', ' '),
        )
        val state = FileTreeGitState(emptySet(), changes)
        for (change in changes) assertTrue(state.includes(change.path, false, FileTreeFilter.Changed))
        assertTrue(state.includes("src", true, FileTreeFilter.Changed))
        assertTrue(state.includes("docs", true, FileTreeFilter.Changed))
        assertTrue(state.includes("deleted", true, FileTreeFilter.Changed))
        assertFalse(state.includes("src/clean.kt", false, FileTreeFilter.Changed))
        assertFalse(state.includes("src-other", true, FileTreeFilter.Changed))
    }

    @Test fun `NUL paths preserve spaces and newlines while ignoring runner trailing newline`() {
        assertEquals(setOf("cache dir/", "src/a\nb.tmp"), FileTreeGitState.parseIgnoredPaths("cache dir/\u0000src/a\nb.tmp\u0000\n"))
        assertTrue(FileTreeGitState.parseIgnoredPaths("").isEmpty())
    }
}
