package dev.yurlungr.app.projects

import dev.yurlungr.app.projects.Git.Companion.shellQuote
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/** The package managers whose outdated dependencies can be listed and updated. */
enum class DepTool(val label: String) {
    Npm("npm"), Pnpm("pnpm"), Yarn("Yarn"), Bun("Bun"), Pip("pip"), Uv("uv"), Poetry("Poetry"), Go("Go modules"), Cargo("Cargo");
}

/** How big a jump an update is. 0.x minors count as major: they're breaking under semver's caret rules. */
enum class Bump { Patch, Minor, Major, Unknown }

/** A dependency with a newer version. */
data class OutdatedDep(
    val name: String,
    /** Installed (or locked) version; null when it isn't installed. */
    val current: String?,
    /** The newest version the project's ranges allow, when the tool says (npm's wanted, Cargo's compat). */
    val wanted: String?,
    val latest: String,
    /** "dev", "build", "optional", "peer" or "indirect"; null for a normal dependency. */
    val kind: String? = null,
) {
    val bump: Bump get() = DepVersions.bump(current ?: wanted, latest)

    /** The newest non-major version: latest when it isn't a major jump, else wanted when that's newer; null when none. */
    val safeTarget: String?
        get() {
            val from = current ?: return null
            if (DepVersions.bump(from, latest).let { it == Bump.Patch || it == Bump.Minor }) return latest
            val w = wanted ?: return null
            return w.takeIf { it != from && DepVersions.bump(from, it).let { b -> b == Bump.Patch || b == Bump.Minor } }
        }

    /** What "all safe" picks: indirect (transitive) modules only move with what needs them. */
    val autoSafe: Boolean get() = safeTarget != null && kind != "indirect"
}

/** One package manager's outdated list, for one folder. [note]: why there's no list, or a caveat. */
data class DepGroup(val tool: DepTool, val dir: File, val deps: List<OutdatedDep>, val note: String? = null, val failed: Boolean = false)

/** A dependency check of a whole project. */
data class DepReport(val checkedAt: Long, val groups: List<DepGroup>) {
    val outdated: Int get() = groups.sumOf { it.deps.size }
    val majors: Int get() = groups.sumOf { g -> g.deps.count { it.bump == Bump.Major } }
}

/** A dependency to move to [version]. */
data class DepUpdate(val dep: OutdatedDep, val version: String)

object DepVersions {
    private val NUM = Regex("^\\s*[v=^~]*\\s*(\\d+)(?:\\.(\\d+))?(?:\\.(\\d+))?")

    /** Major, minor, patch of a version ("v1.2.3", "^1.2", "2023.7.22", "1.0.0-beta.1"); null when it doesn't start with a number. */
    fun parts(v: String?): List<Int>? {
        val m = v?.let { NUM.find(it) } ?: return null
        return m.groupValues.drop(1).map { it.toIntOrNull() ?: 0 }
    }

    fun bump(from: String?, to: String?): Bump {
        val a = parts(from) ?: return Bump.Unknown
        val b = parts(to) ?: return Bump.Unknown
        val cmp = compare(a, b)
        // Not newer (a downgrade, or only a pre-release tag changed): nothing to call safe.
        if (cmp > 0) return Bump.Unknown
        if (cmp == 0) return if (a[0] >= 1 && from != to) Bump.Patch else Bump.Unknown
        return when {
            a[0] != b[0] -> Bump.Major
            a[0] == 0 && a[1] != b[1] -> Bump.Major
            a[1] != b[1] -> Bump.Minor
            else -> Bump.Patch
        }
    }

    private fun compare(a: List<Int>, b: List<Int>): Int {
        for (i in 0..2) if (a[i] != b[i]) return a[i].compareTo(b[i])
        return 0
    }

    /** A real version ("---", "Removed", "" and the like aren't). */
    fun isVersion(v: String?): Boolean = parts(v) != null
}

