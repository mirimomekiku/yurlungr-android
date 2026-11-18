package dev.yurlungr.app.env

/** One variable in a `.env` file, as loaders read it. */
data class EnvEntry(
    val key: String,
    val value: String,
    val export: Boolean = false,
    /** The quote it was written with (`"`, `'` or a backtick), or null when bare. */
    val quote: Char? = null,
)

/** A piece of a `.env` file: a variable, or anything else (comments, blank lines, junk) kept byte for byte. */
sealed interface EnvSegment {
    /** The exact original text, line ending(s) included. */
    val raw: String

    data class Text(override val raw: String) : EnvSegment

    data class Variable(
        override val raw: String,
        val entry: EnvEntry,
        /** Everything before the value: indentation, `export `, the key, `=` and the spaces around it. */
        val prefix: String,
        /** What follows the value on its last line (spaces, an inline comment), without the line ending. */
        val suffix: String,
        /** The line ending after the variable ("" on a last line without one). */
        val lineEnding: String,
    ) : EnvSegment
}

/**
 * A parsed `.env` file that writes back exactly what it read: only variables that were changed
 * are re-rendered, and those keep their `export ` prefix, spacing, quote style and inline comment.
 */
class EnvDocument(val segments: List<EnvSegment>) {
    val variables: List<EnvSegment.Variable> get() = segments.filterIsInstance<EnvSegment.Variable>()

    /** Every variable in file order; a key set twice appears twice (the last one wins in most loaders). */
    val entries: List<EnvEntry> get() = variables.map { it.entry }

    /** Distinct keys in the order they first appear. */
    val keys: List<String> get() = entries.map { it.key }.distinct()

    fun value(key: String): String? = entries.lastOrNull { it.key == key }?.value

    operator fun contains(key: String): Boolean = entries.any { it.key == key }

    /** CRLF when the file already uses it, so added lines match. */
    val newline: String
        get() {
            val text = segments.firstOrNull { it.raw.contains('\n') }?.raw ?: return "\n"
            return if (text.contains("\r\n")) "\r\n" else "\n"
        }

    fun render(): String = segments.joinToString("") { it.raw }

    /**
     * [key] set to [value]: the last line that sets it is rewritten in place (keeping its quote style
     * where the value allows), or a new `KEY=value` line is appended.
     */
    fun set(key: String, value: String): EnvDocument = replace(key, key, value)

    /** Renames [oldKey] to [newKey] (in place) and sets its value; appends when [oldKey] isn't there. */
    fun replace(oldKey: String, newKey: String, value: String): EnvDocument {
        require(EnvFormat.isValidKey(newKey)) { "Not a valid variable name: $newKey" }
        val index = segments.indexOfLast { it is EnvSegment.Variable && it.entry.key == oldKey }
        if (index < 0) return append(newKey, value)
        val old = segments[index] as EnvSegment.Variable
        if (old.entry.key == newKey && old.entry.value == value) return this
        val prefix = if (newKey == old.entry.key) old.prefix else {
            // The key sits right before "=" (and its spaces); "export e=" must not become "xport…".
            val eq = Regex("[ \t]*=[ \t]*$").find(old.prefix)?.value.orEmpty()
            val keyStart = old.prefix.length - eq.length - old.entry.key.length
            old.prefix.substring(0, keyStart) + newKey + eq
        }
        val quote = EnvFormat.chooseQuote(value, old.entry.quote)
        val encoded = EnvFormat.encode(value, quote)
        val updated = EnvSegment.Variable(
            raw = prefix + encoded + old.suffix + old.lineEnding,
            entry = old.entry.copy(key = newKey, value = value, quote = quote),
            prefix = prefix, suffix = old.suffix, lineEnding = old.lineEnding,
        )
        // Other lines setting the new name would override the edit: drop them.
        return EnvDocument(segments.mapIndexedNotNull { i, s ->
            when {
                i == index -> updated
                newKey != oldKey && s is EnvSegment.Variable && s.entry.key == newKey -> null
                else -> s
            }
        })
    }

    /** Removes every line that sets [key]; comments and blank lines around it stay. */
    fun remove(key: String): EnvDocument =
        EnvDocument(segments.filterNot { it is EnvSegment.Variable && it.entry.key == key })

