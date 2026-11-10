package dev.yurlungr.app.projects

/** How far back the history goes. */
enum class HistoryPeriod(val label: String, val since: String?) {
    Any("Any time", null),
    Today("Today", "midnight"),
    Week("Last 7 days", "7 days ago"),
    Month("Last 30 days", "30 days ago"),
    Year("Last year", "1 year ago"),
}

/**
 * The history's filters. Applied by git itself (not to the 300 commits already loaded), so a
 * search finds old commits too. [query] matches commit messages, or a commit hash prefix.
 */
data class HistoryFilter(
    val author: String? = null,
    val period: HistoryPeriod = HistoryPeriod.Any,
    val query: String = "",
) {
    val isActive: Boolean get() = author != null || period != HistoryPeriod.Any || query.isNotBlank()

    /**
     * A query that looks like (part of) a commit hash: 7+ hex digits, or 4+ including a digit, so
     * words that happen to be hex ("add", "cafe", "dead") still search messages.
     */
    val hashQuery: String?
        get() = query.trim().lowercase().takeIf { q -> HASH.matches(q) && (q.length >= 7 || q.any(Char::isDigit)) }

    fun gitArgs(): List<String> = buildList {
        // Fixed strings, case-insensitive: names and words, not regular expressions.
        if (author != null || (query.isNotBlank() && hashQuery == null)) {
            add("--fixed-strings")
            add("--regexp-ignore-case")
        }
        author?.let { add("--author=$it") }
        period.since?.let { add("--since=$it") }
        if (query.isNotBlank() && hashQuery == null) add("--grep=${query.trim()}")
    }

    /** Hash searches are filtered here, on the commits git returned (the subject may match too). */
    fun matchesHash(commit: GitCommit): Boolean =
        hashQuery?.let { commit.hash.startsWith(it) || commit.subject.contains(it, ignoreCase = true) } ?: true

    companion object {
        private val HASH = Regex("^[0-9a-f]{4,40}$")
    }
}