/**
 * Listing outdated dependencies with each package manager's machine-readable output, and the
 * commands that update them. The parsers are defensive: output differs between tool versions, so
 * anything unexpected is skipped rather than guessed at.
 */
object DependencyUpdates {
    private val json = Json { ignoreUnknownKeys = true }

    // ---- Which tools ----

    /** The package managers to ask for a project, from its detected stacks, with their folders. */
    fun toolsFor(stacks: List<DetectedStack>): List<Pair<DepTool, File>> = stacks.mapNotNull { s ->
        val manager = Regex("\\(([a-z]+)\\)").find(s.name)?.groupValues?.get(1)
        val tool = when (s.id) {
            "node" -> when (manager) { "pnpm" -> DepTool.Pnpm; "yarn" -> DepTool.Yarn; "bun" -> DepTool.Bun; else -> DepTool.Npm }
            "python" -> when (manager) { "uv" -> DepTool.Uv; "poetry" -> DepTool.Poetry; else -> DepTool.Pip }
            "go" -> DepTool.Go
            "rust" -> DepTool.Cargo
            else -> null
        }
        tool?.let { it to s.dir }
    }.distinct()

    /** Yarn 2+ (Berry) has no `outdated`: told by .yarnrc.yml or packageManager. */
    fun isYarnBerry(dir: File): Boolean {
        if (File(dir, ".yarnrc.yml").isFile) return true
        val text = runCatching { File(dir, "package.json").takeIf { it.length() < 2_000_000 }?.readText() }.getOrNull() ?: return false
        val major = Regex("\"packageManager\"\\s*:\\s*\"yarn@(\\d+)").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: return false
        return major >= 2
    }

    /** Marker printed by [listCommand] when cargo-outdated isn't installed. */
    const val NO_CARGO_OUTDATED = "__yurlungr_no_cargo_outdated__"

    /** The shell command that lists outdated dependencies (run in the stack's folder); fixed text, nothing from the project. */
    fun listCommand(tool: DepTool): String = when (tool) {
        DepTool.Npm -> "npm outdated --json"
        DepTool.Pnpm -> "pnpm outdated --format json"
        DepTool.Yarn -> "yarn outdated --json"
        DepTool.Bun -> "NO_COLOR=1 bun outdated"
        DepTool.Pip -> ".venv/bin/python -m pip list --outdated --not-required --format=json --disable-pip-version-check"
        DepTool.Uv -> "uv pip list --outdated --format json"
        DepTool.Poetry -> "P=.venv/bin/poetry; [ -x \"\$P\" ] || P=poetry; POETRY_VIRTUALENVS_IN_PROJECT=true \"\$P\" show --outdated --top-level --no-ansi"
        DepTool.Go -> "go list -m -u -json all"
        DepTool.Cargo -> "if cargo outdated --version >/dev/null 2>&1; then cargo outdated --format json --root-deps-only; else echo $NO_CARGO_OUTDATED; fi"
    }

    /** Why [tool] can't be asked in [dir] yet (nothing installed to compare), or null. */
    fun notReady(tool: DepTool, dir: File): String? = when (tool) {
        DepTool.Pip, DepTool.Uv, DepTool.Poetry ->
            if (!File(dir, ".venv/bin/python").exists()) "No .venv yet: install the dependencies first (Setup), then refresh." else null
        DepTool.Yarn -> if (isYarnBerry(dir)) "Yarn 2+ has no outdated list. Run `yarn upgrade-interactive` in a terminal, or ask the agent." else null
        else -> null
    }

    /** The parsed list from a tool's output, or null when the output isn't what that tool prints. */
    fun parse(tool: DepTool, output: String): List<OutdatedDep>? = when (tool) {
        DepTool.Npm -> parseNpm(output)
        DepTool.Pnpm -> parsePnpm(output)
        DepTool.Yarn -> parseYarnClassic(output)
        DepTool.Bun -> parseBunTable(output)
        DepTool.Pip, DepTool.Uv -> parsePip(output)?.filterNot { tool == DepTool.Pip && it.name.equals("pip", ignoreCase = true) }
        DepTool.Poetry -> parsePoetry(output)
        DepTool.Go -> parseGoList(output)
        DepTool.Cargo -> parseCargoOutdated(output)
    }

