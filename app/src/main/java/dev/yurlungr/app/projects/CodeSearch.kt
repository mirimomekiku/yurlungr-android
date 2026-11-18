package dev.yurlungr.app.projects

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/** What to look for across projects. [glob] is a comma/space separated list like "*.kt, !*.test.ts". */
data class CodeQuery(
    val text: String,
    val regex: Boolean = false,
    val caseSensitive: Boolean = false,
    val glob: String = "",
    /** One project folder's name, or null for all of them. */
    val project: String? = null,
)

data class CodeLine(val number: Int, val text: String)

/** One matching line in [path] (relative to the project folder), with [ranges] to highlight in [text]. */
data class CodeMatch(
    val project: String,
    val path: String,
    val line: Int,
    val text: String,
    val ranges: List<IntRange>,
    val before: List<CodeLine> = emptyList(),
    val after: List<CodeLine> = emptyList(),
)

data class CodeFileGroup(val path: String, val matches: List<CodeMatch>)
data class CodeProjectGroup(val project: String, val files: List<CodeFileGroup>) {
    val count: Int get() = files.sumOf { it.matches.size }
}

/**
 * Find code across every project under ~/projects. ripgrep (`rg --json`) when it's installed,
 * otherwise a plain Kotlin walk; both skip the same folders and big or binary files.
 */
object CodeSearch {
    const val MAX_MATCHES = 2_000
    const val MAX_PER_FILE = 100
    const val MAX_FILE_BYTES = 1L shl 20
    const val CONTEXT = 1
    /** Long (minified) lines are cut to about this many characters around the first match. */
    const val MAX_LINE_CHARS = 240

    /** Folders that are tools' output, dependencies or caches, never worth searching. */
    val IGNORED_DIRS = setOf(
        ".git", ".hg", ".svn", "node_modules", "bower_components", "build", "dist", "target", ".gradle",
        ".venv", "venv", "__pycache__", ".mypy_cache", ".pytest_cache", ".tox", ".next", ".nuxt", ".svelte-kit",
        ".turbo", ".parcel-cache", ".cache", ".dart_tool", ".idea", "Pods", "coverage", ".expo", ".angular",
    )

    /** Generated files that are large and rarely what anyone is looking for. */
    val IGNORED_FILES = listOf("*.min.js", "*.min.css", "*.map", "package-lock.json", "yarn.lock", "pnpm-lock.yaml")

    fun isIgnoredDir(name: String): Boolean = name in IGNORED_DIRS

    fun isIgnoredFile(name: String): Boolean = IGNORED_FILES.any { Glob.matches(it, name) }

