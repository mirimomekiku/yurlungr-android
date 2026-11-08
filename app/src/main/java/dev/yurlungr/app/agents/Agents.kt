package dev.yurlungr.app.agents

import dev.yurlungr.app.runtime.RuntimePaths
import java.io.File

/**
 * The coding agents the app can chat with, and how to launch each over ACP inside the
 * runtime. Launch recipes come from the Phase 0 spike (see assets/agents-setup.sh).
 */
enum class Agent(
    val displayName: String,
    val vendor: String,
    /** Shell command that starts the agent speaking ACP on stdio. */
    val acpCommand: String,
    /** Files under $PREFIX that must exist for the agent to be usable. */
    private val requiredFiles: List<String>,
    /** Credential files under $HOME that indicate a signed-in CLI. */
    private val credentialFiles: List<String>,
    /** Interactive sign-in, run in a terminal tab. */
    val loginCommand: String,
    val loginHint: String,
) {
    Claude(
        displayName = "Claude",
        vendor = "Anthropic · Claude Code",
        acpCommand = "claude-agent-acp",
        requiredFiles = listOf("bin/claude-agent-acp", "libexec/yurlungr/claude-glibc"),
        credentialFiles = listOf(".claude/.credentials.json"),
        loginCommand = "claude",
        loginHint = "Type /login and pick your Claude account. When it says you're logged in, type /exit.",
    ),
    Codex(
        displayName = "Codex",
        vendor = "OpenAI · Codex CLI",
        acpCommand = "codex-acp",
        requiredFiles = listOf("bin/codex", "bin/codex-acp"),
        credentialFiles = listOf(".codex/auth.json"),
        loginCommand = "codex login",
        loginHint = "Sign in with ChatGPT in the browser that opens. The login page redirects back to this phone.",
    ),
    Gemini(
        displayName = "Gemini",
        vendor = "Google · Gemini CLI",
        acpCommand = "gemini --acp",
        requiredFiles = listOf("bin/gemini"),
        credentialFiles = listOf(".gemini/oauth_creds.json"),
        loginCommand = "gemini",
        loginHint = "Choose “Login with Google” and finish in the browser. Then type /quit.",
    ),
    Antigravity(
        displayName = "Antigravity",
        vendor = "Google · Antigravity CLI (agy)",
        acpCommand = "agy acp",
        requiredFiles = listOf("bin/agy"),
        // Tokens go to a file on Android (no OS keyring), but its path isn't documented: status unknown.
        credentialFiles = emptyList(),
        loginCommand = "agy acp --login",
        loginHint = "Sign in with your Google account in the browser that opens. If it asks for a code, paste it here. It exits when done.",
    );

    /** Antigravity exposes its approval override as a launch flag, separately from ACP modes.
     * https://antigravity.google/docs/ide/extensions/acp/
     */
    fun launchCommand(skipPermissions: Boolean): String =
        if (this == Antigravity && skipPermissions) "$acpCommand --dangerously-skip-permissions" else acpCommand

    /** Extra environment for the ACP process. */
    fun env(paths: RuntimePaths): Map<String, String> = when (this) {
        Claude -> mapOf(
            "CLAUDE_CODE_EXECUTABLE" to File(paths.libexec, "claude").path,
            "DISABLE_AUTOUPDATER" to "1",
        )
        Codex -> mapOf("CODEX_PATH" to File(paths.bin, "codex").path)
        Gemini, Antigravity -> emptyMap()
    }

    fun isInstalled(paths: RuntimePaths): Boolean = requiredFiles.all { File(paths.prefix, it).exists() }

    /**
     * Best-effort: a credential file exists. Null when the agent's credential location isn't
     * known (the chat still detects a missing sign-in via ACP's auth error).
     */
    fun isSignedIn(paths: RuntimePaths): Boolean? =
        if (credentialFiles.isEmpty()) null else credentialFiles.any { File(paths.home, it).isFile }
}

data class AgentStatus(val agent: Agent, val installed: Boolean, val signedIn: Boolean?)

fun agentStatuses(paths: RuntimePaths): List<AgentStatus> =
    Agent.entries.map { AgentStatus(it, it.isInstalled(paths), it.isSignedIn(paths)) }
