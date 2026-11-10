package dev.yurlungr.app.runtime

/** Which setup provides a tool, i.e. what to run when it's missing or broken. */
enum class ToolSetup(val script: String?, val title: String) {
    DevTools("dev-tools-setup.sh", "Developer tools"),
    Agents("agents-setup.sh", "Agents"),
    AndroidSdk("android-sdk-setup.sh", "Android SDK"),
}

/**
 * A command-line tool the app or its users rely on. [probe] is a shell command that prints a
 * version line and exits 0 when the tool works; exit 127 means it isn't installed.
 */
data class Tool(
    val id: String,
    val name: String,
    val probe: String,
    val setup: ToolSetup,
    /** Shown as a failure (not just a warning) when broken, because core features need it. */
    val essential: Boolean = false,
    /** Only checked when this file under the prefix exists (e.g. an agent that's been installed). */
    val onlyIf: String? = null,
)

/** Result of probing one [Tool]. */
data class ToolState(val tool: Tool, val exitCode: Int, val firstLine: String) {
    val ok: Boolean get() = exitCode == 0 && firstLine.isNotBlank()
    val missing: Boolean get() = exitCode == 127
    val timedOut: Boolean get() = exitCode == 124
}

/**
 * The single list of packages and tools: setup scripts read it (via the generated
 * `toolchain.sh`, see [shellManifest]) and System checks probe it, so they can't drift apart.
 */
object Toolchain {
    /** Packages for git/GitHub/Node work and for running npm-based CLIs (termux-exec fixes their shebangs). */
    val DEV_PACKAGES = listOf("termux-exec", "git", "gh", "nodejs-lts", "ripgrep", "curl")

    /** Extra packages the agents need, before and after enabling the glibc repository. */
    val AGENT_PACKAGES = listOf("patchelf", "glibc-repo")
    val AGENT_GLIBC_PACKAGES = listOf("glibc-runner", "codex")

    /** Termux's `gradle` only bootstraps the 8.x wrapper; projects build with ./gradlew. */
    val SDK_PACKAGES = listOf("openjdk-17", "gradle", "aapt", "aapt2", "aidl", "apksigner", "d8", "unzip", "curl")
    const val SDK_PLATFORM = "android-36"
    const val SDK_BUILD_TOOLS = "36.0.0"

    val tools = listOf(
        Tool("git", "Git", "git --version", ToolSetup.DevTools, essential = true),
        Tool("gh", "GitHub CLI", "gh --version | head -n1", ToolSetup.DevTools),
        Tool("node", "Node.js", "node --version", ToolSetup.DevTools),
        Tool("npm", "npm", "npm --version", ToolSetup.DevTools),
        Tool("rg", "ripgrep", "rg --version | head -n1", ToolSetup.DevTools),
        Tool("gemini", "Gemini CLI", "gemini --version", ToolSetup.Agents, onlyIf = "bin/gemini"),
        Tool("codex", "Codex", "codex --version", ToolSetup.Agents, onlyIf = "bin/codex"),
        Tool("codex-acp", "Codex ACP adapter", "test -x \"\$(command -v codex-acp)\" && echo \"\$(command -v codex-acp)\"", ToolSetup.Agents, onlyIf = "bin/codex-acp"),
        Tool("claude", "Claude Code", "claude --version", ToolSetup.Agents, onlyIf = "libexec/yurlungr/claude-glibc"),
        Tool("claude-agent-acp", "Claude ACP adapter", "test -x \"\$(command -v claude-agent-acp)\" && echo \"\$(command -v claude-agent-acp)\"", ToolSetup.Agents, onlyIf = "bin/claude-agent-acp"),
        Tool("agy", "Antigravity", "agy --version", ToolSetup.Agents, onlyIf = "bin/agy"),
        Tool("java", "Java (JDK)", "java -version 2>&1 | head -n1", ToolSetup.AndroidSdk, onlyIf = "bin/java"),
        Tool("aapt2", "aapt2", "aapt2 version", ToolSetup.AndroidSdk, onlyIf = "bin/aapt2"),
    )

    /** Seconds each probe may take; agent CLIs start Node, which is slow on phones. */
    const val PROBE_TIMEOUT_S = 20

    /**
     * One script that probes [tools] in parallel and prints `id<TAB>exit<TAB>first line` per tool.
     * 127 = not installed, 124 = timed out.
     */
    fun probeScript(tools: List<Tool>): String = buildString {
        appendLine("d=\$(mktemp -d \"\${TMPDIR:-\$PREFIX/tmp}/probe.XXXXXX\") || exit 70")
        for ((i, tool) in tools.withIndex()) {
            // Each probe in its own subshell; the outer timeout bounds a hung tool.
            // pipefail: "gh --version | head -n1" must fail when gh is missing, not report head's 0.
            append("( out=\$(timeout ").append(PROBE_TIMEOUT_S).append(" bash -o pipefail -c ").append(shellQuote(tool.probe))
            append(" 2>&1 </dev/null); s=\$?; ")
            append("printf '%s\\t%s\\t%s\\n' ").append(shellQuote(tool.id)).append(" \"\$s\" \"\$(printf '%s' \"\$out\" | grep -m1 . | tr '\\t' ' ')\" > \"\$d/").append(i).append("\" ) &\n")
        }
        appendLine("wait")
        appendLine("cat \"\$d\"/* 2>/dev/null; rm -rf \"\$d\"")
    }

    /** Parses [probeScript] output; tools with no line (e.g. the shell died) count as not runnable. */
    fun parseProbe(output: String, tools: List<Tool>): List<ToolState> {
        val lines = output.lines().mapNotNull { line ->
            val f = line.split('\t', limit = 3)
            if (f.size >= 2) f[0] to (f[1].trim().toIntOrNull() to f.getOrElse(2) { "" }.trim()) else null
        }.toMap()
        return tools.map { tool ->
            val (code, text) = lines[tool.id] ?: (null to "")
            ToolState(tool, code ?: 126, text)
        }
    }

    /** Sourced by the setup scripts: the package lists and SDK versions above, as Bash. */
    fun shellManifest(): String = buildString {
        appendLine("# Generated by the Yurlungr app from Toolchain.kt on every launch. Don't edit.")
        appendLine("YURLUNGR_DEV_PACKAGES=(${DEV_PACKAGES.joinToString(" ")})")
        appendLine("YURLUNGR_AGENT_PACKAGES=(${AGENT_PACKAGES.joinToString(" ")})")
        appendLine("YURLUNGR_AGENT_GLIBC_PACKAGES=(${AGENT_GLIBC_PACKAGES.joinToString(" ")})")
        appendLine("YURLUNGR_SDK_PACKAGES=(${SDK_PACKAGES.joinToString(" ")})")
        appendLine("YURLUNGR_SDK_PLATFORM=$SDK_PLATFORM")
        appendLine("YURLUNGR_SDK_BUILD_TOOLS=$SDK_BUILD_TOOLS")
        // "name<TAB>command" per tool and setup, for the scripts' final version checks.
        for (setup in ToolSetup.entries) {
            val name = "YURLUNGR_CHECKS_" + setup.name.uppercase()
            appendLine("$name=(")
            tools.filter { it.setup == setup }.forEach { appendLine("  ${shellQuote(it.id + "\t" + it.probe)}") }
            appendLine(")")
        }
    }

    private fun shellQuote(s: String) = "'" + s.replace("'", "'\\''") + "'"
}