    /** "*.kt, *.kts !*Test.kt" → ["*.kt", "*.kts", "!*Test.kt"]. */
    fun globs(text: String): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        var depth = 0
        // Commas inside {a,b} belong to the glob.
        for (c in text) {
            when {
                c == '{' -> { depth++; cur.append(c) }
                c == '}' -> { depth = maxOf(0, depth - 1); cur.append(c) }
                depth == 0 && (c == ',' || c == ';' || c.isWhitespace()) -> { out += cur.toString(); cur.clear() }
                else -> cur.append(c)
            }
        }
        out += cur.toString()
        return out.map { it.trim() }.filter { it.isNotEmpty() && it != "!" }
    }

    /** Whether [relPath] (inside a project) passes the user's file filter. */
    fun passesGlobs(globs: List<String>, relPath: String): Boolean {
        val include = globs.filterNot { it.startsWith("!") }
        val exclude = globs.filter { it.startsWith("!") }.map { it.substring(1) }
        fun hit(g: String) = if ('/' in g) Glob.matches(g.removePrefix("/"), relPath) || Glob.matches("**/" + g.removePrefix("/"), relPath)
            else Glob.matches(g, relPath.substringAfterLast('/'))
        if (exclude.any(::hit)) return false
        return include.isEmpty() || include.any(::hit)
    }

    /** ripgrep's arguments; run from ~/projects with [roots] as project folder names. */
    fun rgArgs(rg: String, q: CodeQuery, roots: List<String>): List<String> = buildList {
        add(rg)
        addAll(listOf("--json", "--no-config", "--hidden", "--max-filesize", "1M", "--max-count", "$MAX_PER_FILE", "--context", "$CONTEXT"))
        add(if (q.caseSensitive) "--case-sensitive" else "--ignore-case")
        if (!q.regex) add("--fixed-strings")
        for (d in IGNORED_DIRS) { add("--glob"); add("!$d") }
        for (f in IGNORED_FILES) { add("--glob"); add("!$f") }
        for (g in globs(q.glob)) { add("--glob"); add(g) }
        // -e and --: a query or folder starting with "-" is never read as an option.
        addAll(listOf("-e", q.text, "--"))
        addAll(roots)
    }

    /** Splits "project/dir/file.kt" (as rg prints it from ~/projects) into project and path. */
    fun splitPath(raw: String): Pair<String, String>? {
        val p = raw.replace('\\', '/').removePrefix("./")
        val slash = p.indexOf('/')
        if (slash <= 0 || slash == p.lastIndex) return null
        return p.substring(0, slash) to p.substring(slash + 1)
    }

    /** Groups matches by project, then file, in the order they were found. */
    fun group(matches: List<CodeMatch>): List<CodeProjectGroup> =
        matches.groupBy { it.project }.map { (project, ms) ->
            CodeProjectGroup(project, ms.groupBy { it.path }.map { (path, fm) -> CodeFileGroup(path, fm) })
        }

    /**
     * Cuts a long line down to about [max] characters around its first match, moving the ranges
     * along; "…" marks what was cut.
     */
    fun clip(text: String, ranges: List<IntRange>, max: Int = MAX_LINE_CHARS): Pair<String, List<IntRange>> {
        val t = text.trimEnd('\n', '\r')
        if (t.length <= max) return t to ranges.filter { it.first < t.length }.map { it.first..minOf(it.last, t.length - 1) }
        val first = ranges.firstOrNull()?.first ?: 0
        val start = (first - max / 4).coerceIn(0, t.length - 1)
        val end = minOf(t.length, start + max)
        val head = if (start > 0) "…" else ""
        val shift = head.length - start
        val body = head + t.substring(start, end) + if (end < t.length) "…" else ""
        val moved = ranges.mapNotNull { r ->
            val a = maxOf(r.first, start); val b = minOf(r.last, end - 1)
            if (a > b) null else (a + shift)..(b + shift)
        }
        return body to moved
    }
}

/** Shell-style globs: `*` and `?` stay within a path segment, `**` crosses them, `{a,b}` is either. */
object Glob {
    private val cache = HashMap<String, Regex>()

    fun matches(glob: String, path: String): Boolean = synchronized(cache) { cache.getOrPut(glob) { toRegex(glob) } }.matches(path)

    fun toRegex(glob: String): Regex {
        val sb = StringBuilder()
        var i = 0
        var inBraces = false
        while (i < glob.length) {
            val c = glob[i]
            when {
                c == '*' && glob.getOrNull(i + 1) == '*' -> {
                    // "**/" also matches no folder at all.
                    if (glob.getOrNull(i + 2) == '/') { sb.append("(?:.*/)?"); i += 2 } else { sb.append(".*"); i++ }
                }
                c == '*' -> sb.append("[^/]*")
                c == '?' -> sb.append("[^/]")
                c == '{' -> { inBraces = true; sb.append("(?:") }
                c == '}' && inBraces -> { inBraces = false; sb.append(')') }
                c == ',' && inBraces -> sb.append('|')
                else -> sb.append(Regex.escape(c.toString()))
            }
            i++
        }
        return Regex(sb.toString())
    }
}

/** Finds the matches of a [CodeQuery] in one line (the Kotlin fallback; rg does its own). */
class CodeMatcher(q: CodeQuery) {
    private val plain = q.text.takeUnless { q.regex }
    private val ignoreCase = !q.caseSensitive
    /** Throws [java.util.regex.PatternSyntaxException] for a bad expression. */
    private val regex = if (q.regex) Regex(q.text, if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet()) else null

