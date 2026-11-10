package dev.yurlungr.app.projects

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitRoutinesTest {
    @Test fun `unmerged paths count as conflicts, ordinary changes don't`() {
        // Porcelain v1 unmerged states: UU, AA, DD, AU, UA, DU, UD.
        val status = Git.parseStatus(
            "## main...origin/main\u0000UU both.txt\u0000AA added.txt\u0000DD gone.txt\u0000AU a.txt\u0000UD b.txt\u0000 M plain.txt\u0000?? new.txt\u0000",
        )
        assertEquals(listOf("both.txt", "added.txt", "gone.txt", "a.txt", "b.txt"), status.conflicts)
        assertTrue(Git.parseStatus("## main\u0000 M x\u0000").conflicts.isEmpty())
    }

    @Test fun `pull conflicts finish the merge and bring stashed work back`() {
        val prompt = GitRoutines.conflictPrompt(
            RoutineOutcome.Conflicts(listOf("app/a.kt", "b.md"), ConflictStage.Pull, stashKept = true, "m"),
            branch = "main", upstream = "origin/main",
        )
        assertTrue(prompt.contains("- app/a.kt\n- b.md"))
        assertTrue(prompt.contains("`main`") && prompt.contains("`origin/main`"))
        assertTrue(prompt.contains("git commit --no-edit"))
        assertTrue("stashed work is restored after the merge", prompt.contains("git stash pop"))
        assertTrue(prompt.contains("Don't push"))
        assertFalse(prompt.contains("git stash drop"))
    }

    @Test fun `stash-pop conflicts stay uncommitted and drop the applied stash`() {
        val prompt = GitRoutines.conflictPrompt(
            RoutineOutcome.Conflicts(listOf("f.txt"), ConflictStage.RestoreChanges, stashKept = true, "m"),
            branch = "main", upstream = null,
        )
        assertTrue(prompt.contains("git stash pop"))
        assertTrue(prompt.contains("Leave the result uncommitted"))
        assertTrue(prompt.contains("git stash drop"))
        assertFalse("never commits the user's work in progress", prompt.contains("git commit --no-edit"))
    }

    @Test fun `long conflict lists are capped`() {
        val files = (1..60).map { "f$it.txt" }
        val prompt = GitRoutines.fixConflictsPrompt(files, mergeInProgress = true)
        assertTrue(prompt.contains("- f40.txt"))
        assertFalse(prompt.contains("- f41.txt"))
        assertTrue(prompt.contains("…and 20 more"))
        assertTrue(prompt.contains("git commit --no-edit"))
        assertTrue(GitRoutines.fixConflictsPrompt(files, mergeInProgress = false).contains("git stash drop"))
    }

    @Test fun `commit and review prompts never push or edit`() {
        assertTrue(GitRoutines.commitPrompt().contains("Don't push"))
        assertTrue(GitRoutines.reviewPrompt().contains("Don't change any files"))
    }
}
