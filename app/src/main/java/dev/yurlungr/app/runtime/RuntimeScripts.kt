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

/** The command that installs or updates one native-binary agent with `agent-install.sh`. */
internal fun agentInstallCommand(recipe: String): String {
    require(recipe in setOf("copilot", "goose", "opencode")) { "Unknown agent recipe: $recipe" }
    val path = "\"\$PREFIX/libexec/yurlungr/agent-install.sh\""
    return "bash -n $path && bash $path $recipe"
}
