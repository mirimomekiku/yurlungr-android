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
) {
    companion object {
        val NotARepo = GitStatus(isRepo = false)
    }
}

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

data class GhStatus(val installed: Boolean, val signedIn: Boolean, val account: String?)

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
        if (!r.ok) return GitStatus.NotARepo
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
    suspend fun log(dir: File, count: Int = 300, allBranches: Boolean = false): List<GitCommit> {
        val args = mutableListOf("log", "-n", count.toString(), "--topo-order", "--no-color", "--pretty=format:%H%x1f%P%x1f%an%x1f%ct%x1f%D%x1f%s%x1e")
        if (allBranches) args += "--all"
        val r = runParsed(dir, *args.toTypedArray())
        if (!r.ok) return emptyList()
        return parseLog(r.output)
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
    suspend fun commitFiles(dir: File, hash: String): List<CommitFile> {
        // -z: paths verbatim (no quoting of non-ASCII, quotes or tabs), so they can be passed back to git.
        val r = runParsed(dir, "diff-tree", "--root", "--no-commit-id", "-r", "-m", "--first-parent", "--name-status", "-z", hash)
        if (!r.ok) return emptyList()
        return parseNameStatus(r.output)
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

    // ---- Branches & stash --------------------------------------------------------------------

    /** Local branches, then remote ones without a local branch of the same name. */
    suspend fun branches(dir: File): List<GitBranch> {
        val r = runParsed(
            dir, "for-each-ref", "--sort=-committerdate",
            "--format=%(refname)%1f%(refname:short)%1f%(upstream:short)%1f%(committerdate:relative)%1f%(HEAD)",
            "refs/heads", "refs/remotes",
        )
        if (!r.ok) return emptyList()
        return parseBranches(r.output)
    }

    /** Updates remote branches (for the switch list); quiet failure when offline. */
    suspend fun fetch(dir: File): CommandResult = run(dir, "fetch", "--prune", "--quiet", timeoutMs = 120_000)

    suspend fun switchTo(dir: File, branch: GitBranch): CommandResult =
        if (branch.isRemote) run(dir, "switch", "--track", branch.name) // creates the local branch
        else run(dir, "switch", branch.name)

    suspend fun createBranch(dir: File, name: String): CommandResult = run(dir, "switch", "-c", name)

    suspend fun stashes(dir: File): List<GitStash> {
        val r = runParsed(dir, "stash", "list", "--format=%gd%x1f%s%x1f%cr")
        if (!r.ok) return emptyList()
        return r.output.lines().mapNotNull { line ->
            val f = line.split('\u001f')
            if (f.size == 3) GitStash(f[0], f[1], f[2]) else null
        }
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

    suspend fun setIdentity(name: String, email: String) {
        run(paths.home, "config", "--global", "user.name", name)
        run(paths.home, "config", "--global", "user.email", email)
    }

    suspend fun ghStatus(): GhStatus {
        val gh = File(paths.bin, "gh")
        if (!gh.exists()) return GhStatus(installed = false, signedIn = false, account = null)
        val r = CommandRunner.run(paths, listOf(gh.path, "auth", "status", "--hostname", "github.com"), extraEnv = env)
        val account = ACCOUNT.find(r.output)?.groupValues?.get(1)
        return GhStatus(installed = true, signedIn = r.ok, account = account)
    }

    /**
     * Repositories of the signed-in account and of every organization it belongs to (each org's
     * repos marked [GhRepo.isOrg]), newest push first. Null if gh can't list the account's own.
     */
    suspend fun ghRepos(limit: Int = 200): List<GhRepo>? = coroutineScope {
        val gh = File(paths.bin, "gh")
        if (!gh.exists()) return@coroutineScope null
        fun list(owner: String?) = async {
            val r = CommandRunner.shell(
                paths,
                "$GH_ENV ${shellQuote(gh.path)} repo list ${owner?.let(::shellQuote).orEmpty()} --limit $limit " +
                    "--json nameWithOwner,name,description,isPrivate,isFork,pushedAt 2>/dev/null",
                timeoutMs = 60_000,
            )
            if (r.ok) parseRepos(r.output) else null
        }
        val own = list(null)
        val orgs = CommandRunner.shell(paths, "$GH_ENV ${shellQuote(gh.path)} api user/orgs --jq '.[].login' 2>/dev/null", timeoutMs = 30_000)
            .takeIf { it.ok }?.output?.lines()?.map { it.trim() }?.filter { it.isNotEmpty() && ' ' !in it }.orEmpty()
        val orgRepos = orgs.map { list(it) }.awaitAll().filterNotNull().flatten().map { it.copy(isOrg = true) }
        val mine = own.await() ?: return@coroutineScope null
        (mine + orgRepos).distinctBy { it.slug.lowercase() }.sortedByDescending { it.updatedAt }
    }

    /** "owner/name" (lowercase) of the origin remote if it's on GitHub; null otherwise. */
    suspend fun originSlug(dir: File): String? {
        if (!File(dir, ".git").exists()) return null
        val r = runParsed(dir, "remote", "get-url", "origin", timeoutMs = 10_000)
        return if (r.ok) githubSlug(r.firstLine) else null
    }

    companion object {
        private const val GH_ENV = "GH_PROMPT_DISABLED=1 GH_NO_UPDATE_NOTIFIER=1"
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