    private fun append(key: String, value: String): EnvDocument {
        val nl = newline
        val head = segments.toMutableList()
        // A last line without a line ending gets one, so the new variable starts on its own line.
        head.lastOrNull()?.let { last ->
            if (!last.raw.endsWith("\n")) head[head.lastIndex] = when (last) {
                is EnvSegment.Text -> EnvSegment.Text(last.raw + nl)
                is EnvSegment.Variable -> last.copy(raw = last.raw + nl, lineEnding = nl)
            }
        }
        val quote = EnvFormat.chooseQuote(value, null)
        val prefix = "$key="
        head += EnvSegment.Variable(prefix + EnvFormat.encode(value, quote) + nl, EnvEntry(key, value, false, quote), prefix, "", nl)
        return EnvDocument(head)
    }
}

/**
 * The `.env` dialect most loaders agree on (dotenv for Node, python-dotenv, Docker Compose, Vite):
 * `KEY=value`, optional `export `, `#` comments, single, double or backtick quotes, and quoted
 * values that span lines. In bare values `#` starts a comment only after whitespace.
 */
object EnvFormat {
    private val ASSIGNMENT = Regex("""^([ \t]*)(export[ \t]+)?([A-Za-z_][A-Za-z0-9_.\-]*)([ \t]*=[ \t]*)(.*)$""")
    private val KEY = Regex("""^[A-Za-z_][A-Za-z0-9_.\-]*$""")
    private const val QUOTES = "\"'`"

    fun isValidKey(key: String): Boolean = KEY.matches(key)

    fun parse(text: String): EnvDocument {
        val lines = splitLines(text)
        val out = mutableListOf<EnvSegment>()
        var i = 0
        while (i < lines.size) {
            val (content, ending) = lines[i]
            val m = ASSIGNMENT.matchEntire(content)
            if (m == null) {
                out += EnvSegment.Text(content + ending)
                i++
                continue
            }
            val (indent, export, key, eq, rest) = m.destructured
            val prefix = indent + export + key + eq
            val q = rest.firstOrNull()?.takeIf { it in QUOTES }
            val quoted = q?.let { readQuoted(lines, i, prefix.length + 1, it) }
            if (quoted != null) {
                val (rawValue, lastLine, suffix) = quoted
                val raw = (i..lastLine).joinToString("") { lines[it].first + lines[it].second }
                val value = decode(rawValue.replace("\r\n", "\n"), q)
                out += EnvSegment.Variable(raw, EnvEntry(key, value, export.isNotEmpty(), q), prefix, suffix, lines[lastLine].second)
                i = lastLine + 1
            } else {
                // Bare (or an unterminated quote, read as-is like the loaders do).
                val comment = commentStart(rest, afterSpace = eq.last().isWhitespace())
                val value = (if (comment >= 0) rest.substring(0, comment) else rest).trimEnd()
                out += EnvSegment.Variable(content + ending, EnvEntry(key, value, export.isNotEmpty(), null),
                    prefix, rest.substring(value.length), ending)
                i++
            }
        }
        return EnvDocument(out)
    }

    /** Index of a `#` that starts a comment in a bare value, or -1. */
    private fun commentStart(rest: String, afterSpace: Boolean): Int {
        for (k in rest.indices) {
            if (rest[k] != '#') continue
            if (if (k == 0) afterSpace else rest[k - 1] == ' ' || rest[k - 1] == '\t') return k
        }
        return -1
    }

    /**
     * Reads a quoted value starting at [start] of line [first] (just after the opening [q]). Returns
     * the raw text between the quotes, the line where it closes and what follows the closing quote;
     * null when it never closes.
     */
    private fun readQuoted(lines: List<Pair<String, String>>, first: Int, start: Int, q: Char): Triple<String, Int, String>? {
        val raw = StringBuilder()
        var line = first
        var pos = start
        while (line < lines.size) {
            val text = lines[line].first
            var k = pos
            while (k < text.length) {
                val c = text[k]
                if (c == '\\' && k + 1 < text.length && (q == '"' || text[k + 1] == q)) {
                    raw.append(c).append(text[k + 1]); k += 2; continue
                }
                if (c == q) return Triple(raw.toString(), line, text.substring(k + 1))
                raw.append(c); k++
            }
            raw.append(lines[line].second)
            if (lines[line].second.isEmpty()) return null
            line++
            pos = 0
        }
        return null
    }

    private fun decode(raw: String, q: Char): String {
        val out = StringBuilder()
        var k = 0
        while (k < raw.length) {
            val c = raw[k]
            if (c == '\\' && k + 1 < raw.length) {
                val n = raw[k + 1]
                val mapped = if (q == '"') when (n) {
                    'n' -> '\n'; 'r' -> '\r'; 't' -> '\t'; '"' -> '"'; '\\' -> '\\'; else -> null
                } else if (n == q) q else null
                if (mapped != null) { out.append(mapped); k += 2; continue }
            }
            out.append(c); k++
        }
        return out.toString()
    }

