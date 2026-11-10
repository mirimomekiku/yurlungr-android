package dev.yurlungr.app.runtime

/** Assets can come from Windows checkouts or ZIPs, so Git attributes alone aren't enough. */
internal fun normalizeRuntimeScript(source: String): String {
    val text = source.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')
    require(text.isNotBlank()) { "Runtime script is empty" }
    require('\u0000' !in text) { "Runtime script contains a NUL byte" }
    return if (text.endsWith('\n')) text else "$text\n"
}

internal fun setupScriptCommand(name: String, test: Boolean = false): String {
    require(name in setOf("agents-setup.sh", "android-sdk-setup.sh", "dev-tools-setup.sh"))
    val path = "\"\$PREFIX/libexec/yurlungr/$name\""
    // Parse the entire script before executing even its first command.
    return "bash -n $path && bash $path" + if (test) " --test" else ""
}