    // ---- Parsers ----

    private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.trim()?.takeIf { it.isNotEmpty() }

    private fun parseJson(text: String): JsonElement? = runCatching { json.parseToJsonElement(text.trim()) }.getOrNull()

    private fun nodeKind(type: String?): String? = when (type?.lowercase()) {
        "devdependencies", "dev" -> "dev"
        "optionaldependencies", "optional" -> "optional"
        "peerdependencies", "peer" -> "peer"
        else -> null
    }

    /** An "error" object some tools print instead of the list (npm's `{"error": {"summary": …}}`). */
    fun errorOf(output: String): String? {
        val o = parseJson(output) as? JsonObject ?: return null
        val e = o["error"] ?: return null
        return (e as? JsonObject)?.let { it["summary"].str() ?: it["message"].str() ?: it["code"].str() } ?: e.str()
    }

    /**
     * `npm outdated --json`: `{name: {current, wanted, latest, type, …}}`; with workspaces a value
     * can be an array (one per dependent). Empty output means nothing is outdated.
     */
    fun parseNpm(output: String): List<OutdatedDep>? {
        if (output.isBlank()) return emptyList()
        val root = parseJson(output) as? JsonObject ?: return null
        if (root["error"] is JsonObject) return null
        return root.entries.flatMap { (name, v) ->
            val entries = when (v) { is JsonArray -> v.mapNotNull { it as? JsonObject }; is JsonObject -> listOf(v); else -> emptyList() }
            entries.mapNotNull { o ->
                val latest = o["latest"].str()?.takeIf(DepVersions::isVersion) ?: return@mapNotNull null
                OutdatedDep(name, o["current"].str()?.takeIf(DepVersions::isVersion), o["wanted"].str()?.takeIf(DepVersions::isVersion), latest,
                    nodeKind(o["type"].str() ?: o["dependencyType"].str()))
            }.distinctBy { it.current to it.latest }
        }
    }

    /**
     * `pnpm outdated --format json`: `{name: {current, latest, wanted, dependencyType}}` (pnpm 8+);
     * some versions print an array of `{packageName, current, latest, wanted}`.
     */
    fun parsePnpm(output: String): List<OutdatedDep>? {
        if (output.isBlank()) return emptyList()
        fun dep(name: String?, o: JsonObject): OutdatedDep? {
            val n = name ?: return null
            val latest = (o["latest"].str() ?: (o["latestManifest"] as? JsonObject)?.get("version").str())?.takeIf(DepVersions::isVersion) ?: return null
            val current = o["current"].str()?.takeIf(DepVersions::isVersion)
            if (current == latest) return null
            return OutdatedDep(n, current, o["wanted"].str()?.takeIf(DepVersions::isVersion), latest, nodeKind(o["dependencyType"].str() ?: o["type"].str()))
        }
        return when (val root = parseJson(output)) {
            is JsonObject -> if (root["error"] != null) null else root.entries.mapNotNull { (name, v) -> (v as? JsonObject)?.let { dep(name, it) } }
            is JsonArray -> root.mapNotNull { (it as? JsonObject)?.let { o -> dep(o["packageName"].str() ?: o["name"].str(), o) } }
            else -> null
        }
    }

