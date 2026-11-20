package dev.yurlungr.app.runtime

/** Assets can come from Windows checkouts or ZIPs, so Git attributes alone aren't enough. */
internal fun normalizeRuntimeScript(source: String): String {
    val text = source.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')
    require(text.isNotBlank()) { "Runtime script is empty" }
    require('\u0000' !in text) { "Runtime script contains a NUL byte" }
    return if (text.endsWith('\n')) text else "$text\n"
}

internal fun setupScriptCommand(name: String, test: Boolean = false): String {
    require(name in ToolSetup.entries.mapNotNull { it.script }.toSet())
    val path = "\"\$PREFIX/libexec/yurlungr/$name\""
    // Parse the entire script before executing even its first command.
    return "bash -n $path && bash $path" + if (test) " --test" else ""
}

/**
 * The command that installs a project's dependencies with `project-setup.sh`: [packages] (Termux
 * toolchain) then each of [steps] in [dir]. Every value is shell-quoted; steps are run by bash.
 */
internal fun projectSetupCommand(dir: java.io.File, packages: List<String>, steps: List<String>): String {
    require(steps.isNotEmpty()) { "Nothing to run" }
    require(packages.all { Regex("^[a-z0-9][a-z0-9.+-]*$").matches(it) }) { "Bad package name" }
    fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"
    val path = "\"\$PREFIX/libexec/yurlungr/project-setup.sh\""
    return "bash -n $path && bash $path " + (listOf(dir.path, packages.joinToString(" ")) + steps).joinToString(" ") { q(it) }
}

/** The command that removes an optional pack's packages with `pack-remove.sh`. */
internal fun packRemoveCommand(pack: ToolSetup): String {
    require(pack.optional) { "Only optional packs can be removed" }
    val path = "\"\$PREFIX/libexec/yurlungr/pack-remove.sh\""
    return "bash -n $path && bash $path ${pack.name.uppercase()}"
}

/** Refreshes the package lists, then upgrades every installed package (in a terminal). */
internal fun packagesUpgradeCommand(): String = SetupLock.wrap(Storage.UPGRADE_COMMAND)

/**
 * The lock every setup script holds (setup-common.sh). Package and cache actions started from the
 * app take it too, so they can't run under a setup: apt would fail on dpkg's lock, and clearing
 * the npm cache would pull files from under a running `npm install`.
 */
object SetupLock {
    /** Exit status when a setup holds the lock. */
    const val BUSY = 75
    const val BUSY_MESSAGE = "A setup is running. Try again when it finishes."

    /**
     * How long a setup gets to stop before what's left is killed (other commands get 1.5 s). dpkg
     * killed mid-unpack leaves a package half-installed; given a few seconds after SIGTERM, it and
     * the setup's traps finish the file they're on and record the stop. Bounded, so a stuck step
     * can't keep a closed tab's processes around.
     */
    const val STOP_GRACE_MS = 10_000L

    /**
     * Whether a terminal command is a setup or package action: one of the app's scripts in
     * `libexec/yurlungr` (setups, pack removal, agent installs, project setup) or a command wrapped
     * with [wrap]. Those can be running apt or dpkg, which get [STOP_GRACE_MS] to stop.
     */
    fun isSetupCommand(command: String?): Boolean =
        command != null && ("/libexec/yurlungr/" in command || "yurlungr-setup.lock" in command)

    /** [command] run while holding the lock, or exit [BUSY] with a message if a setup has it. */
    fun wrap(command: String): String {
        val quoted = "'" + command.replace("'", "'\\''") + "'"
        // A subshell: a terminal tab runs more after this (its "finished" line, a login shell).
        return "(mkdir -p \"\$PREFIX/var/lock\" && flock -n -E $BUSY \"\$PREFIX/var/lock/yurlungr-setup.lock\" bash -c $quoted; " +
            "rc=\$?; [ \$rc -eq $BUSY ] && echo '$BUSY_MESSAGE' >&2; exit \$rc)"
    }
}

/** The recipes `agent-install.sh` knows: native glibc builds, then Python (uv) packages. */
internal val AGENT_RECIPES = setOf("copilot", "goose", "opencode", "cline", "kilo", "cursor", "amp", "vibe", "kiro", "fast-agent", "minion")

/** The command that installs or updates one native-binary or Python agent with `agent-install.sh`. */
internal fun agentInstallCommand(recipe: String): String {
    require(recipe in AGENT_RECIPES) { "Unknown agent recipe: $recipe" }
    val path = "\"\$PREFIX/libexec/yurlungr/agent-install.sh\""
    return "bash -n $path && bash $path $recipe"
}