    /**
     * The quote to write [value] with: the one it had when that still reads back the same,
     * otherwise none when bare is safe, single quotes when they are, else double quotes.
     */
    fun chooseQuote(value: String, previous: Char?): Char? {
        val singleOk = '\'' !in value && '\\' !in value && '\n' !in value && '\r' !in value
        val backtickOk = '`' !in value && '\\' !in value && '\n' !in value && '\r' !in value
        return when {
            previous == '"' -> '"'
            previous == '\'' && singleOk -> '\''
            previous == '`' && backtickOk -> '`'
            previous == null && !needsQuotes(value) -> null
            singleOk -> '\''
            else -> '"'
        }
    }

    /** Whether [value] would read back differently (or be cut short) without quotes. */
    fun needsQuotes(value: String): Boolean = value.isNotEmpty() && (
        value.first().isWhitespace() || value.last().isWhitespace() || value.first() in QUOTES ||
            value.any { it == '#' || it == '\n' || it == '\r' || it == '\\' || it == '$' || it == ' ' || it == '\t' || it == '"' || it == '\'' || it == '`' }
        )

    fun encode(value: String, quote: Char?): String = when (quote) {
        null -> value
        '"' -> buildString {
            append('"')
            for (c in value) when (c) {
                '\\' -> append("\\\\"); '"' -> append("\\\""); '\n' -> append("\\n"); '\r' -> append("\\r")
                else -> append(c)
            }
            append('"')
        }
        else -> "$quote$value$quote"
    }

    /** Lines with their endings ("\n", "\r\n" or "" for a last line without one). */
    private fun splitLines(text: String): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        var start = 0
        while (start < text.length) {
            val nl = text.indexOf('\n', start)
            if (nl < 0) { out += text.substring(start) to ""; break }
            val crlf = nl > start && text[nl - 1] == '\r'
            out += text.substring(start, if (crlf) nl - 1 else nl) to (if (crlf) "\r\n" else "\n")
            start = nl + 1
        }
        return out
    }
}

/** `.gitignore` checks simple enough to run without git: root-level file names only. */
object EnvGitignore {
    /**
     * Whether [gitignore] ignores the file [name] at the repository root. Handles `*`, `?`, a
     * leading slash or double-star folder, and `!` negations (the last matching pattern wins).
     */
    fun ignores(gitignore: String, name: String): Boolean {
        var ignored = false
        for (rawLine in gitignore.lines()) {
            var p = rawLine.trimEnd('\r').trimEnd()
            if (p.isEmpty() || p.startsWith("#")) continue
            val negate = p.startsWith("!")
            if (negate) p = p.substring(1)
            if (p.startsWith("\\")) p = p.substring(1)
            if (p.endsWith("/")) continue // folders only
            p = p.removePrefix("**/").removePrefix("/")
            if ('/' in p) continue // a path in a subfolder, not the root file
            if (glob(p).matches(name)) ignored = !negate
        }
        return ignored
    }

    private fun glob(pattern: String): Regex {
        val sb = StringBuilder()
        for (c in pattern) when (c) {
            '*' -> sb.append("[^/]*")
            '?' -> sb.append("[^/]")
            else -> sb.append(Regex.escape(c.toString()))
        }
        return Regex(sb.toString())
    }

    /** [gitignore] with [name] added on its own line (anchored to the root). */
    fun withEntry(gitignore: String, name: String): String {
        val nl = if (gitignore.contains("\r\n")) "\r\n" else "\n"
        val head = if (gitignore.isEmpty() || gitignore.endsWith("\n")) gitignore else gitignore + nl
        return "$head/$name$nl"
    }
}

/** The `.env` files a project can have, and which of them the editor offers. */
object EnvFiles {
    /** Shown when present; `.env` is always offered (it's created on first save). */
    val VARIANTS = listOf(".env", ".env.local", ".env.development")

    /** Templates teams commit, listing the variables everyone needs. */
    val EXAMPLES = listOf(".env.example", ".env.sample")

    /** Variables in [example] that [env] doesn't set, in the example's order. */
    fun missing(env: EnvDocument, example: EnvDocument): List<EnvEntry> {
        val have = env.keys.toSet()
        return example.entries.filter { it.key !in have }.distinctBy { it.key }
    }

    /** What the editor shows instead of a value: same idea as a password field, length hidden. */
    fun masked(value: String): String = if (value.isEmpty()) "(empty)" else "••••••••"
}
