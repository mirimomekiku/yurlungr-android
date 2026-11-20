package dev.yurlungr.app.projects

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** Which open pull requests the list shows. */
enum class PrFilter(val label: String) { All("All"), Mine("Mine"), ReviewRequested("Review requested") }

/** One CI check of a pull request, from `statusCheckRollup` (a check run or a commit status). */
data class PrCheck(val name: String, val state: State, val workflow: String? = null, val url: String? = null) {
    enum class State { Passed, Failed, Pending, Skipped }
}

/** The checks of a pull request, counted. */
data class CheckSummary(val checks: List<PrCheck>) {
    val failed: List<PrCheck> get() = checks.filter { it.state == PrCheck.State.Failed }
    val pending: Int get() = checks.count { it.state == PrCheck.State.Pending }
    val passed: Int get() = checks.count { it.state == PrCheck.State.Passed }
    val skipped: Int get() = checks.count { it.state == PrCheck.State.Skipped }

    /** Failed beats pending beats passed; null when there are no checks. */
    val overall: PrCheck.State?
        get() = when {
            checks.isEmpty() -> null
            failed.isNotEmpty() -> PrCheck.State.Failed
            pending > 0 -> PrCheck.State.Pending
            passed > 0 -> PrCheck.State.Passed
            else -> PrCheck.State.Skipped
        }

    /** "2 failing · 1 running · 5 passed", or "No checks". */
    val label: String
        get() = if (checks.isEmpty()) "No checks" else listOfNotNull(
            failed.size.takeIf { it > 0 }?.let { "$it failing" },
            pending.takeIf { it > 0 }?.let { "$it running" },
            passed.takeIf { it > 0 }?.let { "$it passed" },
            skipped.takeIf { it > 0 }?.let { "$it skipped" },
        ).joinToString(" · ")

    companion object {
        val None = CheckSummary(emptyList())
    }
}

/** A pull request in the list, from `gh pr list --json …`. */
data class PrSummary(
    val number: Int,
    val title: String,
    val author: String,
    val headRef: String,
    val baseRef: String,
    val isDraft: Boolean,
    /** APPROVED, CHANGES_REQUESTED, REVIEW_REQUIRED, or null when none applies. */
    val reviewDecision: String?,
    val checks: CheckSummary,
    /** Epoch millis, 0 when unknown. */
    val updatedAt: Long,
    val additions: Int,
    val deletions: Int,
    val url: String?,
)

/** A conversation entry: a comment, or a submitted review (with its [reviewState]). */
data class PrComment(val author: String, val body: String, val createdAt: Long, val reviewState: String? = null)

/** An inline review comment on a line of the diff. */
data class PrReviewComment(val path: String, val line: Int?, val author: String, val body: String, val createdAt: Long, val inReplyTo: Long? = null)

data class PrFile(val path: String, val additions: Int, val deletions: Int)

/** A pull request in full, from `gh pr view --json …`. */
data class PrDetail(
    val number: Int,
    val title: String,
    val author: String,
    val headRef: String,
    val baseRef: String,
    val isDraft: Boolean,
    /** OPEN, CLOSED or MERGED. */
    val state: String,
    val body: String,
    val url: String?,
    val reviewDecision: String?,
    val checks: CheckSummary,
    /** Comments and reviews, oldest first. */
    val conversation: List<PrComment>,
    val files: List<PrFile>,
    val additions: Int,
    val deletions: Int,
    /** MERGEABLE, CONFLICTING or UNKNOWN. */
    val mergeable: String?,
    /** The commit the branch pointed at when this was loaded: merging is pinned to it. */
    val headRefOid: String? = null,
) {
    val isOpen: Boolean get() = state.equals("OPEN", ignoreCase = true)
}

/** One file's part of `gh pr diff`. */
data class PrFileDiff(val path: String, val oldPath: String?, val text: String, val added: Int, val removed: Int, val binary: Boolean) {
    val lineCount: Int get() = text.count { it == '\n' } + 1
}

