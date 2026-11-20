package dev.yurlungr.app.projects

import dev.yurlungr.app.runtime.CommandResult
import dev.yurlungr.app.runtime.CommandRunner
import dev.yurlungr.app.runtime.RuntimePaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/** An added line that opens or closes a conflict, as git writes them (7 marks, then a space or nothing). */
internal val CONFLICT_MARKER = Regex("^\\+(<{7}|>{7})( |$)")

data class GitChange(
    val path: String,
    /** Porcelain status letters: [index] (staged) and [worktree]. '?' = untracked. */
    val index: Char,
    val worktree: Char,
    val origPath: String? = null,
) {
    val untracked: Boolean get() = index == '?'

    /** Unmerged (porcelain): U in either column, or both sides added (AA) or deleted (DD). */
    val isConflict: Boolean
        get() = index == 'U' || worktree == 'U' || (index == 'A' && worktree == 'A') || (index == 'D' && worktree == 'D')

    val label: String
        get() = when {
            // Before "added"/"deleted": AU, UD, AA, DD... are conflicts, not ordinary changes.
            isConflict -> "conflict"
            untracked -> "new"
            index == 'R' || worktree == 'R' -> "renamed"
            index == 'A' -> "added"
            index == 'D' || worktree == 'D' -> "deleted"
            else -> "modified"
        }
}

data class GitStatus(
    val isRepo: Boolean,
    val branch: String? = null,
    val upstream: String? = null,
    val ahead: Int = 0,
    val behind: Int = 0,
    /** No commits yet (HEAD doesn't exist). */
    val unborn: Boolean = false,
    val changes: List<GitChange> = emptyList(),
    /** Has a remote named "origin" (where Push goes). */
    val hasOrigin: Boolean = false,
    /** Git couldn't read the repository (or isn't installed): [isRepo] is false but it may well be one. */
    val error: GitError? = null,
) {
    /** Paths with unresolved merge conflicts (porcelain "unmerged": U in either column, AA or DD). */
    val conflicts: List<String>
        get() = changes.filter { it.isConflict }.map { it.path }

    /** HEAD is a commit, not a branch: there's nothing to push or publish until a branch is made here. */
    val detached: Boolean get() = isRepo && branch == DETACHED

    companion object {
        val NotARepo = GitStatus(isRepo = false)

        /** What [Git.parseStatus] names the branch when HEAD is detached. */
        const val DETACHED = "detached HEAD"
    }
}

/** Why git couldn't answer; [toolMissing] means installing the developer tools is the fix. */
data class GitError(val message: String, val toolMissing: Boolean)

/** A failed git command, for the operations that return [Result]. */
class GitException(val error: GitError) : Exception(error.message)

/**
 * A commit for the history graph. [refs] are git's decorations ("HEAD -> main", "origin/main",
 * "tag: v1"); [time] is the committer date in epoch millis.
 */
data class GitCommit(
    val hash: String,
    val parents: List<String>,
    val author: String,
    val time: Long,
    val refs: List<String>,
    val subject: String,
) {
    val short: String get() = hash.take(7)
}

/** One changed file in a commit (git status letter + path). */
data class CommitFile(val status: Char, val path: String)

/** gh exists but can't do its job right now. */
enum class GhProblem {
    /** GitHub can't be reached (offline, DNS, timeout); a saved sign-in may still be fine. */
    Unreachable,
    /** The saved sign-in was rejected (expired or revoked token): sign in again. */
    SignInExpired,
    /** gh itself fails (can't run, or an unexpected error). */
    Broken,
}

data class GhStatus(
    val installed: Boolean,
    val signedIn: Boolean,
    val account: String?,
    val problem: GhProblem? = null,
    /** gh's own words about [problem], for showing to the user. */
    val detail: String? = null,
)

/**
 * The signed-in account's repositories; [orgError] says why some organisations' repos are missing.
 * [orgs]: the organisations the account belongs to (their logins), to tell the user's own repositories apart.
 */
data class GhRepoList(val repos: List<GhRepo>, val orgError: String? = null, val orgs: List<String> = emptyList())

/** A branch; remote ones are named "origin/x" and only listed when there's no local "x". */
data class GitBranch(val name: String, val isRemote: Boolean, val upstream: String?, val updated: String, val isCurrent: Boolean)

/** The pull request of the current branch, from `gh pr view`. */
data class GitPullRequest(val number: Int, val title: String, val state: String, val url: String, val isDraft: Boolean = false)

/**
 * A stash entry; [ref] is "stash@{n}" when it was listed, and shifts as entries are added or
 * dropped, so actions go by [sha] (the stash commit), which doesn't. Empty [sha]: not known.
 */
data class GitStash(val ref: String, val message: String, val whenRelative: String, val sha: String = "")

/**
 * A multi-step git command stopped half way, waiting for the conflicts to be resolved. Each is
 * finished or undone with its own command: committing a rebase by hand, or dropping a stash to
 * "finish" a cherry-pick, would leave the repository in a worse state.
 */
enum class GitOperation(
    val label: String,
    /** What finishes it once the conflicts are resolved and staged. */
    val continueArgs: List<String>,
    val abortArgs: List<String>,
) {
    Merge("merge", listOf("commit", "--no-edit"), listOf("merge", "--abort")),
    Rebase("rebase", listOf("rebase", "--continue"), listOf("rebase", "--abort")),
    /** `git am` applying mailed patches (it shares rebase-apply/ with old-style rebases). */
    ApplyMailbox("patch series (git am)", listOf("am", "--continue"), listOf("am", "--abort")),
    CherryPick("cherry-pick", listOf("cherry-pick", "--continue"), listOf("cherry-pick", "--abort")),
    Revert("revert", listOf("revert", "--continue"), listOf("revert", "--abort"));

    val continueCommand: String get() = "git " + continueArgs.joinToString(" ")
    val abortCommand: String get() = "git " + abortArgs.joinToString(" ")

    companion object {
        /** The paths [Git.operationInProgress] asks `git rev-parse --git-path` for, in this order. */
        val GIT_PATHS = listOf("rebase-merge", "rebase-apply", "rebase-apply/applying", "MERGE_HEAD", "CHERRY_PICK_HEAD", "REVERT_HEAD")

        /**
         * Which operation is in progress, from which of [GIT_PATHS] exist. A rebase wins: while
         * one stops on a conflict it can leave other state files around, but only `rebase
         * --continue` moves it on.
         */
        fun detect(exists: (String) -> Boolean): GitOperation? = when {
            exists("rebase-merge") -> Rebase
            exists("rebase-apply") -> if (exists("rebase-apply/applying")) ApplyMailbox else Rebase
            exists("MERGE_HEAD") -> Merge
            exists("CHERRY_PICK_HEAD") -> CherryPick
            exists("REVERT_HEAD") -> Revert
            else -> null
        }
    }
}

/** A local branch offered for clean-up: [gone] = its upstream was deleted on the remote. */
data class CleanupBranch(val name: String, val gone: Boolean, val merged: Boolean)