    /** `yarn outdated --json` (Yarn 1): JSON lines, one of them `{"type":"table","data":{"head":[…],"body":[[…]]}}`. */
    fun parseYarnClassic(output: String): List<OutdatedDep>? {
        if (output.isBlank()) return emptyList()
        var sawJson = false
        for (line in output.lineSequence()) {
            val o = parseJson(line) as? JsonObject ?: continue
            sawJson = true
            if (o["type"].str() != "table") continue
            val data = o["data"] as? JsonObject ?: continue
            val head = (data["head"] as? JsonArray)?.map { it.str()?.lowercase().orEmpty() } ?: continue
            fun col(vararg names: String) = head.indexOfFirst { it in names }
            val iName = col("package"); val iCurrent = col("current"); val iWanted = col("wanted"); val iLatest = col("latest"); val iType = col("package type", "type")
            if (iName < 0 || iLatest < 0) return null
            return (data["body"] as? JsonArray).orEmpty().mapNotNull { row ->
                val cells = (row as? JsonArray)?.map { it.str() } ?: return@mapNotNull null
                fun cell(i: Int) = cells.getOrNull(i)
                val latest = cell(iLatest)?.takeIf(DepVersions::isVersion) ?: return@mapNotNull null
                OutdatedDep(cell(iName) ?: return@mapNotNull null, cell(iCurrent)?.takeIf(DepVersions::isVersion),
                    cell(iWanted)?.takeIf(DepVersions::isVersion), latest, nodeKind(cell(iType)))
            }
        }
        // Only info lines (or nothing): everything is up to date.
        return if (sawJson) emptyList() else null
    }

    /**
     * `bun outdated` has no JSON: a box table (`│ Package │ Current │ Update │ Latest │`), dev
     * dependencies marked "(dev)". ASCII `|` tables are read too.
     */
    fun parseBunTable(output: String): List<OutdatedDep>? {
        val ansi = Regex("\u001B\\[[0-9;?]*[A-Za-z]")
        val rows = output.lineSequence().map { ansi.replace(it, "").trim() }
            .filter { it.startsWith("│") || it.startsWith("|") }
            .map { line -> line.trim('│', '|').split('│', '|').map { it.trim() } }
            .toList()
        // No table: bun prints only its version line when nothing is outdated (errors show in the exit code).
        if (rows.isEmpty()) return emptyList()
        val head = rows.first().map { it.lowercase() }
        val iName = head.indexOfFirst { it.startsWith("package") }
        val iCurrent = head.indexOf("current")
        val iWanted = head.indexOfFirst { it == "update" || it == "wanted" }
        val iLatest = head.indexOf("latest")
        if (iName < 0 || iLatest < 0) return null
        return rows.drop(1).mapNotNull { cells ->
            val rawName = cells.getOrNull(iName)?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val kind = Regex("\\((dev|peer|optional)\\)\\s*$").find(rawName)?.groupValues?.get(1)
            val name = rawName.replace(Regex("\\s*\\((dev|peer|optional)\\)\\s*$"), "")
            val latest = cells.getOrNull(iLatest)?.takeIf(DepVersions::isVersion) ?: return@mapNotNull null
            OutdatedDep(name, cells.getOrNull(iCurrent)?.takeIf(DepVersions::isVersion), cells.getOrNull(iWanted)?.takeIf(DepVersions::isVersion), latest, kind)
        }
    }

