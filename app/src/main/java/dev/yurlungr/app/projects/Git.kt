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

data class GitChange(
    val path: String,
    /** Porcelain status letters: [index] (staged) and [worktree]. '?' = untracked. */
    val index: Char,
    val worktree: Char,
    val origPath: String? = null,
) {
    val untracked: Boolean get() = index == '?'

    val label: String
        get() = when {
            untracked -> "new"
            index == 'R' || worktree == 'R' -> "renamed"
            index == 'A' -> "added"
            index == 'D' || worktree == 'D' -> "deleted"
            index == 'U' || worktree == 'U' -> "conflict"
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
        get() = changes.filter { c ->
            c.index == 'U' || c.worktree == 'U' || (c.index == 'A' && c.worktree == 'A') || (c.index == 'D' && c.worktree == 'D')
        }.map { it.path }

    companion object {
        val NotARepo = GitStatus(isRepo = false)
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

/** The signed-in account's repositories; [orgError] says why some organisations' repos are missing. */
data class GhRepoList(val repos: List<GhRepo>, val orgError: String? = null)

/** A branch; remote ones are named "origin/x" and only listed when there's no local "x". */
data class GitBranch(val name: String, val isRemote: Boolean, val upstream: String?, val updated: String, val isCurrent: Boolean)

/** A stash entry; [ref] is "stash@{n}". */
data class GitStash(val ref: String, val message: String, val whenRelative: String)

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

    /** Fail fast instead of waiting for a password prompt nobody can answer; no pagers. */
    private val env = mapOf("GIT_TERMINAL_PROMPT" to "0", "GIT_PAGER" to "cat", "PAGER" to "cat")

    private suspend fun run(dir: File, vararg args: String, timeoutMs: Long = 60_000): CommandResult =
        CommandRunner.run(paths, listOf(git, "-C", dir.path, *args), cwd = dir, timeoutMs = timeoutMs, extraEnv = env)

    /** For output that gets parsed: exact stdout, stderr kept apart (warnings can't corrupt it), large cap. */
    private suspend fun runParsed(dir: File, vararg args: String, timeoutMs: Long = 60_000): CommandResult =
        CommandRunner.run(paths, listOf(git, "-C", dir.path, *args), cwd = dir, timeoutMs = timeoutMs, extraEnv = env, forParsing = true)
            .also { if (it.truncated) android.util.Log.w("Git", "Output of git ${args.firstOrNull()} was truncated") }

    suspend fun status(dir: File): GitStatus {
        if (!File(dir, ".git").exists()) return GitStatus.NotARepo
        val r = runParsed(dir, "status", "--porcelain=v1", "-b", "-z", "-uall")
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

    /** Unified diff of one change against HEAD, or the whole file for new/unborn files. */
    suspend fun diff(dir: File, change: GitChange, unborn: Boolean): String {
        if (change.untracked || unborn) return addedFileDiff(File(dir, change.path))
        val r = run(dir, "diff", "--no-color", "--no-ext-diff", "HEAD", "--", change.path)
        return r.output.ifBlank { "(no textual changes)" }
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
    suspend fun log(dir: File, count: Int = 300, allBranches: Boolean = false, filter: HistoryFilter = HistoryFilter()): Result<List<GitCommit>> {
        val args = mutableListOf("log", "-n", count.toString(), "--topo-order", "--no-color", "--pretty=format:%H%x1f%P%x1f%an%x1f%ct%x1f%D%x1f%s%x1e")
        if (allBranches) args += "--all"
        args += filter.gitArgs()
        val r = runParsed(dir, *args.toTypedArray())
        if (!r.ok) return failure(r, "git log")
        return Result.success(parseLog(r.output))
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

    suspend fun commitDiff(dir: File, hash: String, path: String): String =
        run(dir, "diff-tree", "--root", "-p", "--no-color", "-m", "--first-parent", hash, "--", path).output
            .lines().dropWhile { it.length == 40 && it.all(Char::isLetterOrDigit) }.joinToString("\n")
            .ifBlank { "(no textual changes)" }

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
        val add = run(dir, "add", "-A")
        if (!add.ok) return add
        return run(dir, "commit", "-m", message)
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
    suspend fun pullMerge(dir: File): CommandResult = run(dir, "pull", "--no-rebase", "--no-edit", timeoutMs = 300_000)

    /** The current commit's hash, or null (no commits yet, or git failed). */
    suspend fun head(dir: File): String? =
        runParsed(dir, "rev-parse", "--verify", "--quiet", "HEAD").takeIf { it.ok }?.output?.trim()?.takeIf { it.length == 40 }

    /** How many commits [to] has that [from] doesn't (0 on failure). */
    suspend fun countCommits(dir: File, from: String, to: String): Int =
        runParsed(dir, "rev-list", "--count", "$from..$to").takeIf { it.ok }?.output?.trim()?.toIntOrNull() ?: 0

    /** Re-applies and drops the newest stash; on a conflict git keeps it (stash@{0}). */
    suspend fun stashPopLatest(dir: File): CommandResult = run(dir, "stash", "pop")

    /** Undoes the last commit but keeps its changes, staged. */
    suspend fun undoLastCommit(dir: File): CommandResult = run(dir, "reset", "--soft", "HEAD~1")

    /** A merge is half done (after a conflicted pull): it needs resolving and committing. */
    fun mergeInProgress(dir: File): Boolean = File(dir, ".git/MERGE_HEAD").exists()

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
        if (branch.isRemote) run(dir, "switch", "--track", branch.name) // creates the local branch
        else run(dir, "switch", branch.name)

    suspend fun createBranch(dir: File, name: String): CommandResult = run(dir, "switch", "-c", name)

    suspend fun stashes(dir: File): Result<List<GitStash>> {
        val r = runParsed(dir, "stash", "list", "--format=%gd%x1f%s%x1f%cr")
        if (!r.ok) return failure(r, "git stash list")
        return Result.success(r.output.lines().mapNotNull { line ->
            val f = line.split('\u001f')
            if (f.size == 3) GitStash(f[0], f[1], f[2]) else null
        })
    }

    /** Stashes everything, untracked files included. */
    suspend fun stash(dir: File, message: String): CommandResult =
        if (message.isBlank()) run(dir, "stash", "push", "--include-untracked")
        else run(dir, "stash", "push", "--include-untracked", "-m", message)

    suspend fun stashPop(dir: File, stash: GitStash): CommandResult = run(dir, "stash", "pop", stash.ref)
    suspend fun stashApply(dir: File, stash: GitStash): CommandResult = run(dir, "stash", "apply", stash.ref)
    suspend fun stashDrop(dir: File, stash: GitStash): CommandResult = run(dir, "stash", "drop", stash.ref)

    suspend fun identity(): Pair<String, String> {
        val home = paths.home
        val name = run(home, "config", "--global", "user.name").output.trim()
        val email = run(home, "config", "--global", "user.email").output.trim()
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
        Result.success(GhRepoList((mine + orgRepos).distinctBy { it.slug.lowercase() }.sortedByDescending { it.updatedAt }, orgError))
    }

    /** "owner/name" (lowercase) of the origin remote if it's on GitHub; null otherwise. */
    suspend fun originSlug(dir: File): String? {
        if (!File(dir, ".git").exists()) return null
        val r = runParsed(dir, "remote", "get-url", "origin", timeoutMs = 10_000)
        return if (r.ok) githubSlug(r.firstLine) else null
    }

    private fun <T> failure(r: CommandResult, what: String): Result<T> = Result.failure(GitException(errorOf(r, what)))

    companion object {
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
                            h.startsWith("HEAD (no branch)") -> branch = "detached HEAD"
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

        /** Single-quotes a value for the shell. */
        fun shellQuote(s: String) = "'" + s.replace("'", "'\\''") + "'"
    }
}
