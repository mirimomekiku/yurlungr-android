package dev.yurlungr.app.projects

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * Version ranges as projects write them: exact (`18.17.0`), partial (`18`, `3.11`), x-ranges
 * (`18.x`, `3.11.*`), `^`, `~`, `~=`, comparisons (`>=18 <21`, `>=3.9,<3.13`), `!=`, hyphen
 * ranges and `||`. Tolerant: anything else is "don't know" (null), never a false alarm.
 */
object VersionRange {
    private val VERSION = Regex("(\\d+)(?:\\.(\\d+))?(?:\\.(\\d+))?")

    /** The first version number in [text] ("v22.11.0", "Python 3.12.7", "go1.23.2"), as major.minor.patch. */
    fun parseVersion(text: String): IntArray? {
        val m = VERSION.find(text) ?: return null
        return IntArray(3) { m.groupValues[it + 1].toIntOrNull() ?: 0 }
    }

    private fun compare(a: IntArray, b: IntArray): Int {
        for (i in 0 until 3) if (a[i] != b[i]) return a[i].compareTo(b[i])
        return 0
    }

    /** A version in a range: its numbers, and how many were given (x, * and missing parts aren't). */
    private class Partial(val parts: IntArray, val given: Int)

    private val PARTIAL = Regex("^v?(\\d+|[xX*])(?:\\.(\\d+|[xX*]))?(?:\\.(\\d+|[xX*]))?(?:[-+][0-9A-Za-z.-]+)?$")

    private fun partial(text: String): Partial? {
        val m = PARTIAL.matchEntire(text.trim()) ?: return null
        val parts = IntArray(3)
        var given = 0
        for (i in 0 until 3) {
            val p = m.groupValues[i + 1]
            val n = p.toIntOrNull() ?: break
            parts[i] = n
            given++
        }
        return Partial(parts, given)
    }

    /** The first version after every version matching [p] (for `1.2` that's `1.3.0`). */
    private fun next(p: Partial): IntArray = when (p.given) {
        0 -> intArrayOf(Int.MAX_VALUE, 0, 0)
        1 -> intArrayOf(p.parts[0] + 1, 0, 0)
        2 -> intArrayOf(p.parts[0], p.parts[1] + 1, 0)
        else -> intArrayOf(p.parts[0], p.parts[1], p.parts[2] + 1)
    }

    private fun xRange(p: Partial): (IntArray) -> Boolean = { v -> compare(v, p.parts) >= 0 && compare(v, next(p)) < 0 }

    private val COMPARATOR = Regex("^(>=|<=|==|!=|~=|>|<|=|\\^|~)?\\s*(.+)$")

    private fun comparator(token: String): ((IntArray) -> Boolean)? {
        val m = COMPARATOR.matchEntire(token.trim()) ?: return null
        val op = m.groupValues[1]
        val p = partial(m.groupValues[2]) ?: return null
        val lo = p.parts
        val max = intArrayOf(Int.MAX_VALUE, 0, 0)
        return when (op) {
            "", "=", "==" -> xRange(p)
            "!=" -> not(xRange(p))
            ">=" -> between(lo, max)
            "<" -> below(lo)
            // ">18" means past every 18.x; "<=18" includes every 18.x.
            ">" -> not(below(next(p)))
            "<=" -> below(next(p))
            // Up to the next change in the first non-zero part that was given.
            "^" -> between(lo, when {
                p.given == 0 -> max
                lo[0] > 0 || p.given == 1 -> intArrayOf(lo[0] + 1, 0, 0)
                lo[1] > 0 || p.given == 2 -> intArrayOf(0, lo[1] + 1, 0)
                else -> intArrayOf(0, 0, lo[2] + 1)
            })
            "~" -> between(lo, if (p.given <= 1) next(p) else intArrayOf(lo[0], lo[1] + 1, 0))
            // Python's compatible release: ~=3.10 is >=3.10,<4; ~=3.10.2 is >=3.10.2,<3.11.
            "~=" -> if (p.given < 2) null else between(lo, if (p.given == 2) intArrayOf(lo[0] + 1, 0, 0) else intArrayOf(lo[0], lo[1] + 1, 0))
            else -> null
        }
    }

    private fun below(upper: IntArray): (IntArray) -> Boolean = { v -> compare(v, upper) < 0 }
    private fun between(lo: IntArray, upper: IntArray): (IntArray) -> Boolean = { v -> compare(v, lo) >= 0 && compare(v, upper) < 0 }
    private fun not(f: (IntArray) -> Boolean): (IntArray) -> Boolean = { v -> !f(v) }

