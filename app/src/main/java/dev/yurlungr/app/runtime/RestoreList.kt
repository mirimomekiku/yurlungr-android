package dev.yurlungr.app.runtime

import java.io.File

/**
 * What was installed before a base-system reinstall, so it can be put back afterwards. The
 * reinstall replaces `$PREFIX` whole: every package, the global npm packages (agents among them),
 * native agents and Ubuntu go with it, while home and projects stay. Before the old prefix is
 * touched the app saves, in its files folder (outside the prefix):
 * - `pkg <name>`: packages installed by hand (installed, not marked auto-installed by apt: what
 *   `apt-mark showmanual` lists), read straight from dpkg's and apt's files so a broken apt (the
 *   usual reason to reinstall) doesn't stop it;
 * - `npm <name>`: global npm packages;
 * - `setup <script>`: setups that completed and do more than install packages (agents, Android SDK,
 *   Ubuntu, Flutter, the browser), from their status files;
 * - `agent <recipe>`: agents `agent-install.sh` installed.
 *
 * System checks then offers to reinstall whatever of that is still missing (the new base system's
 * own packages are left out by comparing with what's installed now).
 */
object RestoreList {
    const val FILE = "restore-after-reinstall.txt"

    data class Plan(
        val packages: List<String>,
        val npm: List<String>,
        val setups: List<ToolSetup>,
        val agents: List<String>,
    ) {
        /** Packages to reinstall, npm ones included: what the card counts. */
        val count: Int get() = packages.size + npm.size
        val isEmpty: Boolean get() = packages.isEmpty() && npm.isEmpty() && setups.isEmpty() && agents.isEmpty()
    }

    private val PACKAGE = Regex("^[a-z0-9][a-z0-9.+-]*$")
    private val NPM = Regex("^(@[a-z0-9][a-z0-9._-]*/)?[a-z0-9][a-z0-9._-]*$")
    /** npm itself and corepack come with Node.js. */
    private val NPM_BUNDLED = setOf("npm", "corepack")

    fun file(paths: RuntimePaths) = File(paths.filesDir, FILE)

    /** Setups worth rerunning after a reinstall: those that do more than install the packages listed. */
    internal val RERUN: List<ToolSetup> get() = ToolSetup.entries.filter { it.script != null && it.asset != "pack-setup.sh" && it != ToolSetup.DevTools }

    /**
     * Saves what's installed in the current prefix, added to what an earlier, not yet restored
     * reinstall saved (a second reinstall must not forget it). Call off the main thread, before the
     * prefix is replaced. Never throws: losing this list must not stop a repair.
     */
    fun save(paths: RuntimePaths) {
        runCatching {
            val now = snapshot(
                dpkgStatus = read(File(paths.prefix, "var/lib/dpkg/status")),
                extendedStates = read(File(paths.prefix, "var/lib/apt/extended_states")),
                npmGlobals = npmGlobals(File(paths.prefix, "lib/node_modules")),
                completed = completedSetups(paths.setupStatusDir),
            )
            val target = file(paths)
            val merged = (parse(read(target)) + now).distinct()
            if (merged.isEmpty()) return
            val tmp = File(target.path + ".tmp")
            tmp.writeText(merged.joinToString("\n", postfix = "\n") { "${it.first} ${it.second}" })
            if (!tmp.renameTo(target)) tmp.delete()
        }.onFailure { android.util.Log.w("RestoreList", "Couldn't save the list of installed packages", it) }
    }

    /** What to reinstall now, or null when nothing is (or no list was saved). Call off the main thread. */
    fun pending(paths: RuntimePaths): Plan? {
        val saved = file(paths).takeIf { it.isFile } ?: return null
        val plan = plan(
            saved = parse(read(saved)),
            installed = Storage.installedSizes(paths.prefix).keys,
            npmInstalled = npmGlobals(File(paths.prefix, "lib/node_modules")).toSet(),
            completed = completedSetups(paths.setupStatusDir),
        )
        return plan.takeUnless { it.isEmpty }
    }

    /** Forgets the saved list (restored, or the user doesn't want it). */
    fun dismiss(paths: RuntimePaths) {
        file(paths).delete()
    }

