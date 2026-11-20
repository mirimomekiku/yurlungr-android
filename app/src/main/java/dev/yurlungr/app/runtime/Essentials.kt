package dev.yurlungr.app.runtime

/**
 * The everyday tools System checks offers to install together (System checks → Install tools). Each
 * item is a few Termux packages, verified by the same [Toolchain.tools] probes System checks runs;
 * [Agents] runs the agents setup instead. Listed in display order. Optional packs follow them in the
 * same sheet: see [ToolPacks].
 */
enum class Essential(
    /** Passed to essentials-setup.sh, which looks its packages and checks up in toolchain.sh. */
    val id: String,
    val title: String,
    val blurb: String,
    /** Termux packages installed for it. Empty for [Agents], which has its own setup. */
    val packages: List<String>,
    /** [Tool.id]s that must all pass for it to count as installed (System checks shows `tool-<id>`). */
    val tools: List<String>,
) {
    Git("git", "Git", "Version control for every project", listOf("git"), listOf("git")),
    GitHubCli("gh", "GitHub CLI", "Sign in, clone your repositories, pull requests", listOf("gh"), listOf("gh")),
    // npm ships inside Termux's Node package; termux-exec fixes the #!/usr/bin/env shebangs of npm CLIs.
    Node("node", "Node.js and npm", "JavaScript runtime; npm comes with it", listOf("nodejs-lts", "termux-exec"), listOf("node", "npm")),
    Ripgrep("rg", "ripgrep", "Fast code search, used by Find code", listOf("ripgrep"), listOf("rg")),
    Agents("agents", "Coding agents", "Claude Code, Codex, Gemini CLI and Antigravity", emptyList(), emptyList()),
    Python("python", "Python", "Python 3", listOf("python"), listOf("python")),
    Pip("pip", "pip", "Python's package installer", listOf("python-pip"), listOf("pip")),
    Uv("uv", "uv", "Fast Python package and project manager", listOf("uv"), listOf("uv")),
    Sqlite("sqlite", "SQLite", "The sqlite3 shell and library", listOf("sqlite"), listOf("sqlite3")),
    ;

    /**
     * Installed along with this item, so they're selected with it and can't be left out: pip and uv
     * run on Termux's Python, and the agents setup installs the developer tools itself.
     */
    val needs: Set<Essential>
        get() = when (this) {
            Pip, Uv -> setOf(Python)
            Agents -> setOf(Git, GitHubCli, Node, Ripgrep)
            else -> emptySet()
        }

    /** The System checks ids that say whether it's installed. */
    val checkIds: List<String> get() = if (this == Agents) listOf("agents") else tools.map { "tool-$it" }

    companion object {
        /** [chosen] plus everything they need, in display order. */
        fun closure(chosen: Collection<Essential>): List<Essential> {
            val all = chosen.toMutableSet()
            chosen.forEach { all += it.needs }
            return entries.filter { it in all }
        }

        /** The selected items that need [item], so it can't be deselected while they're selected. */
        fun neededBy(item: Essential, chosen: Collection<Essential>): List<Essential> =
            entries.filter { it in chosen && item in it.needs }

        /** For toolchain.sh: each item's packages and tool ids, which essentials-setup.sh reads. */
        internal fun shellManifest(): String = buildString {
            appendLine("declare -gA YURLUNGR_ESSENTIAL_PACKAGES=(")
            entries.filter { it.packages.isNotEmpty() }.forEach { appendLine("  [${it.id}]='${it.packages.joinToString(" ")}'") }
            appendLine(")")
            appendLine("declare -gA YURLUNGR_ESSENTIAL_TOOLS=(")
            entries.filter { it.tools.isNotEmpty() }.forEach { appendLine("  [${it.id}]='${it.tools.joinToString(" ")}'") }
            appendLine(")")
            // Every essential tool's check, "id<TAB>command" like YURLUNGR_CHECKS_<SETUP>; the script picks the chosen ones.
            appendLine("YURLUNGR_CHECKS_ESSENTIALS=(")
            val ids = entries.flatMap { it.tools }.toSet()
            Toolchain.tools.filter { it.id in ids }.forEach { t ->
                appendLine("  '" + (t.id + "\t" + t.probe).replace("'", "'\\''") + "'")
            }
            appendLine(")")
        }
    }
}

/** The optional packs (and the Android SDK) the Install tools sheet lists below the essentials. */
object ToolPacks {
    /** In display order. The Python pack is left out: the Python, pip and uv essentials are that pack. */
    val offered: List<ToolSetup> = ToolSetup.entries.filter { it.optional && it != ToolSetup.Python } + ToolSetup.AndroidSdk

    /** Installed along with [pack]: Flutter runs inside the Ubuntu environment. */
    fun needs(pack: ToolSetup): Set<ToolSetup> = if (pack == ToolSetup.Flutter) setOf(ToolSetup.Ubuntu) else emptySet()

    fun closure(chosen: Collection<ToolSetup>): List<ToolSetup> {
        val all = chosen.toMutableSet()
        chosen.forEach { all += needs(it) }
        return offered.filter { it in all }
    }

    fun neededBy(pack: ToolSetup, chosen: Collection<ToolSetup>): List<ToolSetup> =
        offered.filter { it in chosen && pack in needs(it) }

    /** Packs that only install packages: they join essentials-setup.sh's single package run. */
    fun joinsPackageRun(pack: ToolSetup): Boolean = pack.optional && pack.asset == "pack-setup.sh"

    /** The System checks ids that say whether [pack] is installed; empty when only its marker file can. */
    fun checkIds(pack: ToolSetup): List<String> =
        if (pack == ToolSetup.AndroidSdk) listOf("android-sdk") else Toolchain.tools.filter { it.setup == pack }.map { "tool-${it.id}" }