/** Parsing of gh's pull request output; pure, so it can be unit-tested. */
object PrParse {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.int(k: String): Int = (this[k] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0
    private fun JsonObject.bool(k: String): Boolean = (this[k] as? JsonPrimitive)?.content == "true"
    private fun JsonObject.obj(k: String): JsonObject? = this[k] as? JsonObject
    private fun JsonObject.arr(k: String): List<JsonElement> = (this[k] as? JsonArray).orEmpty()
    private fun JsonObject.login(k: String): String = obj(k)?.str("login")?.ifBlank { null } ?: "ghost"

    /** gh's timestamps: "2024-05-01T10:20:30Z" (sometimes with fractions or an offset). */
    fun time(text: String?): Long {
        if (text.isNullOrBlank()) return 0L
        val t = text.trim().replace(Regex("""\.\d+"""), "").replace(Regex("""Z$"""), "+0000").replace(Regex("""([+-]\d\d):(\d\d)$"""), "$1$2")
        return runCatching { SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(t)?.time }
            .getOrNull() ?: 0L
    }

    private fun parse(text: String, open: Char): JsonElement? {
        val start = text.indexOf(open)
        if (start < 0) return null
        return runCatching { json.parseToJsonElement(text.substring(start)) }.getOrNull()
    }

    /** One `statusCheckRollup` entry: a CheckRun (status + conclusion) or a StatusContext (state). */
    fun check(o: JsonObject): PrCheck? {
        val isStatus = o.str("__typename") == "StatusContext" || (o["context"] != null && o["conclusion"] == null && o["state"] != null)
        return if (isStatus) {
            val name = o.str("context")?.ifBlank { null } ?: return null
            val state = when (o.str("state")?.uppercase()) {
                "SUCCESS" -> PrCheck.State.Passed
                "FAILURE", "ERROR" -> PrCheck.State.Failed
                else -> PrCheck.State.Pending // PENDING, EXPECTED
            }
            PrCheck(name, state, url = o.str("targetUrl")?.takeIf { it.startsWith("https://") })
        } else {
            val name = o.str("name")?.ifBlank { null } ?: return null
            val status = o.str("status")?.uppercase()
            val state = if (status != null && status != "COMPLETED") PrCheck.State.Pending else when (o.str("conclusion")?.uppercase()) {
                "SUCCESS", "NEUTRAL" -> PrCheck.State.Passed
                "SKIPPED" -> PrCheck.State.Skipped
                null, "" -> PrCheck.State.Pending
                else -> PrCheck.State.Failed // FAILURE, TIMED_OUT, CANCELLED, ACTION_REQUIRED, STARTUP_FAILURE, STALE
            }
            PrCheck(name, state, o.str("workflowName")?.ifBlank { null }, o.str("detailsUrl")?.takeIf { it.startsWith("https://") })
        }
    }

    fun checks(o: JsonObject): CheckSummary =
        CheckSummary(o.arr("statusCheckRollup").mapNotNull { (it as? JsonObject)?.let(::check) })

    private fun decision(o: JsonObject) = o.str("reviewDecision")?.ifBlank { null }

    /** `gh pr list --json …`; null if the output isn't a JSON array. Entries without a number are skipped. */
    fun list(text: String): List<PrSummary>? {
        val arr = parse(text, '[') as? JsonArray ?: return null
        return arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val number = o.int("number").takeIf { it > 0 } ?: return@mapNotNull null
            PrSummary(
                number = number,
                title = o.str("title").orEmpty(),
                author = o.login("author"),
                headRef = o.str("headRefName").orEmpty(),
                baseRef = o.str("baseRefName").orEmpty(),
                isDraft = o.bool("isDraft"),
                reviewDecision = decision(o),
                checks = checks(o),
                updatedAt = time(o.str("updatedAt")),
                additions = o.int("additions"),
                deletions = o.int("deletions"),
                url = o.str("url")?.takeIf { it.startsWith("https://") },
            )
        }
    }

