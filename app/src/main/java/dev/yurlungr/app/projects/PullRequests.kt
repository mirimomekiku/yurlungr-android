package dev.yurlungr.app.projects

import dev.yurlungr.app.runtime.CommandResult
import dev.yurlungr.app.runtime.CommandRunner
import dev.yurlungr.app.runtime.RuntimePaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

enum class PrReviewKind(val label: String, val flag: String) {
    Approve("Approve", "--approve"),
    RequestChanges("Request changes", "--request-changes"),
    Comment("Comment", "--comment"),
}

enum class PrMergeMethod(val label: String, val flag: String, val description: String) {
    Merge("Merge commit", "--merge", "Keeps every commit and adds a merge commit"),
    Squash("Squash", "--squash", "Combines the commits into one"),
    Rebase("Rebase", "--rebase", "Replays the commits onto the base branch"),
}

/**
 * Pull requests through the GitHub CLI, run in the project folder (gh finds the repository from
 * its remotes). Every argument is passed on its own, never through a shell; texts go in a file.
 */
class GhPullRequests(private val paths: RuntimePaths) {
    private val gh = File(paths.bin, "gh")

    private suspend fun run(dir: File, args: List<String>, timeoutMs: Long = 60_000): CommandResult? {
        if (!gh.exists()) return null
        return CommandRunner.run(paths, listOf(gh.path) + args, cwd = dir, extraEnv = ENV, timeoutMs = timeoutMs, forParsing = true)
    }

    private fun missing(): Result<Nothing> = Result.failure(IllegalStateException("The GitHub CLI isn't installed."))

    private fun <T> parsed(r: CommandResult?, what: String, parse: (String) -> T?): Result<T> {
        if (r == null) return missing()
        if (!r.ok) return Result.failure(IllegalStateException(Git.ghError(r, what)))
        return parse(r.output)?.let { Result.success(it) } ?: Result.failure(IllegalStateException("Unexpected reply from $what."))
    }

    private fun done(r: CommandResult?, what: String): Result<String> = when {
        r == null -> missing()
        r.ok -> Result.success((r.output + "\n" + r.errorOutput).lines().map { it.trim() }.filter { it.isNotEmpty() }.takeLast(3).joinToString("\n"))
        else -> Result.failure(IllegalStateException(Git.ghError(r, what)))
    }

    suspend fun list(dir: File, filter: PrFilter): Result<List<PrSummary>> {
        val args = mutableListOf("pr", "list", "--state", "open", "--limit", "50", "--json", LIST_FIELDS)
        when (filter) {
            PrFilter.All -> Unit
            PrFilter.Mine -> args += listOf("--author", "@me")
            PrFilter.ReviewRequested -> args += listOf("--search", "review-requested:@me")
        }
        return parsed(run(dir, args), "gh pr list", PrParse::list)
    }

    suspend fun view(dir: File, number: Int): Result<PrDetail> =
        parsed(run(dir, listOf("pr", "view", "$number", "--json", VIEW_FIELDS)), "gh pr view", PrParse::detail)

    suspend fun diff(dir: File, number: Int): Result<List<PrFileDiff>> =
        parsed(run(dir, listOf("pr", "diff", "$number", "--color", "never"), timeoutMs = 120_000), "gh pr diff") { PrParse.splitDiff(it) }

    /** Inline comments on the diff; `{owner}/{repo}` is filled in by gh from the current repository. */
    suspend fun reviewComments(dir: File, number: Int): Result<List<PrReviewComment>> =
        parsed(run(dir, listOf("api", "repos/{owner}/{repo}/pulls/$number/comments?per_page=100")), "gh api", PrParse::reviewComments)

    suspend fun review(dir: File, number: Int, kind: PrReviewKind, body: String): Result<String> =
        withBody(body) { file ->
            done(run(dir, listOf("pr", "review", "$number", kind.flag) + (file?.let { listOf("--body-file", it.path) } ?: emptyList()), 120_000), "gh pr review")
        }

    suspend fun comment(dir: File, number: Int, body: String): Result<String> =
        withBody(body) { file ->
            if (file == null) Result.failure(IllegalStateException("Write a comment first."))
            else done(run(dir, listOf("pr", "comment", "$number", "--body-file", file.path), 120_000), "gh pr comment")
        }

