package dev.yurlungr.app.projects

import org.junit.Assert.assertEquals
import org.junit.Test

class GitNameStatusTest {
    @Test fun `paths are taken verbatim, including unicode, quotes and tabs`() {
        val out = "M\u0000café.md\u0000A\u0000say \"hi\".txt\u0000D\u0000tab\there\u0000"
        assertEquals(
            listOf(CommitFile('M', "café.md"), CommitFile('A', "say \"hi\".txt"), CommitFile('D', "tab\there")),
            Git.parseNameStatus(out),
        )
    }

    @Test fun `renames and copies report the new path`() {
        val out = "R100\u0000old/name.kt\u0000new/name.kt\u0000C75\u0000a.txt\u0000b.txt\u0000M\u0000c.txt\u0000"
        assertEquals(
            listOf(CommitFile('R', "new/name.kt"), CommitFile('C', "b.txt"), CommitFile('M', "c.txt")),
            Git.parseNameStatus(out),
        )
    }

    @Test fun `empty and truncated output`() {
        assertEquals(emptyList<CommitFile>(), Git.parseNameStatus(""))
        assertEquals(listOf(CommitFile('M', "a")), Git.parseNameStatus("M\u0000a\u0000R100\u0000only-old"))
    }

    @Test fun `a rename in the worktree column consumes its original path`() {
        val status = Git.parseStatus("## main\u0000 R new.txt\u0000old.txt\u0000 M other.txt\u0000")
        assertEquals(listOf(GitChange("new.txt", ' ', 'R', "old.txt"), GitChange("other.txt", ' ', 'M')), status.changes)
        assertEquals("renamed", status.changes[0].label)
    }
}
