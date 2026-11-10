package dev.yurlungr.app.projects

import java.io.File

/** Where a routine's conflicts came from, which decides how they're resolved. */
enum class ConflictStage {
    /** `git pull` merged and conflicted: resolve, then commit the merge. */
    Pull,
    /** Re-applying the user's stashed changes conflicted: resolve, keep uncommitted, drop the stash. */
    RestoreChanges,
}

sealed interface RoutineOutcome {
    val message: String

    data class Done(override val message: String) : RoutineOutcome

    data class Conflicts(
        val files: List<String>,
        val stage: ConflictStage,
        /** The user's own changes are still in stash@{0}, to be re-applied once this is resolved. */
        val stashKept: Boolean,
        override val message: String,
    ) : RoutineOutcome

    data class Failed(override val message: String) : RoutineOutcome
}

/**
 * Multi-step git routines ("get the latest changes" = stash → pull → stash pop) that stop at the
 * first problem and say exactly where things were left, so the user (or an agent) can carry on.
 */
class GitRoutines(private val git: Git) {
    /**
     * Saves local changes, pulls (merging if the branches diverged), and puts the changes back.
     * Conflicts at either step are returned, not hidden; nothing is ever discarded.
     */
    suspend fun getLatest(dir: File, onStep: (String) -> Unit = {}): RoutineOutcome {
        onStep("Checking the repository…")
        val status = git.status(dir)
        status.error?.let { return RoutineOutcome.Failed(it.message) }
        if (!status.isRepo) return RoutineOutcome.Failed("This folder isn't a git repository.")
        if (status.conflicts.isNotEmpty()) {
            return RoutineOutcome.Conflicts(
                status.conflicts, ConflictStage.Pull, stashKept = false,
                "There are unresolved conflicts from earlier. Resolve them first.",
            )
        }
        if (!status.hasOrigin) return RoutineOutcome.Failed("There's no remote (origin) to get changes from.")
        if (status.upstream == null) {
            return RoutineOutcome.Failed("The branch ${status.branch ?: ""} isn't on the remote yet. Push it once first.".replace("  ", " "))
        }

        val dirty = status.changes.isNotEmpty()
        val before = git.head(dir)
        if (dirty) {
            onStep("Saving your local changes (git stash)…")
            val saved = git.stash(dir, STASH_MESSAGE)
            if (!saved.ok) return RoutineOutcome.Failed(saved.describe("Saving your local changes"))
        }

        onStep("Getting the latest changes (git pull)…")
        val pull = git.pullMerge(dir)
        if (!pull.ok) {
            val after = git.status(dir)
            if (after.conflicts.isNotEmpty()) {
                return RoutineOutcome.Conflicts(
                    after.conflicts, ConflictStage.Pull, stashKept = dirty,
                    "The latest changes conflict with commits on this branch in ${after.conflicts.size} file(s)." +
                        if (dirty) " Your uncommitted changes are saved in the stash meanwhile." else "",
                )
            }
            // Nothing merged: put the user's work back exactly as it was.
            if (dirty) {
                onStep("Putting your changes back…")
                val back = git.stashPopLatest(dir)
                if (!back.ok) return RoutineOutcome.Failed(pull.describe("git pull") + "\nYour changes are saved in stash@{0}; restoring them failed.")
            }
            return RoutineOutcome.Failed(pull.describe("git pull"))
        }
        val pulled = if (before != null) git.countCommits(dir, before, "HEAD") else 0

        if (dirty) {
            onStep("Putting your changes back (git stash pop)…")
            val pop = git.stashPopLatest(dir)
            if (!pop.ok) {
                val after = git.status(dir)
                if (after.conflicts.isNotEmpty()) {
                    return RoutineOutcome.Conflicts(
                        after.conflicts, ConflictStage.RestoreChanges, stashKept = true,
                        "Got the latest changes, but your own changes conflict with them in ${after.conflicts.size} file(s). " +
                            "They're still saved in the stash.",
                    )
                }
                return RoutineOutcome.Failed(pop.describe("Putting your changes back") + "\nThey're still saved in stash@{0}.")
            }
        }
        val got = when (pulled) {
            0 -> "Already up to date."
            1 -> "Got 1 new commit."
            else -> "Got $pulled new commits."
        }
        return RoutineOutcome.Done(got + if (dirty) " Your local changes are back in place." else "")
    }

    /** [getLatest], then pushes this branch's own commits (if any). */
    suspend fun sync(dir: File, onStep: (String) -> Unit = {}): RoutineOutcome {
        val latest = getLatest(dir, onStep)
        if (latest !is RoutineOutcome.Done) return latest
        val status = git.status(dir)
        if (status.ahead == 0) return RoutineOutcome.Done(latest.message + " Nothing to push.")
        onStep("Sending your commits (git push)…")
        val push = git.push(dir, status)
        if (!push.ok) return RoutineOutcome.Failed(latest.message + "\n" + push.describe("git push"))
        return RoutineOutcome.Done(latest.message + " Pushed ${status.ahead} commit${if (status.ahead == 1) "" else "s"}.")
    }

