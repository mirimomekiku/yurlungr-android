package dev.yurlungr.app.editor

/**
 * A unified line diff of two texts, in the `@@ -a,b +c,d @@` form the diff viewer reads: for comparing
 * the editor's unsaved text with a newer version on disk, without writing either to a file for git.
 * Common lines at both ends are matched first; the middle gets an exact LCS when it's small enough, and
 * is shown as replaced wholesale when it isn't (a 1 MB file rewritten top to bottom).
 */
internal object TextDiff {
    private const val CONTEXT = 3
    private const val MAX_CELLS = 1_000_000L

    /** Empty when the texts are the same. */
    fun unified(old: String, new: String): String {
        if (old == new) return ""
        val a = old.split('\n')
        val b = new.split('\n')
        var pre = 0
        while (pre < a.size && pre < b.size && a[pre] == b[pre]) pre++
        var suf = 0
        while (suf < a.size - pre && suf < b.size - pre && a[a.size - 1 - suf] == b[b.size - 1 - suf]) suf++
        val ops = ArrayList<Pair<Char, String>>(a.size + b.size)
        for (i in 0 until pre) ops += ' ' to a[i]
        ops += middle(a.subList(pre, a.size - suf), b.subList(pre, b.size - suf))
        for (i in a.size - suf until a.size) ops += ' ' to a[i]
        return hunks(ops)
    }

    private fun middle(a: List<String>, b: List<String>): List<Pair<Char, String>> {
        if (a.size.toLong() * b.size > MAX_CELLS) return a.map { '-' to it } + b.map { '+' to it }
        val n = a.size
        val m = b.size
        // lcs[i][j]: the longest common subsequence of a[i..] and b[j..].
        val lcs = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) for (j in m - 1 downTo 0) {
            lcs[i][j] = if (a[i] == b[j]) lcs[i + 1][j + 1] + 1 else maxOf(lcs[i + 1][j], lcs[i][j + 1])
        }
        val out = ArrayList<Pair<Char, String>>(n + m)
        var i = 0
        var j = 0
        while (i < n && j < m) when {
            a[i] == b[j] -> { out += ' ' to a[i]; i++; j++ }
            lcs[i + 1][j] >= lcs[i][j + 1] -> out += '-' to a[i++]
            else -> out += '+' to b[j++]
        }
        while (i < n) out += '-' to a[i++]
        while (j < m) out += '+' to b[j++]
        return out
    }

    private fun hunks(ops: List<Pair<Char, String>>): String {
        val changed = ops.indices.filter { ops[it].first != ' ' }
        if (changed.isEmpty()) return ""
        // Line numbers (0-based) in each text before op k.
        val oldAt = IntArray(ops.size + 1)
        val newAt = IntArray(ops.size + 1)
        for (k in ops.indices) {
            oldAt[k + 1] = oldAt[k] + if (ops[k].first != '+') 1 else 0
            newAt[k + 1] = newAt[k] + if (ops[k].first != '-') 1 else 0
        }
        val sb = StringBuilder()
        var g = 0
        while (g < changed.size) {
            var last = g
            // Changes close enough that their context would overlap share a hunk.
            while (last + 1 < changed.size && changed[last + 1] - changed[last] <= 2 * CONTEXT + 1) last++
            val from = maxOf(0, changed[g] - CONTEXT)
            val to = minOf(ops.size, changed[last] + CONTEXT + 1)
            sb.append("@@ -").append(oldAt[from] + 1).append(',').append(oldAt[to] - oldAt[from])
                .append(" +").append(newAt[from] + 1).append(',').append(newAt[to] - newAt[from]).append(" @@\n")
            for (k in from until to) sb.append(ops[k].first).append(ops[k].second).append('\n')
            g = last + 1
        }
        return sb.toString()
    }
}