    /** `gh pr view N --json …`; null if it isn't a pull request object. */
    fun detail(text: String): PrDetail? {
        val o = parse(text, '{') as? JsonObject ?: return null
        val number = o.int("number").takeIf { it > 0 } ?: return null
        val comments = o.arr("comments").mapNotNull { el ->
            val c = el as? JsonObject ?: return@mapNotNull null
            PrComment(c.login("author"), c.str("body").orEmpty(), time(c.str("createdAt")))
        }
        // A review with neither text nor a verdict (a bare "COMMENTED" holding only inline comments) says nothing here.
        val reviews = o.arr("reviews").mapNotNull { el ->
            val r = el as? JsonObject ?: return@mapNotNull null
            val state = r.str("state")?.uppercase()
            val body = r.str("body").orEmpty()
            if (body.isBlank() && (state == null || state == "COMMENTED" || state == "PENDING")) return@mapNotNull null
            PrComment(r.login("author"), body, time(r.str("submittedAt") ?: r.str("createdAt")), reviewState = state)
        }
        return PrDetail(
            number = number,
            title = o.str("title").orEmpty(),
            author = o.login("author"),
            headRef = o.str("headRefName").orEmpty(),
            baseRef = o.str("baseRefName").orEmpty(),
            isDraft = o.bool("isDraft"),
            state = o.str("state").orEmpty(),
            body = o.str("body").orEmpty(),
            url = o.str("url")?.takeIf { it.startsWith("https://") },
            reviewDecision = decision(o),
            checks = checks(o),
            conversation = (comments + reviews).sortedBy { it.createdAt },
            files = o.arr("files").mapNotNull { el ->
                val f = el as? JsonObject ?: return@mapNotNull null
                PrFile(f.str("path") ?: return@mapNotNull null, f.int("additions"), f.int("deletions"))
            },
            additions = o.int("additions"),
            deletions = o.int("deletions"),
            mergeable = o.str("mergeable")?.ifBlank { null },
            headRefOid = o.str("headRefOid")?.takeIf { it.length == 40 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } },
        )
    }

    /** The REST `pulls/N/comments` reply (inline review comments); null if it isn't an array. */
    fun reviewComments(text: String): List<PrReviewComment>? {
        val arr = parse(text, '[') as? JsonArray ?: return null
        return arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val path = o.str("path") ?: return@mapNotNull null
            val line = (o["line"] as? JsonPrimitive)?.content?.toIntOrNull()
                ?: (o["original_line"] as? JsonPrimitive)?.content?.toIntOrNull()
            PrReviewComment(
                path = path,
                line = line,
                author = o.login("user"),
                body = o.str("body").orEmpty(),
                createdAt = time(o.str("created_at")),
                inReplyTo = (o["in_reply_to_id"] as? JsonPrimitive)?.content?.toLongOrNull(),
            )
        }.sortedBy { it.createdAt }
    }

    private val GIT_HEADER = Regex("""^diff --git (?:"a/(.*)"|a/(.*?)) (?:"b/(.*)"|b/(.*))$""")

    /**
     * Splits `gh pr diff` (a git diff of many files) into one diff per file. Names come from the
     * "---"/"+++" headers (C-quoted ones unquoted), else rename lines, else the "diff --git" line.
     */
    fun splitDiff(text: String): List<PrFileDiff> {
        val out = ArrayList<PrFileDiff>()
        var chunk: MutableList<String>? = null
        fun flush() {
            chunk?.let { lines -> fileDiff(lines)?.let(out::add) }
            chunk = null
        }
        for (raw in text.split('\n')) {
            val line = raw.removeSuffix("\r")
            if (line.startsWith("diff --git ")) {
                flush()
                chunk = mutableListOf(line)
            } else {
                chunk?.add(line)
            }
        }
        flush()
        return out
    }

    private fun headerName(raw: String): String? {
        val name = Git.unquoteC(raw.trimEnd('\t'))
        return if (name == "/dev/null") null else name
    }

    private fun fileDiff(lines: List<String>): PrFileDiff? {
        var oldName: String? = null
        var newName: String? = null
        var renameFrom: String? = null
        var renameTo: String? = null
        var sawHeaders = false
        var inHunk = false
        var added = 0
        var removed = 0
        var binary = false
        for (line in lines.drop(1)) {
            when {
                line.startsWith("@@") -> inHunk = true
                !inHunk && line.startsWith("--- ") -> { sawHeaders = true; oldName = headerName(line.removePrefix("--- "))?.removePrefix("a/") }
                !inHunk && line.startsWith("+++ ") -> { sawHeaders = true; newName = headerName(line.removePrefix("+++ "))?.removePrefix("b/") }
                !inHunk && line.startsWith("rename from ") -> renameFrom = Git.unquoteC(line.removePrefix("rename from "))
                !inHunk && line.startsWith("rename to ") -> renameTo = Git.unquoteC(line.removePrefix("rename to "))
                !inHunk && (line.startsWith("Binary files ") || line == "GIT binary patch") -> binary = true
                inHunk && line.startsWith("+") -> added++
                inHunk && line.startsWith("-") -> removed++
            }
        }
        val fromHeader = GIT_HEADER.find(lines.first())?.groupValues
        val headerOld = fromHeader?.let { g -> g[1].takeIf { it.isNotEmpty() }?.let { Git.unquoteC("\"$it\"") } ?: g[2] }
        val headerNew = fromHeader?.let { g -> g[3].takeIf { it.isNotEmpty() }?.let { Git.unquoteC("\"$it\"") } ?: g[4] }
        val path = newName ?: renameTo ?: (if (sawHeaders) oldName else null) ?: headerNew ?: oldName ?: headerOld ?: return null
        val old = renameFrom ?: oldName?.takeIf { it != path && newName != null }
        // Drop the trailing empty line a split leaves behind, so the text ends like git's own.
        val body = lines.dropLastWhile { it.isEmpty() }.joinToString("\n")
        return PrFileDiff(path, old, body, added, removed, binary)
    }

    /** "+12 −3 in 4 files" style diff stat, one line per file, for prompts. */
    fun diffStat(files: List<PrFile>, limit: Int = 40): String = buildString {
        files.take(limit).forEach { append("  ").append(it.path).append(" (+").append(it.additions).append(" -").append(it.deletions).append(")\n") }
        if (files.size > limit) append("  … and ${files.size - limit} more files\n")
    }.trimEnd()
}
