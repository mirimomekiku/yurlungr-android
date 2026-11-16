package dev.yurlungr.app.chat

import androidx.compose.ui.graphics.vector.ImageVector
import dev.yurlungr.app.ui.PhIcon
import dev.yurlungr.app.ui.NavTile
import dev.yurlungr.app.ui.AppColors
import androidx.compose.foundation.border
import androidx.compose.ui.unit.sp
import dev.yurlungr.app.agents.AgentBadge
import dev.yurlungr.app.ui.Ph
import dev.yurlungr.app.ui.ShuffleGreeting
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import dev.yurlungr.app.ui.Appear
import dev.yurlungr.app.ui.Motion
import android.content.Context
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import dev.yurlungr.app.agents.Agent
import dev.yurlungr.app.agents.AgentStatus
import dev.yurlungr.app.agents.agentStatuses
import dev.yurlungr.app.runtime.RuntimePaths
import dev.yurlungr.app.ui.Keyboards
import dev.yurlungr.app.ui.NavButton
import java.io.File

private data class Suggestion(val icon: ImageVector, val title: String, val subtitle: String, val prompt: String)

private val SUGGESTIONS = listOf(
    Suggestion(
        Ph.AndroidLogo, "Create an Android app", "Kotlin + Compose, built on this phone",
        "Create a minimal Android app in this folder with Kotlin and Jetpack Compose that builds with ./gradlew assembleDebug, then build it and install it with yurlungr-install.",
    ),
    Suggestion(
        Ph.BookOpen, "Explain this project", "Structure, entry points, how to run it",
        "Give me a short overview of this project: what it does, how it's structured, and how to build and run it.",
    ),
    Suggestion(Ph.Wrench, "Fix the build", "Find why it fails and fix it", "Run the build, find out why it fails, and fix it."),
    Suggestion(Ph.TestTube, "Write tests", "For the most important logic", "Add unit tests for the most important logic in this project and run them."),
)

/** Remembers the last agent and project folder used for a new chat. */
private object NewChatPrefs {
    private const val PREFS = "new_chat"
    fun agent(context: Context): Agent? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("agent", null)
            ?.let { name -> Agent.entries.firstOrNull { it.name == name } }

    fun folder(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("folder", null) ?: "scratch"

    fun save(context: Context, agent: Agent, folder: String) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putString("agent", agent.name)
            putString("folder", folder)
        }
}

/**
 * The start screen, like Gemini/ChatGPT's empty chat: pick an agent and a folder, then just type.
 * Sending creates the chat; the first message is delivered once the agent is ready.
 */
