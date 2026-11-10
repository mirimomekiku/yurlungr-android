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
    /**
     * For optional agents: the npm package that installs it on its own (from the Agents screen),
     * instead of with the agents setup. Null for the core agents that setup always installs.
     */
    val npmPackage: String? = null,
    /** For optional agents shipped as native Linux binaries: the `agent-install.sh` recipe. */
    val installRecipe: String? = null,
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
    ),

    // Optional agents: pure Node npm packages, so they run on Termux as they are.
    // https://qwenlm.github.io/qwen-code-docs/en/users/integration-zed
    Qwen(
        displayName = "Qwen Code",
        vendor = "Alibaba · Qwen Code",
        acpCommand = "qwen --acp",
        requiredFiles = listOf("bin/qwen"),
        // Which file holds the sign-in depends on the chosen method: status unknown (ACP reports AUTH_REQUIRED).
        credentialFiles = emptyList(),
        loginCommand = "qwen",
        loginHint = "Type /auth and choose how to sign in (Qwen's free OAuth tier ended in April 2026: use an API key or an Alibaba Cloud plan). Then type /quit.",
        npmPackage = "@qwen-code/qwen-code",
    ),
    // https://www.kimi.com/code/docs/en/kimi-code-cli/reference/kimi-acp.html
    Kimi(
        displayName = "Kimi Code",
        vendor = "Moonshot AI · Kimi Code CLI",
        acpCommand = "kimi acp",
        requiredFiles = listOf("bin/kimi"),
        credentialFiles = emptyList(),
        loginCommand = "kimi login",
        loginHint = "Sign in with your Kimi account in the browser that opens. It exits when done.",
        npmPackage = "@moonshot-ai/kimi-code",
    ),
    // https://docs.augmentcode.com/cli/acp
    Auggie(
        displayName = "Auggie",
        vendor = "Augment Code · Auggie CLI",
        acpCommand = "auggie --acp",
        requiredFiles = listOf("bin/auggie"),
        credentialFiles = emptyList(),
        loginCommand = "auggie login",
        loginHint = "Sign in with your Augment account in the browser that opens. It exits when done.",
        npmPackage = "@augmentcode/auggie",
    ),
    // https://commandcode.ai/docs/acp. Its short command `cmd` clashes with Android's /system/bin/cmd,
    // so the package's `commandcode` alias is used throughout.
    CommandCode(
        displayName = "Command Code",
        vendor = "Command Code · CLI",
        acpCommand = "commandcode acp",
        requiredFiles = listOf("bin/commandcode"),
        credentialFiles = emptyList(),
        loginCommand = "commandcode login",
        loginHint = "Sign in with your Command Code account in the browser that opens. It exits when done.",
        npmPackage = "command-code",
    ),

    // Optional native agents: glibc builds run on Termux's glibc (see assets/agent-install.sh).
    // https://docs.github.com/en/copilot/reference/copilot-cli-reference/acp-server
    Copilot(
        displayName = "Copilot",
        vendor = "GitHub · Copilot CLI",
        acpCommand = "copilot --acp",
        requiredFiles = listOf("bin/copilot", "libexec/yurlungr/copilot/copilot"),
        credentialFiles = emptyList(),
        loginCommand = "copilot login",
        loginHint = "Open the link shown, enter the one-time code and approve. It exits when you're signed in.",
        installRecipe = "copilot",
    ),
    // https://goose-docs.ai/docs/guides/acp-clients
    Goose(
        displayName = "Goose",
        vendor = "Block · Goose",
        acpCommand = "goose acp",
        requiredFiles = listOf("libexec/yurlungr/bin/goose", "libexec/yurlungr/goose/goose"),
        credentialFiles = emptyList(),
        loginCommand = "goose configure",
        loginHint = "Choose a model provider and enter its API key (Goose uses your own provider). Then exit.",
        installRecipe = "goose",
    ),
    // https://opencode.ai/docs/acp/
    OpenCode(
        displayName = "OpenCode",
        vendor = "SST · OpenCode",
        acpCommand = "opencode acp",
        requiredFiles = listOf("bin/opencode", "libexec/yurlungr/opencode/opencode"),
        credentialFiles = emptyList(),
        loginCommand = "opencode auth login",
        loginHint = "Pick a provider and sign in or paste its API key. It exits when done.",
        installRecipe = "opencode",
    );

    /** Installed on its own (see [npmPackage], [installRecipe]) rather than by the agents setup. */
    val isOptional: Boolean get() = npmPackage != null || installRecipe != null

    /** The terminal command that installs (or updates) an optional agent. */
    fun installCommand(): String? = installRecipe?.let { dev.yurlungr.app.runtime.agentInstallCommand(it) } ?: npmPackage?.let { pkg ->
        "command -v npm >/dev/null || { echo 'npm is missing: install the developer tools first (Settings → System runtime).'; exit 1; }; " +
            "npm install -g --loglevel=http '$pkg@latest' && echo && echo '$displayName installed. Sign in from ☰ → Agents.'"
    }

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
        Gemini, Antigravity, Qwen, Kimi, Auggie, CommandCode, Copilot, Goose, OpenCode -> emptyMap()
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
