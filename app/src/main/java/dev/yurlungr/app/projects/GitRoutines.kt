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
        /** The user's own changes are still in a stash entry, to be re-applied once this is resolved. */
        val stashKept: Boolean,
        override val message: String,
        /** That entry's commit, so it's found again by what it is, not by its position. */
        val stashSha: String? = null,
    ) : RoutineOutcome

    data class Failed(override val message: String) : RoutineOutcome
}

/** Told about the stash a routine makes with the user's work, so the app can offer it back until it's restored. */
interface StashTracker {
    /** [stash] now holds the user's changes. */
    fun saved(dir: File, stash: TrackedStash) {}
    /** It was popped cleanly: nothing to track. */
    fun restored(dir: File) {}
    /** It was re-applied with conflicts and git kept the entry: the changes are in the files now. */
    fun appliedWithConflicts(dir: File, stash: TrackedStash) {}
}

/**
 * Multi-step git routines ("get the latest changes" = stash → pull → stash pop) that stop at the
 * first problem and say exactly where things were left, so the user (or an agent) can carry on.
 */
class GitRoutines(private val git: Git, private val stashes: StashTracker = object : StashTracker {}) {
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

        val before = git.head(dir)
        // Only what was actually stashed is popped later: `git stash` exits 0 with nothing to save
        // (e.g. only ignored files changed), and popping then would apply an older, unrelated stash.
        // It's popped by its commit, not as stash@{0}: something else may stash meanwhile.
        var stash: TrackedStash? = null
        if (status.changes.isNotEmpty()) {
            onStep("Saving your local changes (git stash)…")
            val (saved, created) = git.stashSaving(dir, STASH_MESSAGE)
            if (!saved.ok) return RoutineOutcome.Failed(saved.describe("Saving your local changes"))
            if (created) {
                val sha = git.stashTop(dir) ?: return RoutineOutcome.Failed("Your changes were stashed, but the stash entry can't be read. They're in `git stash list`.")
                stash = TrackedStash(sha, STASH_MESSAGE).also { stashes.saved(dir, it) }
            }
        }
        val stashed = stash != null

        onStep("Getting the latest changes (git pull)…")
        val pull = git.pullMerge(dir)
        if (!pull.ok) {
            val after = git.status(dir)
            if (after.conflicts.isNotEmpty()) {
                return RoutineOutcome.Conflicts(
                    after.conflicts, ConflictStage.Pull, stashKept = stashed,
                    "The latest changes conflict with commits on this branch in ${after.conflicts.size} file(s)." +
                        if (stashed) " Your uncommitted changes are saved in the stash meanwhile." else "",
                    stashSha = stash?.sha,
                )
            }
            // Nothing merged: put the user's work back exactly as it was.
            if (stash != null) {
                onStep("Putting your changes back…")
                val back = git.stashPopSha(dir, stash.sha)
                if (!back.ok) return RoutineOutcome.Failed(pull.describe("git pull") + "\nYour changes are still stashed (Changes tab → Restore); restoring them failed.")
                stashes.restored(dir)
            }
            return RoutineOutcome.Failed(pull.describe("git pull"))
        }
        val pulled = if (before != null) git.countCommits(dir, before, "HEAD") else 0