    /** Undoes the last commit, keeping its changes, but only while nobody else can have it (not pushed). */
    suspend fun undoLastCommit(dir: File): RoutineOutcome {
        val head = git.head(dir) ?: return RoutineOutcome.Failed("There's no commit to undo.")
        val status = git.status(dir)
        if (status.hasOrigin && head !in git.unpushed(dir)) {
            return RoutineOutcome.Failed("The last commit is already pushed. Undoing it would rewrite shared history.")
        }
        if (git.countCommits(dir, "HEAD~1", "HEAD") == 0) return RoutineOutcome.Failed("The first commit can't be undone this way.")
        val r = git.undoLastCommit(dir)
        return if (r.ok) RoutineOutcome.Done("Undid the last commit. Its changes are kept, ready to commit again.")
        else RoutineOutcome.Failed(r.describe("Undoing the last commit"))
    }

    companion object {
        const val STASH_MESSAGE = "Yurlungr: before getting the latest changes"

        private fun fileList(files: List<String>) = files.take(40).joinToString("\n") { "- $it" } +
            if (files.size > 40) "\n- …and ${files.size - 40} more (see `git status`)" else ""

        /** The agent's instructions for resolving [c], ending in the state a careful person would leave. */
        fun conflictPrompt(c: RoutineOutcome.Conflicts, branch: String?, upstream: String?): String = buildString {
            val where = listOfNotNull(branch?.let { "branch `$it`" }, upstream?.let { "its upstream `$it`" }).joinToString(" and ")
            when (c.stage) {
                ConflictStage.Pull -> appendLine("I pulled the latest changes${if (where.isNotEmpty()) " ($where)" else ""} and the merge has conflicts in:")
                ConflictStage.RestoreChanges -> appendLine("I pulled the latest changes, then re-applied my own uncommitted work (`git stash pop`), which conflicts in:")
            }
            appendLine(fileList(c.files))
            appendLine()
            appendLine("Please resolve them:")
            appendLine("1. For each file, read both sides and combine them so neither side's intent is lost. Remove every conflict marker (<<<<<<<, =======, >>>>>>>).")
            appendLine("2. If two sides truly can't be combined, prefer ${if (c.stage == ConflictStage.RestoreChanges) "my local changes" else "the incoming changes for code you don't understand, and my side for my own work"}, and tell me what you chose.")
            appendLine("3. Make sure nothing else is broken: search for leftover markers, and run the project's build or tests if there's an obvious command.")
            when (c.stage) {
                ConflictStage.Pull -> {
                    appendLine("4. `git add` the resolved files and finish the merge with `git commit --no-edit`.")
                    if (c.stashKept) appendLine("5. Then bring my saved uncommitted work back with `git stash pop`, resolving any conflicts the same way and leaving those changes uncommitted.")
                }
                ConflictStage.RestoreChanges -> {
                    appendLine("4. Leave the result uncommitted: it's my work in progress. Unstage the files (`git restore --staged <file>`) once resolved.")
                    appendLine("5. When everything is resolved, drop the stash entry that was applied: `git stash drop`.")
                }
            }
            appendLine()
            append("Don't push. Finish with a short summary of what you did in each file.")
        }

        /** For conflicts already in the working tree (e.g. from an earlier pull). */
        fun fixConflictsPrompt(files: List<String>, mergeInProgress: Boolean): String = buildString {
            appendLine("This repository has unresolved conflicts in:")
            appendLine(fileList(files))
            appendLine()
            appendLine("Resolve each one: read both sides, combine them so neither side's intent is lost, and remove every conflict marker. Then check for leftover markers and run the build or tests if there's an obvious command.")
            if (mergeInProgress) appendLine("A merge is in progress: `git add` the resolved files and finish it with `git commit --no-edit`.")
            else appendLine("No merge is in progress (these came from re-applying stashed work): leave the result uncommitted and unstaged, and `git stash drop` the applied stash if `git stash list` still shows it.")
            append("Don't push. Finish with a short summary of what you did in each file.")
        }

        fun commitPrompt(): String = """
            Please commit my uncommitted changes:
            1. Look at `git status` and `git diff` (and new untracked files) to understand what changed and why.
            2. Group changes that belong together; if there are unrelated changes, make separate commits.
            3. Write each message in the style of this repository's recent history (`git log --oneline -10`): a short summary line, and a body explaining why when it isn't obvious.
            4. Don't commit secrets, build output or anything .gitignore should cover; tell me if you skip something.
            Don't push. Finish with the commits you made.
        """.trimIndent()

        fun reviewPrompt(): String = """
            Please review my uncommitted changes (`git status`, `git diff`, and new untracked files) like a careful senior reviewer.
            Look for bugs, unhandled errors and edge cases, security problems, leftover debug code, and anything that doesn't match the rest of the code.
            Don't change any files. List the problems by importance, each with the file and line and a suggested fix, and say plainly if everything looks fine.
        """.trimIndent()
    }
}