    /** One `||` alternative: comparators that must all hold (space or comma separated, or a hyphen range). */
    private fun conjunction(text: String): ((IntArray) -> Boolean)? {
        val t = text.trim()
        if (t.isEmpty() || t == "*" || t.equals("x", ignoreCase = true)) return { true }
        Regex("^(\\S+)\\s+-\\s+(\\S+)$").matchEntire(t)?.let { m ->
            val lo = partial(m.groupValues[1]) ?: return null
            val hi = partial(m.groupValues[2]) ?: return null
            return { v -> compare(v, lo.parts) >= 0 && (if (hi.given < 3) compare(v, next(hi)) < 0 else compare(v, hi.parts) <= 0) }
        }
        // ">= 18" is one comparator: glue an operator to the version after it.
        val tokens = t.replace(Regex("(>=|<=|==|!=|~=|>|<|=|\\^|~)\\s+"), "$1").split(Regex("[\\s,]+")).filter { it.isNotEmpty() }
        val parts = tokens.map { comparator(it) ?: return null }
        return { v -> parts.all { it(v) } }
    }

    /** Whether [version] is in [range]; null when the range (or version) isn't understood. */
    fun satisfies(range: String, version: String): Boolean? {
        val v = parseVersion(version) ?: return null
        if (range.isBlank()) return null
        val alternatives = range.split("||").map { conjunction(it) ?: return null }
        return alternatives.any { it(v) }
    }
}

/** A runtime version a project asks for, and where it says so. */
data class RuntimeRequirement(
    /** "node", "python", "go", "rust" or "php". */
    val runtime: String,
    /** A [VersionRange] range, or "nightly" for a nightly Rust toolchain. */
    val range: String,
    /** The file (and field) it came from: ".nvmrc", "package.json engines.node". */
    val source: String,
    /** As the project wrote it. */
    val wanted: String,
)

/** A project asks for a version of a runtime this phone doesn't have. */
data class VersionWarning(val requirement: RuntimeRequirement, val installed: String) {
    val name: String get() = RuntimeRequirements.NAMES[requirement.runtime] ?: requirement.runtime
    val title: String get() = "Wants $name ${requirement.wanted}, this phone has ${installed}"
}

/** Reads the runtime versions a project asks for, from the files version managers and manifests use. */
object RuntimeRequirements {
    val NAMES = mapOf("node" to "Node.js", "python" to "Python", "go" to "Go", "rust" to "Rust", "php" to "PHP")

    /** The command that prints each runtime's version (run in a shell). */
    val VERSION_COMMANDS = mapOf(
        "node" to "node --version", "python" to "python --version", "go" to "go version",
        "rust" to "rustc --version", "php" to "php --version",
    )

    /** The binary under the prefix that tells whether the runtime is installed (and changes when it's updated). */
    val BINARIES = mapOf("node" to "bin/node", "python" to "bin/python", "go" to "bin/go", "rust" to "bin/rustc", "php" to "bin/php")

    private fun text(f: File): String? = runCatching { if (f.isFile && f.length() < 1_000_000) f.readText() else null }.getOrNull()

    /** The first meaningful line of a version file. */
    private fun firstLine(text: String): String? =
        text.lineSequence().map { it.substringBefore('#').trim() }.firstOrNull { it.isNotEmpty() }

    /**
     * A version pinned in a version file ("18.17.0", "v18", "3.11.4"), widened to the part that
     * matters: Node majors, Python/PHP minors. Go and Rust pins are minimums (newer ones build
     * older code). Aliases (lts/hydrogen, latest, system, pypy3.10…) give nothing.
     */
    internal fun pin(runtime: String, text: String): String? {
        val v = text.trim().removePrefix("v")
        if (!Regex("^\\d+(\\.\\d+){0,2}$").matches(v)) return null
        val parts = v.split('.')
        return when (runtime) {
            "node" -> parts[0]
            "python", "php" -> parts.take(2).joinToString(".")
            "go", "rust" -> ">=$v"
            else -> null
        }
    }