/** A repository on the signed-in GitHub account. [slug] is "owner/name". */
data class GhRepo(
    val slug: String,
    val name: String,
    val description: String?,
    val isPrivate: Boolean,
    val isFork: Boolean,
    /** Last push, epoch millis (0 if unknown). */
    val updatedAt: Long,
    /** Owned by an organization the user belongs to (not the user's own account). */
    val isOrg: Boolean = false,
) {
    val owner: String get() = slug.substringBefore('/')
}

/** Git and GitHub CLI operations, run inside the runtime. */
class Git(private val paths: RuntimePaths) {
    private val git = File(paths.bin, "git").path

    private val env = ENV

    private suspend fun run(dir: File, vararg args: String, timeoutMs: Long = 60_000, extraEnv: Map<String, String> = emptyMap()): CommandResult =
        CommandRunner.run(paths, listOf(git, "-C", dir.path, *args), cwd = dir, timeoutMs = timeoutMs, extraEnv = env + extraEnv)

    /** For output that gets parsed: exact stdout, stderr kept apart (warnings can't corrupt it), large cap. */
    private suspend fun runParsed(dir: File, vararg args: String, timeoutMs: Long = 60_000): CommandResult =
        CommandRunner.run(paths, listOf(git, "-C", dir.path, *args), cwd = dir, timeoutMs = timeoutMs, extraEnv = env, forParsing = true)
            .also { if (it.truncated) android.util.Log.w("Git", "Output of git ${args.firstOrNull()} was truncated") }

    /**
     * [allUntracked]: every untracked file (for the changes list); false collapses new folders to
     * one entry, which is much cheaper and enough for counts (the projects list).
     */
    suspend fun status(dir: File, allUntracked: Boolean = true): GitStatus {
        if (!File(dir, ".git").exists()) return GitStatus.NotARepo
        val r = runParsed(dir, "status", "--porcelain=v1", "-b", "-z", if (allUntracked) "-uall" else "-unormal")
        if (!r.ok) {
            // A broken .git (not an actual repository) is still "not a repo"; anything else is an error.
            if (!r.toolMissing && r.errorOutput.contains("not a git repository", ignoreCase = true)) return GitStatus.NotARepo
            return GitStatus(isRepo = false, error = errorOf(r, "git status"))
        }
        val remotes = runParsed(dir, "remote").output.lines().map { it.trim() }
        return parseStatus(r.output).copy(hasOrigin = "origin" in remotes)
    }

    /** Ignored untracked paths, collapsing entire ignored directories to avoid scanning build caches. */
    suspend fun ignoredPaths(dir: File): Set<String>? {
        val result = runParsed(dir, "ls-files", "--others", "--ignored", "--exclude-standard", "--directory", "-z")
        return if (result.ok) FileTreeGitState.parseIgnoredPaths(result.output) else null
    }

    /**
     * Unified diff of one change: against HEAD ([DiffSide.All]), or only its staged or unstaged
     * part, which is what hunk staging applies to the index. New and unborn files are listed whole.
     */
    suspend fun diff(dir: File, change: GitChange, unborn: Boolean, side: DiffSide = DiffSide.All): String {
        if (change.untracked || (unborn && side != DiffSide.Staged)) return addedFileDiff(File(dir, change.path))
        return diffOutput(runParsed(dir, *diffArgs(change.path, side).toTypedArray()), "git diff")
    }

    private suspend fun addedFileDiff(file: File): String = withContext(Dispatchers.IO) {
        when {
            !file.isFile -> "(not a file)"
            file.length() > 512 * 1024 -> "(new file, ${file.length() / 1024} KB — too large to show)"
            isBinary(file) -> "(new binary file)"
            else -> buildString {
                val lines = file.readLines()
                append("@@ new file: ").append(file.name).append(" @@\n")
                lines.take(2000).forEach { append('+').append(it).append('\n') }
                if (lines.size > 2000) append("… ${lines.size - 2000} more lines\n")
            }
        }
    }

    /** Newest first, topologically ordered (children before parents) for the graph. */
    suspend fun log(
        dir: File,
        count: Int = HISTORY_PAGE,
        allBranches: Boolean = false,
        filter: HistoryFilter = HistoryFilter(),
        /** Commits after the first [skip] ("Load more"). */
        skip: Int = 0,
        /** One file's history, following renames. */
        path: String? = null,
    ): Result<List<GitCommit>> {
        val r = runParsed(dir, *logArgs(count, allBranches, filter, skip, path).toTypedArray())
        if (!r.ok) return failure(r, "git log")
        return Result.success(parseLog(r.output))
    }

    /**
     * The commit a hash (prefix) names, anywhere in the repository: history search only looks at
     * the commits it loaded, so an older one is looked up directly. Null when there's no such commit.
     */
    suspend fun findCommit(dir: File, hash: String): GitCommit? {
        if (!HEX.matches(hash)) return null
        val full = runParsed(dir, "rev-parse", "--verify", "--quiet", "$hash^{commit}", timeoutMs = 10_000)
            .takeIf { it.ok }?.output?.trim()?.takeIf { it.length == 40 } ?: return null
        val r = runParsed(dir, "log", "-n", "1", "--no-color", LOG_FORMAT, full, timeoutMs = 10_000)
        return if (r.ok) parseLog(r.output).firstOrNull() else null
    }