    suspend fun checkout(dir: File, number: Int): Result<String> =
        done(run(dir, listOf("pr", "checkout", "$number"), 180_000), "gh pr checkout")

    suspend fun merge(dir: File, number: Int, method: PrMergeMethod): Result<String> =
        done(run(dir, listOf("pr", "merge", "$number", method.flag), 180_000), "gh pr merge")

    /** Writes [body] to a private temporary file for --body-file (no length or quoting limits), deleted afterwards. */
    private suspend fun <T> withBody(body: String, block: suspend (File?) -> Result<T>): Result<T> {
        if (body.isBlank()) return block(null)
        val file = withContext(Dispatchers.IO) {
            runCatching {
                val dir = paths.tmp.takeIf { it.isDirectory || it.mkdirs() } ?: paths.filesDir
                File.createTempFile("pr-body-", ".md", dir).apply { writeText(body) }
            }
        }.getOrElse { return Result.failure(it) }
        return try {
            block(file)
        } finally {
            withContext(Dispatchers.IO) { file.delete() }
        }
    }

    companion object {
        const val LIST_FIELDS = "number,title,author,headRefName,baseRefName,isDraft,reviewDecision,statusCheckRollup,updatedAt,additions,deletions,url"
        const val VIEW_FIELDS = "number,title,author,headRefName,baseRefName,isDraft,state,body,url,reviewDecision," +
            "statusCheckRollup,comments,reviews,files,additions,deletions,mergeable"

        private val ENV = Git.ENV + mapOf("GH_PROMPT_DISABLED" to "1", "GH_NO_UPDATE_NOTIFIER" to "1", "GH_PAGER" to "cat", "NO_COLOR" to "1")
    }
}

/** First messages for an agent working on a pull request. */
object PrPrompts {
    private const val BODY_EXCERPT = 1500

    private fun excerpt(body: String): String {
        val text = body.trim()
        return if (text.length <= BODY_EXCERPT) text else text.take(BODY_EXCERPT).trimEnd() + "\n[…description cut short]"
    }

    /** Asks for a review of [pr] with findings only: no edits, no pushes, no posting on GitHub. */
    fun review(pr: PrDetail): String = buildString {
        append("Please review pull request #${pr.number}: \"${pr.title}\" by ${pr.author} (${pr.headRef} → ${pr.baseRef}).\n\n")
        if (pr.body.isNotBlank()) append("Its description:\n<description>\n").append(excerpt(pr.body)).append("\n</description>\n\n")
        if (pr.files.isNotEmpty()) {
            append("Files changed (+${pr.additions} -${pr.deletions}):\n").append(PrParse.diffStat(pr.files)).append("\n\n")
        }
        append("Fetch the full diff with `gh pr diff ${pr.number}` (and read surrounding code in this repository where it helps). ")
        append("Then give your findings: bugs and edge cases first, then security, performance and readability, each with the file and line. ")
        append("Say clearly if it looks good to merge. Don't change any files, don't push, and don't post anything to GitHub: just report back here.")
    }

    /** Asks to fix [pr]'s failing checks, on its branch, committing but not pushing. */
    fun fixChecks(pr: PrDetail, currentBranch: String?): String = buildString {
        val failing = pr.checks.failed
        append("The checks of pull request #${pr.number} (\"${pr.title}\", branch ${pr.headRef}) are failing")
        if (failing.isNotEmpty()) {
            append(":\n")
            failing.take(20).forEach { c -> append("- ").append(c.name).append(c.workflow?.let { " (workflow: $it)" } ?: "").append('\n') }
            if (failing.size > 20) append("- … and ${failing.size - 20} more\n")
        } else append(".\n")
        append('\n')
        if (currentBranch != pr.headRef) {
            append("This folder is on ${currentBranch ?: "another branch"}: first run `gh pr checkout ${pr.number}`. ")
            append("If there are uncommitted changes, stop and ask me before switching.\n")
        }
        append("Look at the failures with `gh pr checks ${pr.number}` and, for GitHub Actions, `gh run view <run-id> --log-failed`. ")
        append("Find the cause, fix it, and run the relevant build or tests here to confirm. ")
        append("Commit the fix with a clear message but don't push; tell me what was wrong and what you changed.")
    }
}
