package dev.yurlungr.app.home

import dev.yurlungr.app.ui.AppColors
import dev.yurlungr.app.ui.Ph
import dev.yurlungr.app.ui.PhIcon
import dev.yurlungr.app.ui.ScreenHeader
import dev.yurlungr.app.ui.StatusLabel
import dev.yurlungr.app.agents.AgentBadge
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.IconButton
import androidx.compose.ui.unit.sp
import dev.yurlungr.app.ui.Keyboards
import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.yurlungr.app.agents.Agent
import dev.yurlungr.app.agents.AgentStatus
import dev.yurlungr.app.agents.agentStatuses
import dev.yurlungr.app.chat.ChatSession
import dev.yurlungr.app.chat.ChatSessions
import dev.yurlungr.app.chat.ChatState
import dev.yurlungr.app.runtime.RuntimePaths
import java.io.File

/** Agents and their install/sign-in state (opened from the sidebar). */
@Composable
fun AgentsScreen(
    refreshTick: Int,
    onBack: () -> Unit,
    onOpenChat: (ChatSession) -> Unit,
    onLogin: (Agent) -> Unit,
    onInstallAgents: () -> Unit,
    /** Installs one optional agent (its npm package) in a terminal. */
    onInstallAgent: (Agent) -> Unit,
) {
    val context = LocalContext.current
    val paths = remember { RuntimePaths(context) }
    // File checks only — cheap enough to redo whenever the screen resumes.
    val statuses = remember(refreshTick) { agentStatuses(paths) }
    var newChatFor by remember { mutableStateOf<Agent?>(null) }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding(),
    ) {
        ScreenHeader("Agents", onBack) {
            dev.yurlungr.app.ui.LocalOpenExtensions.current?.let { openExt ->
                IconButton(onClick = { openExt(null, null) }) { PhIcon(Ph.Wrench, "Skills and commands", size = 20.dp) }
            }
            IconButton(onClick = onInstallAgents) { PhIcon(Ph.ArrowClockwise, "Install or update agents", size = 20.dp) }
        }
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            val (core, optional) = statuses.partition { !it.agent.isOptional }
            items(core, key = { it.agent.name }) { status ->
                AgentCard(
                    status,
                    onNewChat = { newChatFor = status.agent },
                    onLogin = { onLogin(status.agent) },
                    onInstall = onInstallAgents,
                )
            }
            if (core.any { !it.installed }) {
                item {
                    Text(
                        "Install sets up these agents in the built-in Linux runtime (~1.2 GB, a few minutes).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 6.dp),
                    )
                }
            }
            if (optional.isNotEmpty()) {
                item(key = "more") { dev.yurlungr.app.ui.SectionLabel("More agents") }
                item(key = "more-note") {
                    Text(
                        "Installed one by one. The npm-based ones need the developer tools (Node.js); Copilot, Goose and " +
                            "OpenCode download a Linux build (~60–200 MB) that runs on the phone's glibc runtime (64-bit ARM).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 6.dp),
                    )
                }
                items(optional, key = { it.agent.name }) { status ->
                    AgentCard(
                        status,
                        onNewChat = { newChatFor = status.agent },
                        onLogin = { onLogin(status.agent) },
                        onInstall = { onInstallAgent(status.agent) },
                    )
                }
            }
        }
    }

    newChatFor?.let { agent ->
        NewChatDialog(
            agent = agent,
            projectsDir = paths.projects,
            onDismiss = { newChatFor = null },
            onStart = { dir ->
                newChatFor = null
                onOpenChat(ChatSessions.create(context, agent, dir))
            },
        )
    }
}

@Composable
private fun AgentCard(status: AgentStatus, onNewChat: () -> Unit, onLogin: () -> Unit, onInstall: () -> Unit) {
    val agent = status.agent
    val tones = AppColors.status
    val (label, color) = when {
        !status.installed -> "Not installed" to MaterialTheme.colorScheme.outline
        status.signedIn == false -> "Sign in" to tones.warning
        status.signedIn == null -> "Installed" to MaterialTheme.colorScheme.primary // sign-in state unknown
        else -> "Ready" to tones.success
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AgentBadge(agent)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(agent.displayName, style = MaterialTheme.typography.titleMedium, fontSize = 17.sp)
                Text(agent.vendor, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            StatusLabel(label, color)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val wide = Modifier.weight(1f).height(42.dp)
            when {
                !status.installed -> Button(onClick = onInstall, modifier = wide) { Text("Install") }
                status.signedIn == false -> FilledTonalButton(onClick = onLogin, modifier = wide) { Text(signInLabel(agent)) }
                else -> {
                    Button(onClick = onNewChat, modifier = wide) { Text("New chat") }
                    OutlinedButton(onClick = onLogin, modifier = wide) { Text(when {
                        agent in setOf(Agent.MistralVibe, Agent.FastAgent, Agent.MinionCode) -> "API key"
                        status.signedIn == true -> "Account"
                        else -> "Sign in"
                    }) }
                }
            }
        }
    }
}

private fun signInLabel(agent: Agent) = when (agent) {
    Agent.Claude -> "Sign in with Claude"
    Agent.Codex -> "Sign in with ChatGPT"
    Agent.Gemini, Agent.Antigravity -> "Sign in with Google"
    Agent.Qwen -> "Sign in"
    Agent.Kimi -> "Sign in with Kimi"
    Agent.Auggie -> "Sign in with Augment"
    Agent.CommandCode -> "Sign in"
    Agent.Copilot -> "Sign in with GitHub"
    Agent.Goose -> "Set up a provider"
    Agent.OpenCode, Agent.Qoder, Agent.Cline, Agent.Kilo, Agent.Kiro, Agent.Pi -> "Sign in"
    Agent.MiniMax -> "Sign in with MiniMax"
    Agent.Cursor -> "Sign in with Cursor"
    Agent.Amp -> "Sign in with Amp"
    Agent.MistralVibe, Agent.FastAgent, Agent.MinionCode -> "Add an API key"
}

private fun relativeTime(millis: Long): String =
    DateUtils.getRelativeTimeSpanString(millis, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()

@Composable
private fun NewChatDialog(agent: Agent, projectsDir: File, onDismiss: () -> Unit, onStart: (File) -> Unit) {
    var name by remember { mutableStateOf("scratch") }
    val dir = remember(name) {
        val trimmed = name.trim()
        if (trimmed.startsWith("/")) File(trimmed) else File(projectsDir, trimmed.ifEmpty { "scratch" })
    }
    val existing = remember { projectsDir.listFiles { f -> f.isDirectory }?.map { it.name }?.sorted().orEmpty() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New ${agent.displayName} chat") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Project folder") },
                    singleLine = true,
                    keyboardOptions = Keyboards.Identifier,
                    supportingText = { Text("~/projects/${dir.name}" .takeIf { !name.trim().startsWith("/") } ?: dir.path) },
                )
                if (existing.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (p in existing.take(12)) {
                            TextButton(onClick = { name = p }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text(p) }
                        }
                    }
                }
            }
        },
        confirmButton = { Button(onClick = { onStart(dir) }) { Text("Start") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