    /** `kind name` pairs for what's installed: see the class comment. */
    internal fun snapshot(dpkgStatus: String, extendedStates: String, npmGlobals: List<String>, completed: Set<String>): List<Pair<String, String>> {
        val auto = autoInstalled(extendedStates)
        val manual = Storage.parseDpkgStatus(dpkgStatus).keys.filter { it !in auto }.sorted()
        return manual.filter(PACKAGE::matches).map { "pkg" to it } +
            npmGlobals.filter { NPM.matches(it) && it !in NPM_BUNDLED }.map { "npm" to it } +
            RERUN.mapNotNull { s -> s.script!!.removeSuffix(".sh").takeIf { it in completed }?.let { "setup" to it } } +
            AGENT_RECIPES.sorted().filter { "agent-install-$it" in completed }.map { "agent" to it }
    }

    /** What of [saved] is missing now. */
    internal fun plan(saved: List<Pair<String, String>>, installed: Set<String>, npmInstalled: Set<String>, completed: Set<String>): Plan {
        fun of(kind: String) = saved.filter { it.first == kind }.map { it.second }.distinct()
        return Plan(
            packages = of("pkg").filter { PACKAGE.matches(it) && it !in installed },
            npm = of("npm").filter { NPM.matches(it) && it !in npmInstalled && it !in NPM_BUNDLED },
            setups = of("setup").let { names -> RERUN.filter { s -> s.script!!.removeSuffix(".sh").let { it in names && it !in completed } } },
            agents = of("agent").filter { it in AGENT_RECIPES && "agent-install-$it" !in completed },
        )
    }

    /** Packages apt marked as installed only as a dependency (extended_states' `Auto-Installed: 1`). */
    internal fun autoInstalled(extendedStates: String): Set<String> = extendedStates.split("\n\n").mapNotNull { block ->
        var name: String? = null
        var auto = false
        for (line in block.lines()) when {
            line.startsWith("Package: ") -> name = line.removePrefix("Package: ").trim()
            line.startsWith("Auto-Installed: ") -> auto = line.removePrefix("Auto-Installed: ").trim() == "1"
        }
        name?.takeIf { auto }
    }.toSet()

    internal fun parse(text: String): List<Pair<String, String>> = text.lines().mapNotNull { line ->
        val parts = line.trim().split(' ')
        if (parts.size == 2 && parts[0] in setOf("pkg", "npm", "setup", "agent")) parts[0] to parts[1] else null
    }

    /**
     * The command that reinstalls [plan] in a terminal: `restore-packages.sh` for the packages (read
     * from [listFile], the saved list: see [file]), then
     * each setup and agent install. As with Install tools, a failed step doesn't stop the others,
     * but Ctrl-C or a closed tab (exit 129 and up) ends the chain.
     */
    fun command(listFile: File, plan: Plan): String {
        require(!plan.isEmpty) { "Nothing to restore" }
        fun path(script: String) = "\"\$PREFIX/libexec/yurlungr/$script\""
        fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"
        val runs = buildList {
            if (plan.count > 0) add(path("restore-packages.sh") to q(listFile.path))
            plan.setups.forEach { add(path(it.script!!) to "") }
            plan.agents.forEach { require(it in AGENT_RECIPES); add(path("agent-install.sh") to it) }
        }
        fun run(r: Pair<String, String>) = "bash ${r.first}" + if (r.second.isEmpty()) "" else " ${r.second}"
        val parse = runs.map { it.first }.distinct().joinToString(" && ") { "bash -n $it" }
        return if (runs.size == 1) "$parse && ${run(runs.single())}"
        else "$parse && { r=0; " + runs.joinToString(" ") { "${run(it)} || { c=\$?; [ \$c -ge 129 ] && exit \$c; r=1; };" } + " [ \$r -eq 0 ]; }"
    }

    /** Names of setups whose last run succeeded (`exit 0` in `<name>.status`). */
    private fun completedSetups(dir: File): Set<String> =
        dir.listFiles { f -> f.name.endsWith(".status") }.orEmpty()
            .filter { SetupStatus.read(it)?.exitCode == 0 }
            .map { it.name.removeSuffix(".status") }.toSet()

    /** Global npm packages, scoped ones as `@scope/name`. */
    private fun npmGlobals(dir: File): List<String> = dir.listFiles().orEmpty().filter { it.isDirectory && !it.name.startsWith(".") }.flatMap { d ->
        if (d.name.startsWith("@")) d.listFiles().orEmpty().filter { it.isDirectory && !it.name.startsWith(".") }.map { "${d.name}/${it.name}" }
        else listOf(d.name)
    }.sorted()

    private fun read(file: File): String = runCatching { if (file.isFile && file.length() < 32L * 1024 * 1024) file.readText() else "" }.getOrDefault("")
}