    fun read(dir: File): List<RuntimeRequirement> = buildList {
        fun pinned(runtime: String, source: String, text: String?) {
            val wanted = text?.let(::firstLine) ?: return
            pin(runtime, wanted)?.let { add(RuntimeRequirement(runtime, it, source, wanted.removePrefix("v"))) }
        }
        pinned("node", ".nvmrc", text(File(dir, ".nvmrc")))
        pinned("node", ".node-version", text(File(dir, ".node-version")))
        // pyenv allows several versions, one per line: the first is the one used.
        pinned("python", ".python-version", text(File(dir, ".python-version")))
        text(File(dir, ".tool-versions"))?.lineSequence()?.forEach { line ->
            val words = line.substringBefore('#').trim().split(Regex("\\s+"))
            if (words.size < 2) return@forEach
            val runtime = when (words[0]) { "nodejs", "node" -> "node"; "python" -> "python"; "golang", "go" -> "go"; "rust" -> "rust"; "php" -> "php"; else -> null }
            if (runtime != null) pinned(runtime, ".tool-versions", words[1])
        }
        text(File(dir, "package.json"))?.let { text ->
            runCatching {
                val engines = (Json.parseToJsonElement(text) as JsonObject)["engines"] as? JsonObject
                engines?.get("node")?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
            }.getOrNull()?.let { add(RuntimeRequirement("node", it, "package.json engines.node", it)) }
        }
        text(File(dir, "pyproject.toml"))?.let { text ->
            val requires = Regex("(?m)^\\s*requires-python\\s*=\\s*[\"']([^\"'\\n]+)[\"']").find(text)?.groupValues?.get(1)
            // Poetry's own spelling: python = "^3.10" under [tool.poetry.dependencies].
            val poetry = text.substringAfter("[tool.poetry.dependencies]", "").substringBefore("\n[")
                .let { Regex("(?m)^\\s*python\\s*=\\s*[\"']([^\"'\\n]+)[\"']").find(it)?.groupValues?.get(1) }
            (requires ?: poetry)?.trim()?.takeIf { it.isNotEmpty() }?.let {
                add(RuntimeRequirement("python", it, if (requires != null) "pyproject.toml requires-python" else "pyproject.toml (Poetry)", it))
            }
        }
        // Since Go 1.21 the go line is the minimum; a toolchain line only suggests one (and go
        // downloads it when it can), so it isn't a requirement.
        text(File(dir, "go.mod"))?.let { text ->
            Regex("(?m)^go\\s+(\\d+(?:\\.\\d+){1,2})\\s*$").find(text)?.groupValues?.get(1)?.let {
                add(RuntimeRequirement("go", ">=$it", "go.mod", it))
            }
        }
        val rustChannel = text(File(dir, "rust-toolchain.toml"))?.let { Regex("(?m)^\\s*channel\\s*=\\s*[\"']([^\"'\\n]+)[\"']").find(it)?.groupValues?.get(1) }
            ?.let { it to "rust-toolchain.toml" }
            ?: text(File(dir, "rust-toolchain"))?.let { text ->
                // The legacy file is either a bare channel or TOML.
                (Regex("(?m)^\\s*channel\\s*=\\s*[\"']([^\"'\\n]+)[\"']").find(text)?.groupValues?.get(1) ?: firstLine(text))?.let { it to "rust-toolchain" }
            }
        rustChannel?.let { (channel, source) ->
            when {
                channel.startsWith("nightly") -> add(RuntimeRequirement("rust", "nightly", source, channel))
                else -> pin("rust", channel)?.let { add(RuntimeRequirement("rust", it, source, channel)) }
            }
        }
        text(File(dir, "composer.json"))?.let { text ->
            runCatching {
                val require = (Json.parseToJsonElement(text) as JsonObject)["require"] as? JsonObject
                require?.get("php")?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
            }.getOrNull()?.let {
                // Composer also accepts a single | for "or".
                add(RuntimeRequirement("php", it.replace(Regex("(?<!\\|)\\|(?!\\|)"), "||"), "composer.json require.php", it))
            }
        }
    }.distinctBy { it.runtime to it.range }

    /**
     * The requirements [installed] clearly doesn't meet (runtime → its version output, null when
     * not installed). Unknown syntax and missing runtimes give no warning.
     */
    fun mismatches(requirements: List<RuntimeRequirement>, installed: Map<String, String?>): List<VersionWarning> =
        requirements.mapNotNull { r ->
            val output = installed[r.runtime]?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val version = VersionRange.parseVersion(output)?.let { "${it[0]}.${it[1]}.${it[2]}" } ?: return@mapNotNull null
            val ok = if (r.range == "nightly") output.contains("nightly") else VersionRange.satisfies(r.range, output)
            if (ok == false) VersionWarning(r, version) else null
        }.distinctBy { it.requirement.runtime }

    /** What to do about [w], in a sentence or two. */
    fun advice(w: VersionWarning): String = when {
        w.requirement.range == "nightly" ->
            "Termux ships stable Rust only, so nightly-only features won't build. The agent can try to make it build on stable."
        else -> "Termux usually ships one ${w.name} version, so it can't switch. It often still works; if something breaks, " +
            "ask the agent to adapt the project to ${w.name} ${w.installed}."
    }
}