        if (stash != null) {
            onStep("Putting your changes back (git stash pop)…")
            val pop = git.stashPopSha(dir, stash.sha)
            if (!pop.ok) {
                val after = git.status(dir)
                if (after.conflicts.isNotEmpty()) {
                    stashes.appliedWithConflicts(dir, stash.copy(applied = true))
                    return RoutineOutcome.Conflicts(
                        after.conflicts, ConflictStage.RestoreChanges, stashKept = true,
                        "Got the latest changes, but your own changes conflict with them in ${after.conflicts.size} file(s). " +
                            "They're still saved in the stash.",
                        stashSha = stash.sha,
                    )
                }
                return RoutineOutcome.Failed(pop.describe("Putting your changes back") + "\nThey're still stashed (Changes tab → Restore).")
            }
            stashes.restored(dir)
        }
        val got = when (pulled) {
            0 -> "Already up to date."
            1 -> "Got 1 new commit."
            else -> "Got $pulled new commits."
        }
        return RoutineOutcome.Done(got + if (stashed) " Your local changes are back in place." else "")
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
        // Any remote, not just origin: a fork's upstream or a second remote has it just the same.
        if (git.onAnyRemote(dir, head)) {
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

        /** How to find and act on the stash entry with commit [sha]: its position shifts, its commit doesn't. */
        private fun stashBySha(sha: String, action: String) =
            "find the entry whose commit is `$sha` (`git stash list --format='%gd %H'`) and run `git stash $action stash@{N}` with its number; " +
                "never a bare `git stash $action`, which acts on whatever entry is newest"

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
                    if (c.stashKept) {
                        appendLine("5. Then bring my saved uncommitted work back with `git stash pop`: " +
                            (c.stashSha?.let { stashBySha(it, "pop") + "." } ?: "the entry named \"$STASH_MESSAGE\".") +
                            " Resolve any conflicts the same way and leave those changes uncommitted.")
                    }
                }
                ConflictStage.RestoreChanges -> {
                    appendLine("4. Leave the result uncommitted: it's my work in progress. Unstage the files (`git restore --staged <file>`) once resolved.")
                    appendLine("5. When everything is resolved, drop the stash entry that was applied (`git stash drop`): " +
                        (c.stashSha?.let { stashBySha(it, "drop") + "." } ?: "the entry named \"$STASH_MESSAGE\", only if it's still listed."))
                }
            }
            appendLine()
            append("Don't push. Finish with a short summary of what you did in each file.")
        }

        /**
         * For conflicts already in the working tree. [operation]: the merge, rebase, cherry-pick or
         * revert they're part of, which decides how it's finished. [appStash]: the app's own stash
         * that was re-applied with conflicts, when that's where they came from (and no operation
         * is in progress); only then is a stash entry dropped, and only that one.
         */
        fun fixConflictsPrompt(files: List<String>, operation: GitOperation?, appStash: TrackedStash? = null): String = buildString {
            appendLine("This repository has unresolved conflicts in:")
            appendLine(fileList(files))
            appendLine()
            appendLine("Resolve each one: read both sides, combine them so neither side's intent is lost, and remove every conflict marker. Then check for leftover markers and run the build or tests if there's an obvious command.")
            when (operation) {
                GitOperation.Merge ->
                    appendLine("A merge is in progress: `git add` the resolved files and finish it with `git commit --no-edit`.")
                GitOperation.Rebase, GitOperation.ApplyMailbox, GitOperation.CherryPick, GitOperation.Revert -> {
                    appendLine("A ${operation.label} is in progress (it stopped on these conflicts): `git add` the resolved files, then continue it with " +
                        "`GIT_EDITOR=true ${operation.continueCommand}`. Don't `git commit` yourself, and don't touch the stash.")
                    appendLine("If it stops again on the next commit's conflicts, resolve those the same way and continue until it's done. " +
                        "If it can't be finished sensibly, stop and ask me: `${operation.abortCommand}` would undo all of it.")
                }
                null -> if (appStash != null) {
                    appendLine("No merge or rebase is in progress: these came from re-applying my stashed uncommitted work. Leave the result uncommitted and unstaged (`git restore --staged <file>`).")
                    appendLine("Then drop that stash entry, which is only a backup now: ${stashBySha(appStash.sha, "drop")}. Drop nothing else.")
                } else {
                    appendLine("No merge, rebase, cherry-pick or revert is in progress (they may come from `git stash pop` or `git checkout -m`). Leave the result uncommitted and unstaged (`git restore --staged <file>`).")
                    appendLine("Don't drop or pop any stash entry: if `git stash list` has one that looks already applied, tell me instead.")
                }
            }
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