    fun find(line: String): List<IntRange> {
        val out = ArrayList<IntRange>()
        if (plain != null) {
            if (plain.isEmpty()) return out
            var i = line.indexOf(plain, ignoreCase = ignoreCase)
            while (i >= 0) {
                out += i until i + plain.length
                i = line.indexOf(plain, i + plain.length, ignoreCase = ignoreCase)
            }
        } else if (regex != null) {
            // Empty matches (like "x*" on most lines) aren't something to show.
            for (m in regex.findAll(line)) if (!m.range.isEmpty()) out += m.range
        }
        return out
    }
}

/**
 * Turns ripgrep's `--json` stream, line by line, into [CodeMatch]es with their context. A match is
 * handed out once its "after" lines have arrived (or the file ended), so results can stream.
 */
class RgJsonParser(private val context: Int = CodeSearch.CONTEXT) {
    private var pending: CodeMatch? = null
    private val before = ArrayDeque<CodeLine>()
    /** Lines that weren't rg's JSON: its error messages (stderr is merged in). */
    val errors = ArrayList<String>()

    fun feed(line: String): List<CodeMatch> {
        if (line.isBlank()) return emptyList()
        val obj = runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull()
        if (obj == null) {
            if (errors.size < 20) errors += line.trim()
            return emptyList()
        }
        val data = obj["data"] as? JsonObject
        return when (obj["type"]?.jsonPrimitive?.content) {
            "begin", "end" -> { before.clear(); flush() }
            "match" -> {
                val out = flush()
                parseMatch(data)?.let { pending = it.copy(before = before.toList()) }
                before.clear()
                out
            }
            "context" -> {
                val text = data?.let(::lineText) ?: return emptyList()
                val n = data["line_number"]?.jsonPrimitive?.intOrNull ?: return emptyList()
                val cl = CodeLine(n, CodeSearch.clip(text, emptyList()).first)
                val p = pending
                if (p != null && p.after.size < context && n > p.line) {
                    pending = p.copy(after = p.after + cl)
                    // The same line can be both after one match and before the next.
                    pushBefore(cl)
                    if (pending!!.after.size >= context) flush() else emptyList()
                } else {
                    pushBefore(cl)
                    emptyList()
                }
            }
            else -> flush()
        }
    }

    /** Whatever is still waiting at the end of the stream. */
    fun finish(): List<CodeMatch> = flush()

    private fun pushBefore(l: CodeLine) {
        before.addLast(l)
        while (before.size > context) before.removeFirst()
    }

    private fun flush(): List<CodeMatch> {
        val p = pending ?: return emptyList()
        pending = null
        return listOf(p)
    }

    private fun parseMatch(data: JsonObject?): CodeMatch? {
        if (data == null) return null
        // Paths or lines that aren't UTF-8 come as base64 "bytes": skip them.
        val raw = (data["path"] as? JsonObject)?.get("text")?.jsonPrimitive?.content ?: return null
        val (project, path) = CodeSearch.splitPath(raw) ?: return null
        val text = lineText(data) ?: return null
        val n = data["line_number"]?.jsonPrimitive?.intOrNull ?: return null
        val subs = (data["submatches"] as? JsonArray).orEmpty().mapNotNull { s ->
            val o = s as? JsonObject ?: return@mapNotNull null
            val start = o["start"]?.jsonPrimitive?.int ?: return@mapNotNull null
            val end = o["end"]?.jsonPrimitive?.int ?: return@mapNotNull null
            byteRangeToChars(text, start, end)
        }
        val (shown, ranges) = CodeSearch.clip(text, subs)
        return CodeMatch(project, path, n, shown, ranges)
    }