@Composable
fun NewChatScreen(
    refreshTick: Int,
    onStarted: (ChatSession) -> Unit,
    onLogin: (Agent) -> Unit,
    onInstallAgents: () -> Unit,
) {
    val context = LocalContext.current
    val paths = remember { RuntimePaths(context) }
    // Quick ask (widget, shortcut) focuses the composer once; a later plain "New chat" doesn't.
    val quickAsk = remember { dev.yurlungr.app.widgets.QuickAsk.pending }
    androidx.compose.runtime.LaunchedEffect(Unit) { dev.yurlungr.app.widgets.QuickAsk.pending = false }
    val statuses = remember(refreshTick) { agentStatuses(paths) }
    var agent by rememberSaveable {
        mutableStateOf(
            NewChatPrefs.agent(context)?.takeIf { a -> statuses.any { it.agent == a && it.installed } }
                ?: statuses.firstOrNull { it.installed && it.signedIn == true }?.agent
                ?: statuses.firstOrNull { it.installed }?.agent
                ?: Agent.Claude,
        )
    }
    var folder by rememberSaveable { mutableStateOf(NewChatPrefs.folder(context)) }
    var input by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue("")) }
    val attachments = remember { mutableStateListOf<Attachment>() }
    var choosingFolder by remember { mutableStateOf(false) }
    var skipPermissions by rememberSaveable { mutableStateOf(false) }
    val status = statuses.firstOrNull { it.agent == agent }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding()
            .imePadding(),
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            NavTile(onBack = {})
            Spacer(Modifier.width(8.dp))
            AgentPicker(agent, statuses) { agent = it }
        }

        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(24.dp))
            Appear { ShuffleGreeting() }
            Spacer(Modifier.height(14.dp))
            Appear(delayMillis = 70) {
                Row(
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .background(MaterialTheme.colorScheme.surfaceContainer)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(50))
                        .clickable { choosingFolder = true }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PhIcon(Ph.FolderSimple, null, size = 16.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("~/projects/$folder", style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.width(6.dp))
                    PhIcon(Ph.CaretDown, "Change folder", size = 14.dp)
                }
            }
            AnimatedVisibility(
                visible = status != null && (!status.installed || status.signedIn == false),
                enter = fadeIn(tween(Motion.MEDIUM)) + expandVertically(tween(Motion.MEDIUM, easing = Motion.Emphasized)),
                exit = fadeOut(tween(Motion.SHORT)) + shrinkVertically(tween(Motion.SHORT)),
            ) {
                AgentNotice(status, onLogin = { onLogin(agent) }, onInstall = onInstallAgents)
            }
            Spacer(Modifier.height(12.dp))
            PermissionPicker(skipPermissions) { skipPermissions = it }
            Spacer(Modifier.height(20.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                maxItemsInEachRow = 2,
            ) {
                val tiles = AppColors.tiles
                val palette = listOf(tiles.lime, tiles.lilac, tiles.peach, tiles.sky)
                for ((index, s) in SUGGESTIONS.withIndex()) {
                    val tile = palette[index % palette.size]
                    Appear(delayMillis = 140 + index * 60) {
                        // Bento suggestion tiles, one color each.
                        Column(
                            Modifier
                                .widthIn(min = 150.dp, max = 175.dp)
                                .height(132.dp)
                                .clip(RoundedCornerShape(22.dp))
                                .background(tile.fill)
                                .clickable { input = TextFieldValue(s.prompt, selection = TextRange(s.prompt.length)) }
                                .padding(14.dp),
                            verticalArrangement = Arrangement.SpaceBetween,
                        ) {
                            PhIcon(s.icon, null, size = 22.dp, tint = tile.on)
                            Column {
                                Text(s.title, style = MaterialTheme.typography.titleMedium, fontSize = 15.sp, color = tile.on)
                                Text(s.subtitle, style = MaterialTheme.typography.bodySmall, color = tile.on.copy(alpha = 0.8f), maxLines = 2)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }

        ComposerField(
            value = input,
            onValueChange = { input = it },
            attachments = attachments,
            enabled = status?.installed == true,
            running = false,
            commands = emptyList(),
            acceptsImages = null,
            agentName = agent.displayName,
            autoFocus = quickAsk,
            onSend = { text, files ->
                NewChatPrefs.save(context, agent, folder)
                val chat = ChatSessions.create(context, agent, File(paths.projects, folder), skipPermissions = skipPermissions)
                chat.queue(text, files)
                input = TextFieldValue("")
                attachments.clear()
                onStarted(chat)
            },
            onCancel = {},
        )
    }

    if (choosingFolder) {
        FolderDialog(paths.projects, folder, onDismiss = { choosingFolder = false }) {
            folder = it
            choosingFolder = false
        }
    }
}

@Composable
private fun AgentPicker(agent: Agent, statuses: List<AgentStatus>, onPick: (Agent) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) {
            AgentBadge(agent, size = 24.dp)
            Spacer(Modifier.width(8.dp))
            Text(agent.displayName, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.width(6.dp))
            PhIcon(Ph.CaretDown, "Choose agent", size = 16.dp, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (s in statuses) {
                DropdownMenuItem(
                    leadingIcon = { AgentBadge(s.agent, size = 26.dp) },
                    trailingIcon = { StatusDot(s) },
                    text = {
                        Column {
                            Text(s.agent.displayName, fontWeight = if (s.agent == agent) FontWeight.Bold else FontWeight.Normal)
                            Text(
                                s.agent.vendor + " · " + statusText(s),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    onClick = {
                        open = false
                        onPick(s.agent)
                    },
                )
            }
        }
    }
}

private fun statusText(s: AgentStatus) = when {
    !s.installed -> "not installed"
    s.signedIn == false -> "not signed in"
    s.signedIn == null -> "installed"
    else -> "ready"
}

@Composable
private fun StatusDot(s: AgentStatus) {
    val color = when {
        !s.installed -> MaterialTheme.colorScheme.outline
        s.signedIn == false -> Color(0xFFD29922)
        s.signedIn == null -> MaterialTheme.colorScheme.primary
        else -> Color(0xFF2DA44E)
    }
    Box(Modifier.size(10.dp).clip(CircleShape).background(color))
}

/** Inline hint when the chosen agent can't chat yet. */
@Composable
private fun AgentNotice(status: AgentStatus?, onLogin: () -> Unit, onInstall: () -> Unit) {
    status ?: return
    Spacer(Modifier.height(12.dp))
    Row(
        Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(start = 14.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (!status.installed) "${status.agent.displayName} isn't installed" else "${status.agent.displayName} isn't signed in",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.width(6.dp))
        if (!status.installed) {
            TextButton(onClick = onInstall) { Text("Install") }
        } else {
            TextButton(onClick = onLogin) { Text("Sign in") }
        }
    }
}

@Composable
private fun FolderDialog(projectsDir: File, current: String, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    var name by remember { mutableStateOf(current) }
    val existing = remember { projectsDir.listFiles { f -> f.isDirectory && !f.name.startsWith(".") }?.map { it.name }?.sorted().orEmpty() }
    val valid = Regex("[A-Za-z0-9._-]{1,64}").matches(name.trim())
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Project folder") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    name,
                    { name = it },
                    label = { Text("Folder in ~/projects") },
                    singleLine = true,
                    keyboardOptions = Keyboards.Identifier,
                )
                if (existing.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (p in existing.take(16)) {
                            FilledTonalButton(onClick = { onPick(p) }, contentPadding = PaddingValues(horizontal = 12.dp)) { Text(p) }
                        }
                    }
                }
            }
        },
        confirmButton = { Button(onClick = { onPick(name.trim()) }, enabled = valid) { Text("Use") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