    /** Free space its setup wants, in MB (android-sdk-setup.sh checks for 1500). */
    fun spaceMb(pack: ToolSetup): Int = if (pack == ToolSetup.AndroidSdk) 1500 else pack.spaceMb

    fun blurb(pack: ToolSetup): String =
        if (pack == ToolSetup.AndroidSdk) "Build Android apps on the phone: the SDK platform and build tools" else pack.blurb

    /**
     * The heading [pack] is listed under, null for setups the sheet doesn't offer. No `else`: a new
     * pack doesn't compile until it's given one.
     */
    fun category(pack: ToolSetup): PackCategory? = when (pack) {
        ToolSetup.Go, ToolSetup.Rust, ToolSetup.Ruby, ToolSetup.JsRuntimes, ToolSetup.Jvm, ToolSetup.Php, ToolSetup.Dart,
        ToolSetup.Dotnet, ToolSetup.Swift, ToolSetup.Zig, ToolSetup.Elixir, ToolSetup.Lua, ToolSetup.Crystal, ToolSetup.Nim,
        ToolSetup.Haskell, ToolSetup.Scala, ToolSetup.Dlang, ToolSetup.Perl -> PackCategory.Languages
        ToolSetup.BuildTools, ToolSetup.Debugging, ToolSetup.LanguageServers, ToolSetup.NativeLibs, ToolSetup.TaskRunners,
        ToolSetup.Editors -> PackCategory.Build
        ToolSetup.Utils, ToolSetup.Shell, ToolSetup.CliTools, ToolSetup.Archives, ToolSetup.Networking, ToolSetup.Security,
        ToolSetup.DataTools -> PackCategory.Terminal
        ToolSetup.Databases, ToolSetup.PyScience, ToolSetup.LocalAi -> PackCategory.Data
        ToolSetup.Servers, ToolSetup.DevOps, ToolSetup.Browser -> PackCategory.Web
        ToolSetup.Media, ToolSetup.Ocr, ToolSetup.Docs -> PackCategory.Media
        ToolSetup.AndroidSdk, ToolSetup.Ubuntu, ToolSetup.Flutter, ToolSetup.Distros -> PackCategory.Environments
        ToolSetup.DevTools, ToolSetup.Agents, ToolSetup.Python -> null
    }
}

/** Headings for the optional packs, in display order. */
enum class PackCategory(val title: String) {
    Languages("Languages"),
    Build("Build, debug and editors"),
    Terminal("Terminal and utilities"),
    Data("Data and AI"),
    Web("Web and servers"),
    Media("Media and documents"),
    Environments("Environments"),
}

/** What System checks says about an essential or a pack. */
enum class InstallStatus {
    Checking, Installed, Partial, Broken, Missing;

    companion object {
        /**
         * From the checks that decide it ([ids]): all pass → installed; any failing → broken; some
         * passing → partial. [checked] holds every check id that ran, [passing] and [failing] those
         * that passed or failed. Checking until all of them have run.
         */
        fun of(ids: List<String>, checked: Set<String>, passing: Set<String>, failing: Set<String>): InstallStatus = when {
            ids.isEmpty() || ids.any { it !in checked } -> Checking
            ids.all { it in passing } -> Installed
            ids.any { it in failing } -> Broken
            ids.any { it in passing } -> Partial
            else -> Missing
        }
    }
}

/**
 * The terminal command that installs [essentials] and [packs] (and what they need). Essentials and
 * package-only packs go through one essentials-setup.sh run (one package index refresh); the agents,
 * Ubuntu, Flutter, the browser and the Android SDK run their own setups after it. Each setup holds the
 * shared setup lock while it runs, so they run one after the other; every one runs even if an earlier
 * one failed, and the exit code reports any failure. A setup ended by Ctrl-C, a closed tab or Stop
 * (exit 129, 130 or 143: setup-common.sh's traps exit with those) ends the whole chain instead. The
 * developer tools are left to the agents setup when it runs, since it installs them itself. The SDK is
 * set up without its test build here (System checks' own Set up still runs it).
 */
fun installToolsCommand(essentials: Collection<Essential>, packs: Collection<ToolSetup>): String {
    val chosen = Essential.closure(essentials)
    val chosenPacks = ToolPacks.closure(packs)
    require(chosen.isNotEmpty() || chosenPacks.isNotEmpty()) { "Nothing to install" }
    require(chosenPacks.all { it in ToolPacks.offered }) { "Not an installable pack" }
    val agents = Essential.Agents in chosen
    val names = chosen.filter { it.packages.isNotEmpty() && !(agents && it in Essential.Agents.needs) }.map { it.id } +
        chosenPacks.filter(ToolPacks::joinsPackageRun).map { it.name.uppercase() }

    fun path(script: String) = "\"\$PREFIX/libexec/yurlungr/$script\""
    val runs = buildList {
        if (names.isNotEmpty()) add(path("essentials-setup.sh") to names.joinToString(" "))
        if (agents) add(path("agents-setup.sh") to "")
        chosenPacks.filter { !ToolPacks.joinsPackageRun(it) }.forEach { pack -> add(path(requireNotNull(pack.script)) to "") }
    }
    fun run(r: Pair<String, String>) = "bash ${r.first}" + if (r.second.isEmpty()) "" else " ${r.second}"
    // Parse every script before running any of them.
    val parse = runs.joinToString(" && ") { "bash -n ${it.first}" }
    return if (runs.size == 1) "$parse && ${run(runs.single())}"
    else "$parse && { r=0; " + runs.joinToString(" ") { "${run(it)} || { c=\$?; [ \$c -ge 129 ] && exit \$c; r=1; };" } + " [ \$r -eq 0 ]; }"
}
