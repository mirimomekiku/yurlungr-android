package dev.yurlungr.app.projects

/** Which part of a file's change a diff shows: all of it (against HEAD), what's staged, or what isn't. */
enum class DiffSide { All, Staged, Unstaged }

/**
 * One file's diff split into its header and hunks, as raw lines: CRs (CRLF files) and "\ No
 * newline at end of file" markers are kept byte for byte, so one hunk can go back to git as a
 * patch of its own.
 */
data class FilePatch(val header: List<String>, val hunks: List<List<String>>)

/**
 * Stage or unstage a single hunk: the file's header plus that hunk is a complete patch, applied
 * to the index with `git apply --cached` (unstaging: `--reverse`, from the staged diff).
 *
 * The hunk keeps its original line numbers. Staging applies a hunk of the unstaged diff
 * (index → work tree), whose old side is the index as it is now; unstaging reverse-applies a
 * hunk of the staged diff (HEAD → index), whose new side is the index as it is now. Either
 * way the numbers git checks are exact, whichever other hunks are left out.
 */
object HunkPatch {
    private val HUNK = Regex("""^@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@.*$""")

    /** Header lines that mean the change isn't a plain edit of one existing text file. */
    private val UNSUPPORTED = listOf(
        "new file mode", "deleted file mode", "rename from", "rename to", "copy from", "copy to",
        "similarity index", "dissimilarity index", "old mode", "new mode", "Binary files", "GIT binary patch",
    )

    /**
     * The hunks of [diff], or null when they can't be staged one by one: not exactly one file,
     * a new, deleted, renamed or binary file, a mode change, a diff cut short for showing, or
     * anything that doesn't add up (line counts that don't match the hunk headers).
     */
    fun parse(diff: String): FilePatch? {
        if (diff.contains(Git.DIFF_TRUNCATED)) return null
        val lines = diff.split('\n').let { if (it.isNotEmpty() && it.last().isEmpty()) it.dropLast(1) else it }
        val firstHunk = lines.indexOfFirst { HUNK.matches(it.removeSuffix("\r")) }
        if (firstHunk <= 0) return null
        val header = lines.subList(0, firstHunk)
        if (header.count { it.startsWith("diff --git ") } != 1 || !header.first().startsWith("diff --git ")) return null
        if (header.none { it.startsWith("--- ") } || header.none { it.startsWith("+++ ") }) return null
        if (header.any { line -> UNSUPPORTED.any { line.startsWith(it) } }) return null

        val hunks = ArrayList<List<String>>()
        var i = firstHunk
        while (i < lines.size) {
            val m = HUNK.matchEntire(lines[i].removeSuffix("\r")) ?: return null
            var old = m.groupValues[2].ifEmpty { "1" }.toInt()
            var new = m.groupValues[4].ifEmpty { "1" }.toInt()
            val hunk = arrayListOf(lines[i])
            i++
            // Read exactly as many lines as the header says: a content line may itself start with "@@".
            while (old > 0 || new > 0) {
                val line = lines.getOrNull(i) ?: return null
                when {
                    // An empty line is an empty context line (diff.suppressBlankEmpty); git apply reads it so too.
                    line.isEmpty() || line[0] == ' ' -> { old--; new-- }
                    line[0] == '-' -> old--
                    line[0] == '+' -> new--
                    line[0] == '\\' -> Unit
                    else -> return null
                }
                if (old < 0 || new < 0) return null
                hunk += line
                i++
            }
            // The marker for the hunk's last line comes after it.
            while (i < lines.size && lines[i].startsWith("\\")) hunk += lines[i++]
            hunks += hunk
        }
        return if (hunks.isEmpty()) null else FilePatch(header, hunks)
    }

    /** A patch of [diff]'s header and only its hunk number [index], or null when that can't be made. */
    fun forHunk(diff: String, index: Int): String? {
        val patch = parse(diff) ?: return null
        val hunk = patch.hunks.getOrNull(index) ?: return null
        return (patch.header + hunk).joinToString("\n", postfix = "\n")
    }
}