    private fun lineText(data: JsonObject): String? = (data["lines"] as? JsonObject)?.get("text")?.jsonPrimitive?.content

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** rg reports submatches in UTF-8 byte offsets; the UI needs character offsets. */
        fun byteRangeToChars(text: String, start: Int, end: Int): IntRange? {
            val bytes = text.toByteArray(Charsets.UTF_8)
            if (start < 0 || end <= start || end > bytes.size) return null
            val a = String(bytes, 0, start, Charsets.UTF_8).length
            val b = String(bytes, 0, end, Charsets.UTF_8).length
            return if (b > a) a until b else null
        }
    }
}

/** The fallback when ripgrep isn't installed: walks project folders with plain file reads. */
object CodeWalker {
    /**
     * Searches [projects] (folders directly under ~/projects), calling [onMatch] for each match
     * until it returns false, [isActive] turns false, or [limit] matches were found.
     */
    fun search(
        projects: List<File>,
        q: CodeQuery,
        limit: Int = CodeSearch.MAX_MATCHES,
        isActive: () -> Boolean = { true },
        onMatch: (CodeMatch) -> Boolean,
    ) {
        val matcher = CodeMatcher(q)
        val globs = CodeSearch.globs(q.glob)
        var count = 0
        for (project in projects) {
            val stack = ArrayDeque<Pair<File, String>>()
            stack.addLast(project to "")
            while (stack.isNotEmpty()) {
                if (!isActive() || count >= limit) return
                val (dir, rel) = stack.removeLast()
                val children = dir.listFiles()?.sortedBy { it.name } ?: continue
                // Files first, then folders, both in name order (folders pushed in reverse for the stack).
                for (f in children) {
                    if (!f.isFile) continue
                    val relPath = if (rel.isEmpty()) f.name else "$rel/${f.name}"
                    if (CodeSearch.isIgnoredFile(f.name) || !CodeSearch.passesGlobs(globs, relPath)) continue
                    if (f.length() > CodeSearch.MAX_FILE_BYTES || isLink(f) || looksBinary(f)) continue
                    for (m in searchFile(f, project.name, relPath, matcher)) {
                        if (!isActive() || count >= limit) return
                        count++
                        if (!onMatch(m)) return
                    }
                }
                for (f in children.asReversed()) {
                    if (f.isDirectory && !CodeSearch.isIgnoredDir(f.name) && !isLink(f)) {
                        stack.addLast(f to if (rel.isEmpty()) f.name else "$rel/${f.name}")
                    }
                }
            }
        }
    }

    fun searchFile(file: File, project: String, relPath: String, matcher: CodeMatcher, context: Int = CodeSearch.CONTEXT): List<CodeMatch> {
        val lines = runCatching { file.readLines(Charsets.UTF_8) }.getOrNull() ?: return emptyList()
        val out = ArrayList<CodeMatch>()
        for ((i, line) in lines.withIndex()) {
            val ranges = matcher.find(line)
            if (ranges.isEmpty()) continue
            val (shown, clipped) = CodeSearch.clip(line, ranges)
            fun ctx(r: IntRange) = r.filter { it in lines.indices && matcher.find(lines[it]).isEmpty() }
                .map { CodeLine(it + 1, CodeSearch.clip(lines[it], emptyList()).first) }
            out += CodeMatch(project, relPath, i + 1, shown, clipped, ctx(i - context until i), ctx(i + 1..i + context))
            if (out.size >= CodeSearch.MAX_PER_FILE) break
        }
        return out
    }

    /** A NUL byte in the first 8 KB, like git and ripgrep decide. */
    fun looksBinary(file: File): Boolean = runCatching {
        file.inputStream().use { s ->
            val buf = ByteArray(8 * 1024)
            val n = s.read(buf)
            (0 until maxOf(n, 0)).any { buf[it] == 0.toByte() }
        }
    }.getOrDefault(true)

    /** Symlinks aren't followed: one pointing up the tree would never end. */
    private fun isLink(f: File): Boolean = runCatching {
        val parent = f.absoluteFile.parentFile?.canonicalFile ?: return@runCatching false
        File(parent, f.name).canonicalPath != File(parent, f.name).absolutePath
    }.getOrDefault(true)
}