    /** Commit authors, most commits first (for the history's author filter). */
    suspend fun authors(dir: File, allBranches: Boolean): List<String> {
        val args = mutableListOf("log", "-n", "5000", "--format=%an")
        if (allBranches) args += "--all"
        val r = runParsed(dir, *args.toTypedArray())
        if (!r.ok) return emptyList()
        return r.output.lines().map { it.trim() }.filter { it.isNotEmpty() }
            .groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key }
    }

    /** Commits on HEAD that no remote has yet (empty when there's no remote). */
    suspend fun unpushed(dir: File): Set<String> {
        val r = runParsed(dir, "rev-list", "-n", "500", "HEAD", "--not", "--remotes")
        return if (r.ok) r.output.lines().map { it.trim() }.filter { it.length == 40 }.toSet() else emptySet()
    }

    /** Full message body (after the subject line). */
    suspend fun commitBody(dir: File, hash: String): String =
        run(dir, "show", "-s", "--format=%b", hash).output.trim()

    /** Files changed by a commit, against its first parent (or everything, for the first commit). */
    suspend fun commitFiles(dir: File, hash: String): Result<List<CommitFile>> {
        // -z: paths verbatim (no quoting of non-ASCII, quotes or tabs), so they can be passed back to git.
        val r = runParsed(dir, "diff-tree", "--root", "--no-commit-id", "-r", "-m", "--first-parent", "--name-status", "-z", hash)
        if (!r.ok) return failure(r, "git diff-tree")
        return Result.success(parseNameStatus(r.output))
    }

    suspend fun commitDiff(dir: File, hash: String, path: String): String {
        val r = runParsed(dir, "diff-tree", "--root", "-p", "--no-color", "--no-ext-diff", "-m", "--first-parent", hash, "--", path)
        if (!r.ok) return diffOutput(r, "git diff-tree")
        // diff-tree starts with the commit's id: not part of the diff.
        return diffOutput(r.copy(output = r.output.lines().dropWhile { it.length == 40 && it.all(Char::isLetterOrDigit) }.joinToString("\n")), "git diff-tree")
    }

    /** A diff for showing: capped at [MAX_DIFF_CHARS] with a note saying how much more there is; git's error when it failed. */
    private fun diffOutput(r: CommandResult, what: String): String = when {
        !r.ok -> "(couldn't show the changes)\n… " + r.describe(what).lines().joinToString(" ")
        else -> capDiff(r.output, r.truncated).ifBlank { "(no textual changes)" }
    }

    suspend fun init(dir: File): CommandResult {
        dir.mkdirs()
        val r = run(dir, "init", "-b", "main")
        // Without one, "Commit all" would sweep Gradle caches and build output into the repo.
        if (r.ok) addDefaultGitignore(dir)
        return r
    }

    /** Writes a standard .gitignore if the project has none. Returns false if one already exists. */
    suspend fun addDefaultGitignore(dir: File): Boolean = withContext(Dispatchers.IO) {
        val file = File(dir, ".gitignore")
        if (file.exists()) return@withContext false
        file.writeText(DEFAULT_GITIGNORE)
        true
    }

    suspend fun commitAll(dir: File, message: String): CommandResult {
        // `add -A` would mark conflicted files resolved, markers and all.
        unresolved(dir)?.let { return it }
        val add = run(dir, "add", "-A", timeoutMs = WRITE_TIMEOUT_MS)
        if (!add.ok) return add
        markersStaged(dir)?.let { return it }
        return run(dir, "commit", "-m", message, timeoutMs = WRITE_TIMEOUT_MS)
    }

    /** Commits only what's staged. Hooks (lint-staged, tests) can take minutes on a phone: a long limit. */
    suspend fun commitStaged(dir: File, message: String): CommandResult =
        markersStaged(dir) ?: run(dir, "commit", "-m", message, timeoutMs = WRITE_TIMEOUT_MS)

    /** A failure naming the files git still has as unmerged, or null when there are none. */
    private suspend fun unresolved(dir: File): CommandResult? {
        // -z: paths verbatim, not C-quoted (non-ASCII names).
        val names = runParsed(dir, "diff", "--name-only", "-z", "--diff-filter=U").output.split('\u0000').filter { it.isNotBlank() }.distinct()
        return if (names.isEmpty()) null
        else CommandResult(exitCode = 1, output = "Resolve the conflicts first (open each in the merge view): ${names.joinToString()}")
    }

    /** A failure when the staged changes add conflict markers, or null when they don't. */
    private suspend fun markersStaged(dir: File): CommandResult? {
        // quotePath=false: non-ASCII names as they are (only quotes, tabs, backslashes… still get quoted).
        val diff = runParsed(dir, "-c", "core.quotePath=false", "diff", "--cached", "-U0", "--no-color", "--no-ext-diff").output
        val hits = filesWithMarkers(diff)
        return if (hits.isEmpty()) null
        else CommandResult(exitCode = 1, output = "These files still have conflict markers (<<<<<<< / >>>>>>>): ${hits.joinToString()}. Resolve them, then commit.")
    }

    /** Stages [paths] (including deletions). */
    suspend fun stage(dir: File, paths: List<String>): CommandResult =
        if (paths.isEmpty()) CommandResult(exitCode = 0, output = "") else run(dir, "add", "-A", "--", *paths.toTypedArray(), timeoutMs = WRITE_TIMEOUT_MS)

    /** Takes [paths] out of the next commit, keeping the file changes. */
    suspend fun unstage(dir: File, paths: List<String>, unborn: Boolean): CommandResult = when {
        paths.isEmpty() -> CommandResult(exitCode = 0, output = "")
        // No HEAD to restore from yet: just drop them from the index.
        unborn -> run(dir, "rm", "--cached", "-r", "-q", "--", *paths.toTypedArray(), timeoutMs = WRITE_TIMEOUT_MS)
        else -> run(dir, "restore", "--staged", "--", *paths.toTypedArray(), timeoutMs = WRITE_TIMEOUT_MS)
    }

    /** Throws away a file's changes: untracked files are deleted, others go back to HEAD. */
    suspend fun discard(dir: File, change: GitChange): CommandResult = when {
        // "Discard" of a conflict has no single meaning (ours? theirs?): resolve it in the merge view.
        change.isConflict -> CommandResult(exitCode = 1, output = "${change.path} has a merge conflict: resolve it in the merge view instead.")
        change.untracked -> run(dir, "clean", "-f", "-q", "--", change.path)
        change.index == 'A' -> run(dir, "rm", "-f", "-q", "--", change.path)
        else -> run(dir, "restore", "--source=HEAD", "--staged", "--worktree", "--", change.path)
    }

    suspend fun push(dir: File, status: GitStatus): CommandResult =
        if (status.upstream == null) {
            run(dir, "push", "-u", "origin", "HEAD", timeoutMs = 300_000)
        } else {
            run(dir, "push", timeoutMs = 300_000)
        }

    suspend fun pull(dir: File): CommandResult = run(dir, "pull", "--ff-only", timeoutMs = 300_000)

    /**
     * Pull that merges when the branches diverged (instead of refusing like --ff-only), so remote
     * work always lands; conflicts are left for the user or an agent to resolve. --no-edit: no
     * editor can open here.
     */
    suspend fun pullMerge(dir: File): CommandResult = run(dir, "pull", "--no-rebase", "--no-edit", timeoutMs = WRITE_TIMEOUT_MS)

    /** The current commit's hash, or null (no commits yet, or git failed). */
    suspend fun head(dir: File): String? =
        runParsed(dir, "rev-parse", "--verify", "--quiet", "HEAD").takeIf { it.ok }?.output?.trim()?.takeIf { it.length == 40 }

    /** How many commits [to] has that [from] doesn't (0 on failure). */
    suspend fun countCommits(dir: File, from: String, to: String): Int =
        runParsed(dir, "rev-list", "--count", "$from..$to").takeIf { it.ok }?.output?.trim()?.toIntOrNull() ?: 0

    /** Undoes the last commit but keeps its changes, staged. */
    suspend fun undoLastCommit(dir: File): CommandResult = run(dir, "reset", "--soft", "HEAD~1", timeoutMs = WRITE_TIMEOUT_MS)

    /**
     * Whether [commit] is on any remote-tracking branch (of any remote, not just origin): then
     * someone else may have it. Unknown (git failed) counts as yes, the safe answer.
     */
    suspend fun onAnyRemote(dir: File, commit: String): Boolean {
        val r = runParsed(dir, "for-each-ref", "--contains", commit, "--count=1", "--format=%(refname)", "refs/remotes", timeoutMs = 30_000)
        return !r.ok || r.output.isNotBlank()
    }

    /** Where git keeps each of [names] for this repository (`.git` may be a file pointing elsewhere: worktrees, submodules). */
    private suspend fun gitPaths(dir: File, names: List<String>): List<File>? {
        val args = names.flatMap { listOf("--git-path", it) }
        val r = runParsed(dir, "rev-parse", *args.toTypedArray(), timeoutMs = 10_000)
        val lines = r.output.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (!r.ok || lines.size != names.size) return null
        return lines.map { p -> File(p).let { if (it.isAbsolute) it else File(dir, p) } }
    }

    /** The merge, rebase, cherry-pick or revert waiting to be finished, or null when there's none. */
    suspend fun operationInProgress(dir: File): GitOperation? {
        if (!File(dir, ".git").exists()) return null
        val files = gitPaths(dir, GitOperation.GIT_PATHS) ?: GitOperation.GIT_PATHS.map { File(dir, ".git/$it") }
        val found = withContext(Dispatchers.IO) { GitOperation.GIT_PATHS.zip(files).filter { (_, f) -> f.exists() }.map { it.first }.toSet() }
        return GitOperation.detect { it in found }
    }

    /**
     * Finishes [op] once its conflicts are resolved. No editor can open here, so git keeps the
     * message it prepared (GIT_EDITOR=true); unmerged files or markers in staged files stop it first.
     */
    suspend fun continueOperation(dir: File, op: GitOperation): CommandResult {
        unresolved(dir)?.let { return it }
        markersStaged(dir)?.let { return it }
        return run(dir, *op.continueArgs.toTypedArray(), timeoutMs = WRITE_TIMEOUT_MS, extraEnv = mapOf("GIT_EDITOR" to "true"))
    }

    /** Undoes [op]: the branch goes back to where it was before it started. */
    suspend fun abortOperation(dir: File, op: GitOperation): CommandResult =
        run(dir, *op.abortArgs.toTypedArray(), timeoutMs = WRITE_TIMEOUT_MS)

    /**
     * Applies [patch] (one hunk, from [HunkPatch]) to the index only: stages it, or with
     * [reverse] unstages it. The file on disk isn't touched. The patch goes through a file, so
     * CRs and a missing final newline reach git exactly.
     */
    suspend fun applyToIndex(dir: File, patch: String, reverse: Boolean): CommandResult {
        val file = withContext(Dispatchers.IO) {
            runCatching {
                val tmp = paths.tmp.takeIf { it.isDirectory || it.mkdirs() } ?: paths.filesDir
                File.createTempFile("hunk-", ".patch", tmp).apply { writeBytes(patch.toByteArray(Charsets.UTF_8)) }
            }
        }.getOrElse { return CommandResult(exitCode = 1, output = "Couldn't write the patch: ${it.message}") }
        return try {
            run(dir, *applyArgs(file.path, reverse).toTypedArray(), timeoutMs = WRITE_TIMEOUT_MS)
        } finally {
            withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { file.delete() }
        }
    }

    /**
     * Removes the index.lock a killed git left behind, after checking that no git is running in
     * this repository (a live one holds it on purpose). The result says what happened.
     */
    suspend fun removeStaleLock(dir: File): CommandResult {
        val lock = gitPaths(dir, listOf("index.lock"))?.single() ?: File(dir, ".git/index.lock")
        return withContext(Dispatchers.IO) {
            when {
                !lock.exists() -> CommandResult(exitCode = 0, output = "The lock is already gone. Try again.")
                GitLock.gitRunningIn(dir) -> CommandResult(exitCode = 1,
                    output = "Git is still running in this project (another command, or an agent). Wait for it to finish: it removes the lock itself.")
                lock.delete() -> CommandResult(exitCode = 0, output = "Removed the stale lock. Try again.")
                else -> CommandResult(exitCode = 1, output = "Couldn't remove ${lock.path}.")
            }
        }
    }

    // ---- Branches & stash --------------------------------------------------------------------

    /** Local branches, then remote ones without a local branch of the same name. */
    suspend fun branches(dir: File): Result<List<GitBranch>> {
        val r = runParsed(
            dir, "for-each-ref", "--sort=-committerdate",
            "--format=%(refname)%1f%(refname:short)%1f%(upstream:short)%1f%(committerdate:relative)%1f%(HEAD)",
            "refs/heads", "refs/remotes",
        )
        if (!r.ok) return failure(r, "git for-each-ref")
        return Result.success(parseBranches(r.output))
    }

    /** Updates remote branches (for the switch list); quiet failure when offline. */
    suspend fun fetch(dir: File): CommandResult = run(dir, "fetch", "--prune", "--quiet", timeoutMs = 120_000)

    suspend fun switchTo(dir: File, branch: GitBranch): CommandResult =
        if (branch.isRemote) run(dir, "switch", "--track", branch.name, timeoutMs = WRITE_TIMEOUT_MS) // creates the local branch
        else run(dir, "switch", branch.name, timeoutMs = WRITE_TIMEOUT_MS)

    suspend fun createBranch(dir: File, name: String): CommandResult = run(dir, "switch", "-c", name, timeoutMs = WRITE_TIMEOUT_MS)

    suspend fun renameBranch(dir: File, from: String, to: String): CommandResult = run(dir, "branch", "-m", from, to)

    /**
     * Local branches that can probably go: merged into HEAD, or whose upstream was deleted on the
     * remote ("gone", typically after its pull request was merged). Never the current branch, nor
     * the usual long-lived ones (main, master, develop…), which are "merged" into any branch made from them.
     */
    suspend fun cleanupCandidates(dir: File): Result<List<CleanupBranch>> {
        val all = runParsed(dir, "for-each-ref", "--format=%(refname:short)%1f%(upstream:track)%1f%(HEAD)", "refs/heads")
        if (!all.ok) return failure(all, "git for-each-ref")
        val merged = runParsed(dir, "for-each-ref", "--merged=HEAD", "--format=%(refname:short)", "refs/heads")
        if (!merged.ok) return failure(merged, "git for-each-ref --merged")
        return Result.success(parseCleanup(all.output, merged.output))
    }

    /** `git branch -d` (refuses to lose unmerged work), or with [force] `-D` (deletes it anyway). */
    suspend fun deleteBranch(dir: File, name: String, force: Boolean): CommandResult =
        run(dir, "branch", if (force) "-D" else "-d", name)

    suspend fun stashes(dir: File): Result<List<GitStash>> {
        val r = runParsed(dir, "stash", "list", "--format=%gd%x1f%H%x1f%s%x1f%cr")
        if (!r.ok) return failure(r, "git stash list")
        return Result.success(parseStashList(r.output))
    }

    /** Stashes everything, untracked files included. */
    suspend fun stash(dir: File, message: String): CommandResult =
        if (message.isBlank()) run(dir, "stash", "push", "--include-untracked", timeoutMs = WRITE_TIMEOUT_MS)
        else run(dir, "stash", "push", "--include-untracked", "-m", message, timeoutMs = WRITE_TIMEOUT_MS)

    /**
     * The current "stash@{n}" of the entry whose commit is [sha], checked again just before use:
     * positions shift whenever anything (another screen, an agent) stashes or drops.
     */
    private suspend fun stashRef(dir: File, sha: String): String? {
        val r = runParsed(dir, "stash", "list", "--format=%gd%x1f%H")
        if (!r.ok) return null
        val ref = stashRefIn(r.output, sha) ?: return null
        val now = runParsed(dir, "rev-parse", "-q", "--verify", ref).output.trim()
        return ref.takeIf { now == sha }
    }

    /** Whether the stash entry with commit [sha] is still in the list; null when git couldn't say. */
    suspend fun hasStash(dir: File, sha: String): Boolean? {
        val r = runParsed(dir, "stash", "list", "--format=%H")
        return if (r.ok) r.output.lines().any { it.trim() == sha } else null
    }

    private suspend fun stashBySha(dir: File, action: String, sha: String): CommandResult {
        val ref = stashRef(dir, sha)
            ?: return CommandResult(exitCode = 1, output = "That stash entry isn't there any more (it was popped or dropped elsewhere).")
        return run(dir, "stash", action, ref, timeoutMs = WRITE_TIMEOUT_MS)
    }

    suspend fun stashPopSha(dir: File, sha: String): CommandResult = stashBySha(dir, "pop", sha)
    suspend fun stashDropSha(dir: File, sha: String): CommandResult = stashBySha(dir, "drop", sha)

    /** The commit stash@{0} points at, or null when there are no stashes. */
    suspend fun stashTop(dir: File): String? =
        runParsed(dir, "rev-parse", "-q", "--verify", "refs/stash").takeIf { it.ok }?.output?.trim()?.ifEmpty { null }

    /**
     * [stash], and whether it actually saved an entry: git exits 0 when there was nothing to save,
     * and popping after that would apply an older, unrelated stash.
     */
    suspend fun stashSaving(dir: File, message: String): Pair<CommandResult, Boolean> {
        val before = stashTop(dir)
        val r = stash(dir, message)
        if (!r.ok) return r to false
        return r to stashCreated(before, stashTop(dir))
    }

    /** Acts on [stash] by its commit when known, so a list that went stale can't hit another entry. */
    suspend fun stashPop(dir: File, stash: GitStash): CommandResult =
        if (stash.sha.isNotEmpty()) stashBySha(dir, "pop", stash.sha) else run(dir, "stash", "pop", stash.ref, timeoutMs = WRITE_TIMEOUT_MS)
    suspend fun stashApply(dir: File, stash: GitStash): CommandResult =
        if (stash.sha.isNotEmpty()) stashBySha(dir, "apply", stash.sha) else run(dir, "stash", "apply", stash.ref, timeoutMs = WRITE_TIMEOUT_MS)
    suspend fun stashDrop(dir: File, stash: GitStash): CommandResult =
        if (stash.sha.isNotEmpty()) stashBySha(dir, "drop", stash.sha) else run(dir, "stash", "drop", stash.ref)

    /**
     * The name and email a commit in [dir] would use: git's effective config there (the
     * repository's own, includes, then global), not just the global one. Global when [dir] is null.
     */
    suspend fun identity(dir: File? = null): Pair<String, String> {
        val where = dir ?: paths.home
        val scope = if (dir == null) arrayOf("--global") else emptyArray()
        val name = runParsed(where, "config", *scope, "--get", "user.name").output.trim()
        val email = runParsed(where, "config", *scope, "--get", "user.email").output.trim()
        return name to email
    }

    /** Saves the global commit identity; a failed write is returned, not ignored. */
    suspend fun setIdentity(name: String, email: String): CommandResult {
        val r = run(paths.home, "config", "--global", "user.name", name)
        if (!r.ok) return r
        return run(paths.home, "config", "--global", "user.email", email)
    }

    /** Short timeout: this runs before the projects list shows GitHub, and offline it would just hang. */
    suspend fun ghStatus(): GhStatus {
        val gh = File(paths.bin, "gh")
        if (!gh.exists()) return GhStatus(installed = false, signedIn = false, account = null)
        val r = CommandRunner.run(paths, listOf(gh.path, "auth", "status", "--hostname", "github.com"), extraEnv = env + GH_VARS, timeoutMs = 20_000)
        val hostsFile = File(paths.home, ".config/gh/hosts.yml").isFile
        return parseGhAuth(r, hostsFile)
    }

    /**
     * Repositories of the signed-in account and of every organization it belongs to (each org's
     * repos marked [GhRepo.isOrg]), newest push first. Null if gh can't list the account's own.
     */
    suspend fun ghRepos(limit: Int = 200): Result<GhRepoList> = coroutineScope {
        val gh = File(paths.bin, "gh")
        if (!gh.exists()) return@coroutineScope Result.failure(IllegalStateException("The GitHub CLI isn't installed."))
        suspend fun runGh(vararg args: String, timeoutMs: Long) =
            CommandRunner.run(paths, listOf(gh.path, *args), extraEnv = env + GH_VARS, timeoutMs = timeoutMs, forParsing = true)
        fun list(owner: String?) = async {
            val args = listOfNotNull("repo", "list", owner, "--limit", "$limit", "--json", "nameWithOwner,name,description,isPrivate,isFork,pushedAt")
            val r = runGh(*args.toTypedArray(), timeoutMs = 60_000)
            val parsed = if (r.ok) parseRepos(r.output) else null
            if (parsed != null) Result.success(parsed) else Result.failure(IllegalStateException(ghError(r, "gh repo list")))
        }
        val own = list(null)
        val orgsResult = runGh("api", "user/orgs", "--jq", ".[].login", timeoutMs = 30_000)
        val orgs = if (orgsResult.ok) orgsResult.output.lines().map { it.trim() }.filter { it.isNotEmpty() && ' ' !in it } else emptyList()
        val orgLists = orgs.map { list(it) }.awaitAll()
        val mine = own.await().getOrElse { return@coroutineScope Result.failure(it) }
        val orgRepos = orgLists.mapNotNull { it.getOrNull() }.flatten().map { it.copy(isOrg = true) }
        val orgError = when {
            !orgsResult.ok -> "Organisation repositories weren't loaded: " + ghError(orgsResult, "gh api user/orgs")
            orgLists.any { it.isFailure } -> "Some organisations' repositories weren't loaded: " +
                orgLists.first { it.isFailure }.exceptionOrNull()?.message
            else -> null
        }
        Result.success(GhRepoList((mine + orgRepos).distinctBy { it.slug.lowercase() }.sortedByDescending { it.updatedAt }, orgError, orgs))
    }

    private suspend fun runGh(dir: File, vararg args: String, timeoutMs: Long = 60_000): CommandResult? {
        val gh = File(paths.bin, "gh")
        if (!gh.exists()) return null
        return CommandRunner.run(paths, listOf(gh.path, *args), cwd = dir, extraEnv = env + GH_VARS, timeoutMs = timeoutMs, forParsing = true)
    }

    /** The pull request for [dir]'s current branch: Success(null) when there isn't one. */
    suspend fun pullRequest(dir: File): Result<GitPullRequest?> {
        val r = runGh(dir, "pr", "view", "--json", "number,title,state,url,isDraft", timeoutMs = 30_000)
            ?: return Result.failure(IllegalStateException("The GitHub CLI isn't installed."))
        if (r.ok) return parsePullRequest(r.output)?.let { Result.success(it) } ?: Result.failure(IllegalStateException("Unexpected reply from gh pr view."))
        val text = r.errorOutput + "\n" + r.output
        if (text.contains("no pull requests found", ignoreCase = true)) return Result.success(null)
        return Result.failure(IllegalStateException(ghError(r, "gh pr view")))
    }

    /** Opens a pull request for the current (pushed) branch, title and body filled from its commits. Returns its URL. */
    suspend fun createPullRequest(dir: File): Result<String> {
        val r = runGh(dir, "pr", "create", "--fill", timeoutMs = 120_000)
            ?: return Result.failure(IllegalStateException("The GitHub CLI isn't installed."))
        val url = r.output.lineSequence().map { it.trim() }.lastOrNull { it.startsWith("https://") }
        return if (r.ok && url != null) Result.success(url) else Result.failure(IllegalStateException(ghError(r, "gh pr create")))
    }

    /** "owner/name" (lowercase) of the origin remote if it's on GitHub; null otherwise. */
    suspend fun originSlug(dir: File): String? {
        if (!File(dir, ".git").exists()) return null
        val r = runParsed(dir, "remote", "get-url", "origin", timeoutMs = 10_000)
        return if (r.ok) githubSlug(r.firstLine) else null
    }

    private fun <T> failure(r: CommandResult, what: String): Result<T> = Result.failure(GitException(errorOf(r, what)))

    companion object {
        /**
         * Fail fast instead of waiting for a password prompt nobody can answer; no pagers. Paths
         * are literal: otherwise git reads `app/[id]/page.tsx` as a glob that also matches
         * `app/i/page.tsx`, and discarding one file would reset or delete the other.
         */
        val ENV: Map<String, String> = mapOf(
            "GIT_TERMINAL_PROMPT" to "0", "GIT_PAGER" to "cat", "PAGER" to "cat", "GIT_LITERAL_PATHSPECS" to "1",
        )

        /** Commits per history page ("Load more" fetches the next). */
        const val HISTORY_PAGE = 300

        /**
         * For git commands that change things and may run hooks (commit, add, stash, merge, pull):
         * pre-commit hooks running linters or tests take minutes on a phone, and killing git half
         * way leaves a stale index.lock or a half-made merge.
         */
        const val WRITE_TIMEOUT_MS = 20 * 60_000L

        /** Diffs longer than this are cut, with a note saying how much more there is. */
        const val MAX_DIFF_CHARS = 512 * 1024

        /** Starts the note [capDiff] adds; a diff with it is incomplete (hunks can't be staged from it). */
        const val DIFF_TRUNCATED = "… Diff truncated"

        private val HEX = Regex("^[0-9a-fA-F]{4,40}$")

        internal const val LOG_FORMAT = "--pretty=format:%H%x1f%P%x1f%an%x1f%ct%x1f%D%x1f%s%x1e"

        /** Arguments for [log]: a page of history, or one file's (renames followed). */
        fun logArgs(count: Int, allBranches: Boolean, filter: HistoryFilter, skip: Int = 0, path: String? = null): List<String> = buildList {
            addAll(listOf("log", "-n", count.toString()))
            if (skip > 0) add("--skip=$skip")
            // --follow needs a single path and a plain walk; the graph isn't drawn for one file anyway.
            if (path == null) add("--topo-order")
            addAll(listOf("--no-color", LOG_FORMAT))
            if (allBranches && path == null) add("--all")
            addAll(filter.gitArgs())
            if (path != null) addAll(listOf("--follow", "--", path))
        }

        /**
         * `git diff` arguments for one path. Fixed a/ b/ prefixes, no textconv or external
         * diff: whatever the user's config, the text must be a patch `git apply` takes back.
         */
        fun diffArgs(path: String, side: DiffSide): List<String> = buildList {
            addAll(listOf("diff", "--no-color", "--no-ext-diff", "--no-textconv", "--src-prefix=a/", "--dst-prefix=b/"))
            when (side) {
                DiffSide.All -> add("HEAD")
                DiffSide.Staged -> add("--cached")
                DiffSide.Unstaged -> Unit
            }
            addAll(listOf("--", path))
        }

        /** `git apply` arguments for one hunk's patch file, into the index only. */
        fun applyArgs(patchFile: String, reverse: Boolean): List<String> = buildList {
            // nowarn: a user's apply.whitespace=error mustn't refuse their own trailing spaces.
            addAll(listOf("apply", "--cached", "--whitespace=nowarn"))
            if (reverse) add("--reverse")
            add(patchFile)
        }

        /**
         * [text] cut at a line end once it passes [max] characters, with a note saying how much is
         * left out. [alreadyCut]: the runner itself stopped reading, so the rest is unknown.
         */
        fun capDiff(text: String, alreadyCut: Boolean = false, max: Int = MAX_DIFF_CHARS): String {
            if (text.length <= max && !alreadyCut) return text
            if (text.length <= max) return text.trimEnd('\n') + "\n$DIFF_TRUNCATED: the rest wasn't read.\n"
            val end = text.lastIndexOf('\n', max).takeIf { it > 0 } ?: max
            val rest = text.substring(end).toByteArray(Charsets.UTF_8).size
            val kb = (rest + 1023) / 1024
            return text.substring(0, end) + "\n$DIFF_TRUNCATED — " + (if (alreadyCut) "over " else "") + "$kb KB more. Open the file to see all of it.\n"
        }

        /** Parses `git stash list --format=%gd%x1f%H%x1f%s%x1f%cr`. */
        fun parseStashList(text: String): List<GitStash> = text.lines().mapNotNull { line ->
            val f = line.split('\u001f')
            if (f.size == 4 && f[1].length >= 40) GitStash(f[0], f[2], f[3], f[1]) else null
        }

        /** In `git stash list --format=%gd%x1f%H` output, the "stash@{n}" whose commit is [sha]. */
        fun stashRefIn(text: String, sha: String): String? = text.lines().firstNotNullOfOrNull { line ->
            val f = line.split('\u001f')
            f.getOrNull(0)?.takeIf { f.size == 2 && f[1].trim() == sha && it.startsWith("stash@{") }
        }

        /** Branch names nobody means to clean up, though they're "merged" into every branch made from them. */
        private val LONG_LIVED = setOf("main", "master", "develop", "development", "dev", "trunk", "release", "staging", "production", "gh-pages")

        /**
         * Joins `for-each-ref --format=%(refname:short)%1f%(upstream:track)%1f%(HEAD)` ([all]) with
         * the names merged into HEAD ([merged]): candidates for clean-up, merged or gone, never the
         * current branch or a long-lived one.
         */
        fun parseCleanup(all: String, merged: String): List<CleanupBranch> {
            val mergedNames = merged.lines().map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            return all.lines().mapNotNull { line ->
                val f = line.split('\u001f')
                if (f.size != 3) return@mapNotNull null
                val name = f[0].trim()
                if (name.isEmpty() || f[2].trim() == "*" || name in LONG_LIVED) return@mapNotNull null
                val gone = f[1].trim() == "[gone]"
                val isMerged = name in mergedNames
                if (gone || isMerged) CleanupBranch(name, gone, isMerged) else null
            }
        }

        private val INDEX_LOCK = Regex("""Unable to create '[^']*index\.lock': File exists""")

        /** Whether [output] is git refusing to run because index.lock exists (another git running, or a killed one's leftover). */
        fun mentionsIndexLock(output: String): Boolean = INDEX_LOCK.containsMatchIn(output)

        /** Whether `git stash push` made an entry: refs/stash moved (it exits 0 with "No local changes to save" too). */
        fun stashCreated(before: String?, after: String?): Boolean = after != null && after != before

        private val EMAIL = Regex("""^[^\s@]+@[^\s@]+\.[^\s@]+$""")

        /** A plausible commit email: something@domain.tld, no spaces. */
        fun isValidEmail(email: String): Boolean = EMAIL.matches(email.trim())

        private val GH_VARS = mapOf("GH_PROMPT_DISABLED" to "1", "GH_NO_UPDATE_NOTIFIER" to "1")
        private val NETWORK_ERROR = Regex(
            "(?i)error connecting|dial tcp|no such host|could not resolve|i/o timeout|network is unreachable|connection refused|" +
                "TLS handshake|timeout awaiting|context deadline exceeded|connection reset",
        )

        /** gh's last error lines, or what kind of failure it was when it printed nothing. */
        fun ghError(r: CommandResult, what: String): String {
            val text = (r.errorOutput + "\n" + r.output).lines().map { it.trim() }.filter { it.isNotEmpty() }.takeLast(2).joinToString(" ")
            return when {
                r.timedOut || NETWORK_ERROR.containsMatchIn(text) -> "GitHub can't be reached. Check the connection and pull down to retry."
                text.isNotEmpty() && !r.toolMissing -> text
                else -> r.describe(what)
            }
        }

        /**
         * Reads `gh auth status`: signed in, signed out, sign-in expired, offline or broken. Offline
         * with a saved sign-in ([hasHostsFile]) still counts as signed in, so cached repos stay.
         */
        fun parseGhAuth(r: CommandResult, hasHostsFile: Boolean): GhStatus {
            val text = r.errorOutput + "\n" + r.output
            val account = ACCOUNT.find(text)?.groupValues?.get(1)
            val loggedIn = r.ok || Regex("(?i)Logged in to github\\.com").containsMatchIn(text)
            return when {
                r.toolMissing -> GhStatus(true, false, null, GhProblem.Broken, r.describe("gh"))
                r.timedOut || NETWORK_ERROR.containsMatchIn(text) ->
                    GhStatus(true, hasHostsFile || loggedIn, account, GhProblem.Unreachable, "GitHub can't be reached right now.")
                loggedIn -> GhStatus(true, true, account)
                Regex("(?i)not logged in|You are not logged").containsMatchIn(text) -> GhStatus(true, false, null)
                Regex("(?i)failed to log in|token .*invalid|invalid token|bad credentials|\\b401\\b").containsMatchIn(text) ->
                    GhStatus(true, false, account, GhProblem.SignInExpired, "Your GitHub sign-in is no longer valid. Sign in again.")
                else -> GhStatus(true, false, null, GhProblem.Broken, ghError(r, "gh auth status"))
            }
        }

        /** A user-facing explanation of a failed git command. */
        fun errorOf(r: CommandResult, what: String): GitError =
            if (r.toolMissing) {
                GitError("Git isn't installed, or can't run. Install the developer tools to use it.", toolMissing = true)
            } else {
                GitError(r.describe(what), toolMissing = false)
            }

        private val GITHUB_URL = Regex("github\\.com[:/]+([^/\\s]+)/([^/\\s]+?)(?:\\.git)?/?$")

        /** Parses [log] output: records split by \u001e, fields by \u001f. */
        fun parseLog(text: String): List<GitCommit> = text.split('\u001e').mapNotNull { rec ->
            val f = rec.trim('\n').split('\u001f')
            if (f.size != 6 || f[0].length != 40) return@mapNotNull null
            GitCommit(
                hash = f[0],
                parents = f[1].split(' ').filter { it.isNotBlank() },
                author = f[2],
                time = (f[3].toLongOrNull() ?: 0L) * 1000,
                refs = f[4].split(", ").map { it.trim() }.filter { it.isNotEmpty() },
                subject = f[5],
            )
        }

        /**
         * Parses `diff-tree --name-status -z`: a status token, then the path (or old and new
         * paths for renames/copies, "R100"/"C75"). Merges list a path once.
         */
        fun parseNameStatus(text: String): List<CommitFile> {
            val tokens = text.split('\u0000')
            val files = mutableListOf<CommitFile>()
            var i = 0
            while (i < tokens.size) {
                val status = tokens[i].trim('\n')
                if (status.isEmpty()) { i++; continue }
                val twoPaths = status[0] == 'R' || status[0] == 'C'
                val path = tokens.getOrNull(if (twoPaths) i + 2 else i + 1) ?: break
                files += CommitFile(status[0], path)
                i += if (twoPaths) 3 else 2
            }
            return files.distinctBy { it.path }
        }

        /** Parses `for-each-ref` output (fields split by \u001f) from [branches]. */
        fun parseBranches(text: String): List<GitBranch> {
            val all = text.lines().mapNotNull { line ->
                val f = line.split('\u001f')
                if (f.size != 5) return@mapNotNull null
                val remote = f[0].startsWith("refs/remotes/")
                if (remote && f[0].endsWith("/HEAD")) return@mapNotNull null // origin/HEAD alias
                GitBranch(
                    name = f[1],
                    isRemote = remote,
                    upstream = f[2].ifBlank { null },
                    updated = f[3],
                    isCurrent = f[4].trim() == "*",
                )
            }
            val locals = all.filter { !it.isRemote }
            val localNames = locals.map { it.name }.toSet()
            val tracked = locals.mapNotNull { it.upstream }.toSet()
            val remotes = all.filter { it.isRemote && it.name !in tracked && it.name.substringAfter('/') !in localNames }
            return locals + remotes
        }

        /** "owner/name" (lowercase) from an https or ssh GitHub URL. */
        fun githubSlug(url: String): String? =
            GITHUB_URL.find(url.trim())?.let { "${it.groupValues[1]}/${it.groupValues[2]}".lowercase() }

        private val json = Json { ignoreUnknownKeys = true }

        /** Parses `gh pr view --json number,title,state,url,isDraft`; null if it isn't that. */
        fun parsePullRequest(text: String): GitPullRequest? = runCatching {
            val o = json.parseToJsonElement(text.substring(text.indexOf('{'))).jsonObject
            fun str(k: String) = (o[k] as? JsonPrimitive)?.content
            GitPullRequest(
                number = str("number")!!.toInt(),
                title = str("title").orEmpty(),
                state = str("state").orEmpty(),
                url = str("url")!!.also { require(it.startsWith("https://")) },
                isDraft = str("isDraft") == "true",
            )
        }.getOrNull()

        /** Parses `gh repo list --json …` output; null if it isn't a JSON array. */
        fun parseRepos(text: String): List<GhRepo>? = runCatching {
            val start = text.indexOf('[')
            require(start >= 0)
            val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
            json.parseToJsonElement(text.substring(start)).jsonArray.map { el ->
                val o = el.jsonObject
                fun str(k: String) = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
                fun bool(k: String) = (o[k] as? JsonPrimitive)?.content == "true"
                GhRepo(
                    slug = str("nameWithOwner").orEmpty(),
                    name = str("name").orEmpty(),
                    description = str("description")?.takeIf { it.isNotBlank() },
                    isPrivate = bool("isPrivate"),
                    isFork = bool("isFork"),
                    updatedAt = str("pushedAt")?.let { runCatching { iso.parse(it)?.time }.getOrNull() } ?: 0L,
                )
            }.filter { it.slug.isNotEmpty() }
        }.getOrNull()

        val DEFAULT_GITIGNORE = """
            |# Added by Yurlungr
            |.gradle/
            |.kotlin/
            |build/
            |local.properties
            |.idea/
            |*.iml
            |.cxx/
            |captures/
            |node_modules/
            |.DS_Store
            |""".trimMargin()

        private val ACCOUNT = Regex("""account (\S+)""")
        private val HEADER = Regex("""^(.+?)(?:\.\.\.(\S+))?(?: \[(.*)])?$""")

        /** Parses `git status --porcelain=v1 -b -z` output. */
        fun parseStatus(output: String): GitStatus {
            val tokens = output.split('\u0000')
            var branch: String? = null
            var upstream: String? = null
            var ahead = 0
            var behind = 0
            var unborn = false
            val changes = mutableListOf<GitChange>()
            var i = 0
            while (i < tokens.size) {
                val t = tokens[i]
                when {
                    t.isEmpty() -> Unit
                    t.startsWith("## ") -> {
                        val h = t.removePrefix("## ")
                        when {
                            h.startsWith("No commits yet on ") -> {
                                unborn = true
                                branch = h.removePrefix("No commits yet on ")
                            }
                            h.startsWith("HEAD (no branch)") -> branch = GitStatus.DETACHED
                            else -> HEADER.find(h)?.let { m ->
                                branch = m.groupValues[1]
                                upstream = m.groupValues[2].ifEmpty { null }
                                m.groupValues[3].split(", ").forEach { part ->
                                    when {
                                        part.startsWith("ahead ") -> ahead = part.removePrefix("ahead ").toIntOrNull() ?: 0
                                        part.startsWith("behind ") -> behind = part.removePrefix("behind ").toIntOrNull() ?: 0
                                    }
                                }
                            }
                        }
                    }
                    t.length > 3 -> {
                        val x = t[0]
                        val y = t[1]
                        val path = t.substring(3)
                        // Renames/copies (in either column) are followed by the original path as its own token.
                        val orig = if (x == 'R' || x == 'C' || y == 'R' || y == 'C') tokens.getOrNull(++i) else null
                        changes += GitChange(path, x, y, orig)
                    }
                }
                i++
            }
            return GitStatus(true, branch, upstream, ahead, behind, unborn, changes)
        }

        fun isBinary(file: File): Boolean = runCatching {
            file.inputStream().use { input ->
                val buf = ByteArray(8000)
                val n = input.read(buf)
                (0 until maxOf(n, 0)).any { buf[it] == 0.toByte() }
            }
        }.getOrDefault(true)

        /**
         * The files of a `diff -U0` whose added lines open or close a conflict. A file's name is
         * only read from its header ("+++ b/x", before its first "@@"), so an added content line
         * that starts with "++ " can't be mistaken for one; C-quoted names are unquoted.
         */
        fun filesWithMarkers(diff: String): Set<String> {
            val hits = LinkedHashSet<String>()
            var file: String? = null
            var inHeader = false
            for (line in diff.lineSequence()) {
                when {
                    line.startsWith("diff --git ") -> { inHeader = true; file = null }
                    inHeader && line.startsWith("@@") -> inHeader = false
                    inHeader && line.startsWith("+++ ") -> {
                        val name = unquoteC(line.removePrefix("+++ "))
                        file = if (name == "/dev/null") null else name.removePrefix("b/")
                    }
                    !inHeader && CONFLICT_MARKER.containsMatchIn(line) -> file?.let(hits::add)
                }
            }
            return hits
        }

        /** Undoes git's C-style quoting of a path (`"caf\303\251"` → café); unquoted text is returned as is. */
        fun unquoteC(text: String): String {
            if (text.length < 2 || !text.startsWith('"') || !text.endsWith('"')) return text
            val body = text.substring(1, text.length - 1)
            val out = java.io.ByteArrayOutputStream()
            var i = 0
            while (i < body.length) {
                val c = body[i]
                if (c == '\\' && i + 1 < body.length) {
                    val octal = body.substring(i + 1, minOf(i + 4, body.length))
                    if (octal.length == 3 && octal.all { it in '0'..'7' }) {
                        out.write(octal.toInt(8))
                        i += 4
                        continue
                    }
                    val escaped = when (val n = body[i + 1]) {
                        'n' -> '\n'; 't' -> '\t'; 'r' -> '\r'; 'a' -> '\u0007'; 'b' -> '\b'; 'f' -> '\u000c'; 'v' -> '\u000b'
                        else -> n
                    }
                    out.write(escaped.toString().toByteArray(Charsets.UTF_8))
                    i += 2
                    continue
                }
                val n = if (Character.isHighSurrogate(c) && i + 1 < body.length) 2 else 1
                out.write(body.substring(i, i + n).toByteArray(Charsets.UTF_8))
                i += n
            }
            return out.toString("UTF-8")
        }

        /** Single-quotes a value for the shell. */
        fun shellQuote(s: String) = "'" + s.replace("'", "'\\''") + "'"
    }
}