    /** `pip list --outdated --format=json` (and `uv pip list`): `[{name, version, latest_version}]`. */
    fun parsePip(output: String): List<OutdatedDep>? {
        if (output.isBlank()) return emptyList()
        // pip may print a notice after the JSON; read from the first '[' to its line's end.
        val start = output.indexOf('[').takeIf { it >= 0 } ?: return null
        val root = parseJson(output.substring(start).lineSequence().first()) as? JsonArray ?: parseJson(output.substring(start)) as? JsonArray ?: return null
        return root.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val name = o["name"].str() ?: return@mapNotNull null
            val latest = (o["latest_version"].str() ?: o["latest"].str())?.takeIf(DepVersions::isVersion) ?: return@mapNotNull null
            OutdatedDep(name, o["version"].str(), null, latest)
        }
    }

    /**
     * `poetry show --outdated --top-level --no-ansi`: `name [(!)] current latest description…`.
     * Lines that don't look like that (warnings, headers) are skipped.
     */
    fun parsePoetry(output: String): List<OutdatedDep> = output.lineSequence().mapNotNull { line ->
        val tokens = line.trim().split(Regex("\\s+")).filter { it != "(!)" }
        if (tokens.size < 3) return@mapNotNull null
        val (name, current, latest) = tokens
        if (!Regex("^[A-Za-z0-9][A-Za-z0-9._-]*$").matches(name) || !Regex("^\\d[\\w.+!-]*$").matches(current) || !Regex("^\\d[\\w.+!-]*$").matches(latest)) return@mapNotNull null
        if (current == latest) return@mapNotNull null
        OutdatedDep(name, current, null, latest)
    }.toList()

    /** Top-level `{…}` objects in [text], one after another (Go and cargo-outdated print several, not an array). */
    fun splitJsonObjects(text: String): List<String> {
        val out = ArrayList<String>()
        var depth = 0
        var start = -1
        var inString = false
        var escaped = false
        for (i in text.indices) {
            val c = text[i]
            if (inString) {
                when { escaped -> escaped = false; c == '\\' -> escaped = true; c == '"' -> inString = false }
                continue
            }
            when (c) {
                '"' -> if (depth > 0) inString = true
                '{' -> { if (depth == 0) start = i; depth++ }
                '}' -> if (depth > 0) { depth--; if (depth == 0 && start >= 0) { out += text.substring(start, i + 1); start = -1 } }
            }
        }
        return out
    }

    /** `go list -m -u -json all`: one object per module; those with `Update` are outdated. The main module is skipped. */
    fun parseGoList(output: String): List<OutdatedDep>? {
        if (output.isBlank()) return emptyList()
        val objects = splitJsonObjects(output).mapNotNull { parseJson(it) as? JsonObject }
        if (objects.isEmpty()) return null
        return objects.mapNotNull { o ->
            if (o["Main"].str() == "true") return@mapNotNull null
            val update = (o["Update"] as? JsonObject)?.get("Version").str() ?: return@mapNotNull null
            val path = o["Path"].str() ?: return@mapNotNull null
            OutdatedDep(path, o["Version"].str(), null, update, if (o["Indirect"].str() == "true") "indirect" else null)
        }
    }

    /**
     * `cargo outdated --format json`: per crate, `{"crate_name", "dependencies": [{name, project, compat, latest, kind}]}`;
     * "---" (or "Removed") stands for no version.
     */
    fun parseCargoOutdated(output: String): List<OutdatedDep>? {
        if (output.isBlank()) return emptyList()
        val objects = splitJsonObjects(output).mapNotNull { parseJson(it) as? JsonObject }.filter { it["dependencies"] is JsonArray }
        if (objects.isEmpty()) return null
        return objects.flatMap { o ->
            (o["dependencies"] as JsonArray).mapNotNull { d ->
                val dep = d as? JsonObject ?: return@mapNotNull null
                val name = dep["name"].str() ?: return@mapNotNull null
                val project = dep["project"].str()?.takeIf(DepVersions::isVersion)
                val compat = dep["compat"].str()?.takeIf(DepVersions::isVersion)?.takeIf { it != project }
                val latest = dep["latest"].str()?.takeIf(DepVersions::isVersion)?.takeIf { it != project } ?: compat ?: return@mapNotNull null
                val kind = when (dep["kind"].str()?.lowercase()) { "development", "dev" -> "dev"; "build" -> "build"; else -> null }
                OutdatedDep(name, project, compat, latest, kind)
            }
        }.distinctBy { Triple(it.name, it.current, it.latest) }
    }

    // ---- Updating ----

    private val NAME = Regex("^[A-Za-z0-9@_][A-Za-z0-9@/._+~-]*$")
    private val VERSION = Regex("^v?\\d[A-Za-z0-9._+~-]*$")

    /** Names and versions that are safe to pass on (quoted all the same); anything else is left out. */
    fun valid(u: DepUpdate): Boolean = NAME.matches(u.dep.name) && VERSION.matches(u.version)

    /** The command that moves [updates] to their versions, run in the group's folder; null when none are valid. */
    fun updateCommand(tool: DepTool, updates: List<DepUpdate>): String? {
        val list = updates.filter(::valid)
        if (list.isEmpty()) return null
        fun spec(u: DepUpdate, sep: String = "@") = shellQuote(u.dep.name + sep + u.version)
        fun split(prod: (List<DepUpdate>) -> String?, dev: (List<DepUpdate>) -> String?): String {
            val (d, p) = list.partition { it.dep.kind == "dev" }
            return listOfNotNull(p.takeIf { it.isNotEmpty() }?.let(prod), d.takeIf { it.isNotEmpty() }?.let(dev)).joinToString(" && ")
        }
        return when (tool) {
            DepTool.Npm -> split({ "npm install " + it.joinToString(" ") { u -> spec(u) } }, { "npm install --save-dev " + it.joinToString(" ") { u -> spec(u) } })
            // update (not add) keeps each one in the section it's already in.
            DepTool.Pnpm -> "pnpm update " + list.joinToString(" ") { spec(it) }
            DepTool.Yarn -> "yarn upgrade " + list.joinToString(" ") { spec(it) }
            DepTool.Bun -> split({ "bun add " + it.joinToString(" ") { u -> spec(u) } }, { "bun add --dev " + it.joinToString(" ") { u -> spec(u) } })
            DepTool.Pip -> ".venv/bin/python -m pip install --upgrade " + list.joinToString(" ") { spec(it, "==") }
            // To the newest version pyproject.toml's ranges allow.
            DepTool.Uv -> "uv lock " + list.joinToString(" ") { "--upgrade-package " + shellQuote(it.dep.name) } + " && uv sync"
            // Poetry moves within pyproject.toml's ranges; a range change is the project's call.
            DepTool.Poetry -> "P=.venv/bin/poetry; [ -x \"\$P\" ] || P=poetry; POETRY_VIRTUALENVS_IN_PROJECT=true \"\$P\" update " +
                list.joinToString(" ") { shellQuote(it.dep.name) }
            DepTool.Go -> "go get " + list.joinToString(" ") { spec(it) } + " && go mod tidy"
            DepTool.Cargo -> {
                // Within Cargo.toml's ranges `cargo update` is enough; past them, `cargo add` changes the requirement.
                val (inRange, past) = list.partition { it.version == it.dep.wanted }
                val update = inRange.takeIf { it.isNotEmpty() }?.let { l -> "cargo update " + l.joinToString(" ") { "-p " + shellQuote(it.dep.name) } }
                val add = past.map { u -> "cargo add " + when (u.dep.kind) { "dev" -> "--dev "; "build" -> "--build "; else -> "" } + spec(u) }
                (listOfNotNull(update) + add).joinToString(" && ")
            }
        }
    }

    /** A caveat shown under a tool's list, when updating with it has one. */
    fun caveat(tool: DepTool): String? = when (tool) {
        DepTool.Pip -> "Installs into .venv; requirements files aren't changed, so update their pins too (or ask the agent)."
        DepTool.Poetry -> "Poetry updates within pyproject.toml's ranges; a major update needs the range changed there."
        DepTool.Uv -> "uv updates within pyproject.toml's ranges; a major update needs the range changed there."
        DepTool.Go -> "Indirect modules are listed too; \"All safe\" leaves them to what needs them."
        else -> null
    }

    /** What's done, in a line per dependency ("react 18.2.0 → 18.3.1"), for the agent and the result card. */
    fun describe(tool: DepTool, updates: List<DepUpdate>): String =
        updates.joinToString("\n") { u -> "- ${u.dep.name} ${u.dep.current ?: "?"} → ${u.version} (${tool.label}${if (u.dep.bump == Bump.Major && u.version == u.dep.latest) ", major" else ""})" }

    const val LOG = "deps-update.log"
    const val EXIT = "deps-update.exit"
    const val CHECKED = "deps-checks.done"
    const val RECORD = "deps-update.json"

    /**
     * One terminal command: each update in its folder (all output kept in `.yurlungr/deps-update.log`,
     * the exit code beside it), then, only if they worked, [checks] (the project's own check
     * command), with a marker once those are done so the result can be read.
     */
    fun terminalCommand(root: File, steps: List<Pair<File, String>>, checks: String?): String {
        val y = File(root, ProjectChecks.DIR)
        val log = shellQuote(File(y, LOG).path)
        val exit = shellQuote(File(y, EXIT).path)
        val done = shellQuote(File(y, CHECKED).path)
        val updates = steps.joinToString(" && ") { (dir, command) -> "(cd ${shellQuote(dir.path)} && $command)" }
        return buildString {
            append("cd ${shellQuote(root.path)} && mkdir -p ${shellQuote(y.path)} && printf '*\\n' > ${shellQuote(File(y, ".gitignore").path)} && rm -f $exit $done && ")
            append("{ $updates; } 2>&1 | tee $log; _ec=\${PIPESTATUS[0]}; printf '%s\\n' \"\$_ec\" > $exit")
            if (checks != null) append("; if [ \"\$_ec\" = 0 ]; then $checks; : > $done; fi") else append("; [ \"\$_ec\" = 0 ]")
        }
    }

    // ---- Saved reports ----

    private fun depJson(d: OutdatedDep) = buildJsonObject {
        put("name", d.name); d.current?.let { put("current", it) }; d.wanted?.let { put("wanted", it) }; put("latest", d.latest); d.kind?.let { put("kind", it) }
    }

    private fun depOf(o: JsonObject): OutdatedDep? {
        val name = o["name"].str() ?: return null
        return OutdatedDep(name, o["current"].str(), o["wanted"].str(), o["latest"].str() ?: return null, o["kind"].str())
    }

    fun encode(report: DepReport): String = buildJsonObject {
        put("checkedAt", report.checkedAt)
        put("groups", buildJsonArray {
            for (g in report.groups) add(buildJsonObject {
                put("tool", g.tool.name); put("dir", g.dir.path); g.note?.let { put("note", it) }; put("failed", g.failed)
                put("deps", buildJsonArray { g.deps.forEach { add(depJson(it)) } })
            })
        })
    }.toString()

    fun decode(text: String): DepReport? = runCatching {
        val o = json.parseToJsonElement(text) as JsonObject
        val groups = (o["groups"] as JsonArray).mapNotNull { e ->
            val g = e as? JsonObject ?: return@mapNotNull null
            val tool = runCatching { DepTool.valueOf(g["tool"].str()!!) }.getOrNull() ?: return@mapNotNull null
            DepGroup(tool, File(g["dir"].str() ?: return@mapNotNull null), (g["deps"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(::depOf) },
                g["note"].str(), g["failed"].str() == "true")
        }
        DepReport(o["checkedAt"].str()!!.toLong(), groups)
    }.getOrNull()

    /** What an update run did, saved beside its log so the result can be read later. */
    data class Run(val startedAt: Long, val summary: String, val command: String, val checks: String?)

    fun encodeRun(r: Run): String = buildJsonObject {
        put("startedAt", r.startedAt); put("summary", r.summary); put("command", r.command)
        r.checks?.let { put("checks", it) }
    }.toString()

    fun decodeRun(text: String): Run? = runCatching {
        val o = json.parseToJsonElement(text) as JsonObject
        Run(o["startedAt"].str()!!.toLong(), o["summary"].str().orEmpty(), o["command"].str().orEmpty(), o["checks"].str())
    }.getOrNull()
}
