package dev.yurlungr.app.chat

import androidx.compose.material3.IconButton
import dev.yurlungr.app.ui.PhIcon
import dev.yurlungr.app.ui.Ph
import dev.yurlungr.app.ui.WaveDots
import androidx.compose.ui.unit.IntOffset
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.CircleShape
import dev.yurlungr.app.ui.Motion
import dev.yurlungr.app.ui.SkeletonChat
import dev.yurlungr.app.ui.NavButton
import dev.yurlungr.app.ui.NavTile
import dev.yurlungr.app.ui.HeaderTile
import dev.yurlungr.app.ui.AppColors
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.yurlungr.app.agents.Agent
import dev.yurlungr.app.runtime.RuntimeBridge

@Composable
fun ChatScreen(
    chat: ChatModel,
    onBack: () -> Unit,
    onLogin: (Agent) -> Unit,
    onNewChat: (() -> Unit)? = null,
    /** Reruns the agents setup (offered when the agent couldn't start because something's missing). */
    onInstallAgents: (() -> Unit)? = null,
    onSystemChecks: (() -> Unit)? = null,
    /** Opens another chat by id (the fresh chat a full context continues in). */
    onOpenChat: ((String) -> Unit)? = null,
) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val freshChat: (() -> Unit)? = if (chat is ChatSession && onOpenChat != null) {
        { onOpenChat(ChatSessions.continueFresh(context, chat).id) }
    } else null
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = chat.items.size)
    // A saved chat restarts its agent (and restores its context) when opened.
    LaunchedEffect(chat.id) { chat.resume() }
    val voice = dev.yurlungr.app.voice.rememberVoiceChat(chat)

    // Follow the conversation while the user is really at the end. Scrolling up (a drag) stops
    // following until they come back to the end, so a long streaming reply can be read from its top.
    var follow by remember(chat.id) { mutableStateOf(true) }
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { i ->
            when (i) {
                is androidx.compose.foundation.interaction.DragInteraction.Start -> follow = false
                is androidx.compose.foundation.interaction.DragInteraction.Stop,
                is androidx.compose.foundation.interaction.DragInteraction.Cancel -> if (!listState.canScrollForward) follow = true
            }
        }
    }
    LaunchedEffect(listState) {
        // A fling that ends at the bottom.
        snapshotFlow { listState.canScrollForward }.collect { if (!it) follow = true }
    }
    // Read inside a flow, not here: streamed text must not recompose the whole screen per chunk.
    LaunchedEffect(chat) {
        snapshotFlow {
            val last = chat.items.lastOrNull()
            Triple(chat.items.size, ((last as? ChatItem.AgentText)?.text?.length ?: 0) / 120, chat.state)
        }.collect {
            val sentByUser = chat.items.lastOrNull() is ChatItem.User
            if (sentByUser) follow = true
            if (follow) listState.scrollToItem(chat.items.size) // footer item
        }
    }
    // The reply being written, if any: only it is parsed while it streams (throttled, see MarkdownText).
    val streamingKey by remember(chat) {
        derivedStateOf { if (chat.isRunning) (chat.items.lastOrNull() as? ChatItem.AgentText)?.key else null }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding()
            .imePadding(),
    ) {
        ChatTopBar(
            chat,
            onBack = onBack,
            onNewChat = onNewChat,
            onFreshChat = freshChat,
            voice = voice,
            onStop = chat::endSession,
            onDelete = {
                chat.delete()
                onBack()
            },
        )
        OptionsBar(chat)

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(chat.items, key = { it.key }) { item ->
                // New messages fade/slide in; existing ones glide when the list reflows.
                Box(Modifier.animateItem(fadeInSpec = tween(Motion.MEDIUM), placementSpec = tween(Motion.MEDIUM, easing = Motion.Emphasized))) {
                    ChatItemView(chat, item, voice, streaming = item.key == streamingKey)
                }
            }
            item(key = "footer") { ChatFooter(chat, onLogin, onInstallAgents, onSystemChecks) }
        }

        dev.yurlungr.app.voice.VoiceBar(voice)
        Composer(chat, onFreshChat = freshChat)
    }
}

/**
 * ☰ · centered "● Agent · Model ⌄" pill · new chat. The pill's menu holds the chat's details
 * (title, folder, state, context use) and the session actions.
 */
@Composable
private fun ChatTopBar(chat: ChatModel, onBack: () -> Unit, onNewChat: (() -> Unit)?, onFreshChat: (() -> Unit)?, voice: dev.yurlungr.app.voice.VoiceChat, onStop: () -> Unit, onDelete: () -> Unit) {
    var sheet by remember { mutableStateOf<String?>(null) }
    val export = rememberExportChat(chat)
    var exportMenu by remember { mutableStateOf(false) }
    if (sheet == "rename" && chat is ChatSession) RenameChatDialog(chat.title, onRename = chat::rename, onDismiss = { sheet = null })
    when (sheet) {
        "checkpoints" -> (chat as? ChatSession)?.let { CheckpointsSheet(it) { sheet = null } }
        "usage" -> UsageSheet(chat) { sheet = null }
        "artifacts" -> ArtifactsSheet(chat) { sheet = null }
        "rules" -> (chat as? ChatSession)?.let { ApprovalRulesSheet(it.cwd.path) { sheet = null } }
    }
    var menu by remember { mutableStateOf(false) }
    val model = chat.configOptions.firstOrNull { it.category == "model" }?.currentName?.takeIf { it.isNotBlank() }
    val lilac = AppColors.tiles.lilac
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NavTile(onBack)
        Spacer(Modifier.width(8.dp))
        // The agent pill: a lilac Bento tile.
        Box(Modifier.weight(1f)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(46.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(lilac.fill)
                    .clickable { menu = true }
                    .padding(horizontal = 14.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusDot(chat.state)
                Spacer(Modifier.width(8.dp))
                Text(
                    listOfNotNull(chat.agent.displayName, model).joinToString(" · "),
                    style = MaterialTheme.typography.titleMedium,
                    fontSize = 15.sp,
                    color = lilac.on,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(6.dp))
                PhIcon(Ph.CaretDown, "Chat details", size = 12.dp, tint = lilac.on)
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).widthIn(max = 280.dp)) {
                    Text(chat.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOfNotNull(chat.cwdLabel, stateLabel(chat.state), if (chat.skipPermissions) "Skip permissions enabled" else null, chat.usage?.let(AcpExtras::usageLabel)).joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (chat is ChatSession) {
                    DropdownMenuItem(
                        leadingIcon = { PhIcon(Ph.PencilSimple, null, size = 18.dp) },
                        text = { Text("Rename") },
                        onClick = { menu = false; sheet = "rename" },
                    )
                    DropdownMenuItem(
                        leadingIcon = { PhIcon(Ph.PushPin, null, size = 18.dp) },
                        text = { Text(if (chat.pinned) "Unpin" else "Pin to top") },
                        onClick = { menu = false; chat.pin(!chat.pinned) },
                    )
                }
                DropdownMenuItem(
                    leadingIcon = { PhIcon(Ph.DownloadSimple, null, size = 18.dp) },
                    text = { Text("Export chat…") },
                    enabled = chat.items.isNotEmpty(),
                    onClick = { menu = false; exportMenu = true },
                )
                if (onFreshChat != null) DropdownMenuItem(
                    leadingIcon = { PhIcon(Ph.ArrowsLeftRight, null, size = 18.dp) },
                    text = { Text("Continue in a new chat") },
                    enabled = chat.items.any { it is ChatItem.User },
                    onClick = { menu = false; onFreshChat() },
                )
                if (chat is ChatSession) DropdownMenuItem(
                    leadingIcon = { PhIcon(Ph.ClockCounterClockwise, null, size = 18.dp) },
                    text = { Text("Checkpoints") },
                    onClick = { menu = false; sheet = "checkpoints" },
                )
                if (chat is ChatSession) DropdownMenuItem(
                    leadingIcon = { PhIcon(Ph.ShieldCheck, null, size = 18.dp) },
                    text = { Text("Approval rules") },
                    onClick = { menu = false; sheet = "rules" },
                )
                dev.yurlungr.app.ui.LocalOpenExtensions.current?.let { openExt ->
                    DropdownMenuItem(
                        leadingIcon = { PhIcon(Ph.Wrench, null, size = 18.dp) },
                        text = { Text("Skills & commands") },
                        onClick = { menu = false; openExt(chat.agent.name, (chat as? ChatSession)?.cwd?.path) },
                    )
                }
                DropdownMenuItem(
                    leadingIcon = { PhIcon(Ph.File, null, size = 18.dp) },
                    text = { Text("Artifacts") },
                    onClick = { menu = false; sheet = "artifacts" },
                )
                DropdownMenuItem(
                    leadingIcon = { PhIcon(Ph.Lightning, null, size = 18.dp) },
                    text = { Text("Usage") },
                    onClick = { menu = false; sheet = "usage" },
                )
                dev.yurlungr.app.voice.VoiceMenuItems(voice) { menu = false }
                DropdownMenuItem(
                    leadingIcon = { PhIcon(Ph.Stop, null, size = 18.dp) },
                    text = { Text("End session") },
                    enabled = chat.state != ChatState.Saved,
                    onClick = {
                        menu = false
                        onStop()
                    },
                )
                DropdownMenuItem(
                    leadingIcon = { PhIcon(Ph.Trash, null, size = 18.dp, tint = MaterialTheme.colorScheme.error) },
                    text = { Text("Delete chat", color = MaterialTheme.colorScheme.error) },
                    onClick = {
                        menu = false
                        onDelete()
                    },
                )
            }
            DropdownMenu(expanded = exportMenu, onDismissRequest = { exportMenu = false }) {
                Text(
                    "Export as",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
                for (format in ExportFormat.entries) DropdownMenuItem(
                    text = { Text(format.label + if (format == ExportFormat.Markdown) " (to read)" else " (for tools)") },
                    onClick = { exportMenu = false; export(format) },
                )
            }
        }
        if (onNewChat != null) {
            Spacer(Modifier.width(8.dp))
            HeaderTile(Ph.Plus, "New chat", onNewChat, fill = MaterialTheme.colorScheme.primary, tint = MaterialTheme.colorScheme.onPrimary)
        }
    }
}

/** Green when ready, accent while busy, amber when it needs you, red when stopped. */
@Composable
private fun StatusDot(state: ChatState) {
    // Drawn on the lilac pill, so dark, saturated dots read best in both themes.
    val color = when (state) {
        ChatState.Ready -> Color(0xFF15803D)
        ChatState.Starting, ChatState.Running -> AppColors.tiles.lilac.on
        ChatState.AuthRequired -> Color(0xFFC2410C)
        is ChatState.Failed -> Color(0xFFB91C1C)
        ChatState.Saved -> AppColors.tiles.lilac.on.copy(alpha = 0.45f)
    }
    val busy = state == ChatState.Starting || state == ChatState.Running
    val alpha = if (busy) {
        val t = rememberInfiniteTransition(label = "dot")
        t.animateFloat(
            initialValue = 1f, targetValue = 1f,
            animationSpec = infiniteRepeatable(keyframes { durationMillis = 1200; 0.35f at 600 }),
            label = "dot-alpha",
        ).value
    } else 1f
    Box(Modifier.size(8.dp).clip(CircleShape).background(color.copy(alpha = alpha)))
}

private fun stateLabel(state: ChatState) = when (state) {
    ChatState.Saved -> "session ended"
    ChatState.Starting -> "starting…"
    ChatState.Ready -> "ready"
    ChatState.Running -> "working…"
    ChatState.AuthRequired -> "sign-in needed"
    is ChatState.Failed -> "stopped"
}

@Composable
private fun ChatItemView(chat: ChatModel, item: ChatItem, voice: dev.yurlungr.app.voice.VoiceChat, streaming: Boolean = false) {
    when (item) {
        is ChatItem.User -> UserBubble(item)
        is ChatItem.AgentText -> Column {
            SelectionContainer {
                // The text is read inside MarkdownStream (not here) while it streams, so a chunk doesn't recompose this.
                if (streaming) MarkdownStream({ item.text }, allowLocalFiles = !chat.isRemote)
                else MarkdownText(item.text, allowLocalFiles = !chat.isRemote)
            }
            dev.yurlungr.app.voice.ReadAloudButton(voice, item)
        }
        is ChatItem.Thought -> ThoughtView(item)
        is ChatItem.ToolCall -> ToolCallCard(item, chat)
        is ChatItem.Plan -> PlanCard(item)
        is ChatItem.Permission -> PermissionCard(item, chat.localCwd?.path, onAlways = if (chat is ChatSession && item.toolKind in ApprovalRules.kinds) ({ chat.alwaysAllow(item) }) else null) { option -> chat.answerPermission(item, option) }
        is ChatItem.Notice -> Text(
            item.text,
            style = MaterialTheme.typography.labelMedium,
            color = if (item.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun UserBubble(item: ChatItem.User) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    var menu by remember { mutableStateOf(false) }
    fun copy() {
        val cm = context.getSystemService(android.content.ClipboardManager::class.java)
        cm?.setPrimaryClip(android.content.ClipData.newPlainText("Message", item.text))
        android.widget.Toast.makeText(context, "Copied", android.widget.Toast.LENGTH_SHORT).show()
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Box {
            // Long-press copies the whole message (a text selection inside a bubble was swallowed by the list).
            Column(
                Modifier
                    .widthIn(max = 320.dp)
                    .clip(RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .combinedClickable(
                        enabled = item.text.isNotBlank(),
                        onClick = {},
                        onLongClick = { haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress); menu = true },
                    )
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                if (item.text.isNotBlank()) {
                    Text(item.text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
                if (item.attachments.isNotEmpty()) AttachmentLabels(item.attachments)
            }
            androidx.compose.material3.DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                androidx.compose.material3.DropdownMenuItem(text = { Text("Copy") }, onClick = { menu = false; copy() })
            }
        }
    }
}

@Composable
private fun ThoughtView(item: ChatItem.Thought) {
    var expanded by remember { mutableStateOf(false) }
    Text(
        if (expanded) item.text.trim() else "Thinking · " + item.text.trim().lineSequence().firstOrNull().orEmpty(),
        style = MaterialTheme.typography.bodySmall,
        fontStyle = FontStyle.Italic,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = if (expanded) Int.MAX_VALUE else 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
            .animateContentSize(),
    )
}

/** Opens a file in the editor at a line (1-based; null for the top). Null outside the app shell. */
val LocalOpenFileAt = androidx.compose.runtime.compositionLocalOf<((java.io.File, Int?) -> Unit)?> { null }

/** A tool call's `path` or `path:line` location, split; relative paths are taken from [cwd]. */
internal fun parseLocation(location: String, cwd: java.io.File?): Pair<java.io.File, Int?>? {
    val m = Regex("^(.*?)(?::(\\d+))?$").find(location) ?: return null
    val path = m.groupValues[1].ifEmpty { return null }
    val line = m.groupValues[2].toIntOrNull()?.takeIf { it > 0 }
    val f = java.io.File(path)
    val file = if (f.isAbsolute) f else cwd?.let { java.io.File(it, path) } ?: return null
    return file to line
}

/** The tool call's locations, each one tappable when it's a file on this phone. */
@Composable
private fun ToolLocations(locations: List<String>, chat: ChatModel) {
    val openAt = LocalOpenFileAt.current
    val cwd = chat.localCwd
    val context = LocalContext.current
    val style = MaterialTheme.typography.labelSmall
    if (openAt == null || cwd == null) {
        Text(
            locations.joinToString(", "), style = style, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 28.dp, top = 2.dp),
        )
        return
    }
    FlowRow(Modifier.padding(start = 28.dp, top = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (loc in locations.take(6)) {
            Text(
                loc.substringAfterLast('/').ifEmpty { loc },
                style = style,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .widthIn(max = 220.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .clickable {
                        val (file, line) = parseLocation(loc, cwd) ?: return@clickable
                        if (file.isFile) openAt(file, line)
                        else android.widget.Toast.makeText(context, "Not found: ${file.path}", android.widget.Toast.LENGTH_SHORT).show()
                    }
                    .padding(vertical = 2.dp),
            )
        }
        if (locations.size > 6) Text("+${locations.size - 6}", style = style, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Output of a command the agent runs through an ACP terminal: live, with Stop while it runs. */
@Composable
private fun TerminalBlock(content: ToolContent.Terminal, chat: ChatModel) {
    val live = remember(content.terminalId) { chat.terminal(content.terminalId) }
    val text = live?.text ?: content.output
    val running = live?.running == true
    val exit = live?.exit?.label ?: content.exitLabel
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PhIcon(Ph.Terminal, null, size = 14.dp, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(6.dp))
            Text(
                when {
                    running -> "Running…"
                    exit != null -> exit
                    chat.isRemote -> "Runs on the other phone"
                    else -> "Command output"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (running) TextButton(onClick = { chat.killTerminal(content.terminalId) }) {
                Text("Stop", color = MaterialTheme.colorScheme.error)
            }
        }
        if (!text.isNullOrEmpty()) ToolOutputBlock(text)
    }
}

@Composable
private fun ToolCallCard(item: ChatItem.ToolCall, chat: ChatModel) {
    // A running command's output is worth seeing without a tap.
    var userExpanded by remember { mutableStateOf<Boolean?>(null) }
    val expanded = userExpanded ?: (item.content.any { it is ToolContent.Terminal } && item.status != "completed" && item.status != "failed")
    val hasDetail = item.content.isNotEmpty()
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth().animateContentSize(),
    ) {
        Column(
            Modifier
                .clickable(enabled = hasDetail) { userExpanded = !expanded }
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PhIcon(kindIcon(item.kind), kindLabel(item.kind), size = 18.dp, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Text(
                    item.title.ifBlank { item.toolCallId },
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = if (expanded) 6 else 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                ToolStatus(item.status)
            }
            if (item.locations.isNotEmpty()) ToolLocations(item.locations, chat)
            // Folded: a failed call still shows what went wrong.
            val errorLine = if (!expanded && item.status == "failed") remember(item.content) { firstErrorLine(item) } else null
            if (errorLine != null) {
                Text(
                    errorLine,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 28.dp, top = 2.dp),
                )
            }
            if (expanded) {
                Spacer(Modifier.height(8.dp))
                for (c in item.content) {
                    when (c) {
                        is ToolContent.Text -> ToolOutputBlock(c.text)
                        is ToolContent.Diff -> DiffView(c)
                        is ToolContent.Terminal -> TerminalBlock(c, chat)
                    }
                    Spacer(Modifier.height(6.dp))
                }
            }
        }
    }
}

private fun kindLabel(kind: String) = when (kind) {
    "read" -> "Read"
    "edit" -> "Edit"
    "delete" -> "Delete"
    "move" -> "Move"
    "search" -> "Search"
    "execute" -> "Run"
    "think" -> "Think"
    "fetch" -> "Fetch"
    else -> "Tool"
}

private fun kindIcon(kind: String) = when (kind) {
    "read" -> Ph.FileText
    "edit" -> Ph.PencilSimple
    "delete" -> Ph.Trash
    "move" -> Ph.ArrowsLeftRight
    "search" -> Ph.MagnifyingGlass
    "execute" -> Ph.Terminal
    "think" -> Ph.Brain
    "fetch" -> Ph.Globe
    else -> Ph.Wrench
}

@Composable
private fun ToolStatus(status: String) {
    when (status) {
        "pending", "in_progress" -> WaveDots(dotSize = 4.dp)
        "completed" -> PhIcon(Ph.CheckCircle, "Done", size = 18.dp, tint = Color(0xFF2DA44E))
        "failed" -> PhIcon(Ph.XCircle, "Failed", size = 18.dp, tint = MaterialTheme.colorScheme.error)
    }
}

/** Shows only the changed region (common prefix/suffix lines trimmed). */
@Composable
private fun DiffView(diff: ToolContent.Diff) {
    val lines = remember(diff) { ChatExport.changedLines(diff) }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(Color(0xFF0D1117))
            .padding(8.dp),
    ) {
        for (line in lines) {
            Text(
                line,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                lineHeight = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = when {
                    line.startsWith("@@") -> Color(0xFF79C0FF)
                    line.startsWith("+") -> Color(0xFF7EE787)
                    line.startsWith("-") -> Color(0xFFFF7B72)
                    else -> Color(0xFFE6EDF3)
                },
            )
        }
    }
}

@Composable
private fun PlanCard(item: ChatItem.Plan) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Plan", style = MaterialTheme.typography.labelLarge)
            for (entry in item.entries) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PhIcon(
                        when (entry.status) {
                            "completed" -> Ph.CheckCircle
                            "in_progress" -> Ph.CircleHalf
                            else -> Ph.Circle
                        },
                        entry.status,
                        size = 16.dp,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        entry.content,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (entry.status == "completed") MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

@Composable
private fun PermissionCard(item: ChatItem.Permission, cwd: String?, onAlways: (() -> Unit)? = null, onChoose: (PermissionOption) -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
        modifier = Modifier.fillMaxWidth().animateContentSize(),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Permission needed", style = MaterialTheme.typography.labelLarge)
            Text(item.toolTitle, style = MaterialTheme.typography.bodyMedium)
            PermissionDetailsView(item.details, cwd, open = item.resolved == null)
            val resolved = item.resolved
            if (resolved != null) {
                Text("→ $resolved", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
            } else {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (option in item.options) {
                        if (option.isAllow) {
                            Button(onClick = { onChoose(option) }) { Text(option.name) }
                        } else {
                            OutlinedButton(onClick = { onChoose(option) }) { Text(option.name) }
                        }
                    }
                    if (onAlways != null) androidx.compose.material3.TextButton(onClick = onAlways) {
                        Text("Always allow ${ApprovalRules.label(item.toolKind ?: "").lowercase()} here")
                    }
                }
            }
        }
    }
}

/**
 * What a prompt approves: the command, the files, and the changes (folded; tap to see them).
 * Open prompts show the command and files at once; answered ones keep them behind a tap.
 */
@Composable
private fun PermissionDetailsView(details: PermissionDetails, cwd: String?, open: Boolean) {
    if (details.isEmpty) return
    var expanded by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    val command = details.command
    if (command != null && (open || expanded)) {
        Text(
            command,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            color = Color(0xFFE6EDF3),
            maxLines = if (expanded) 40 else 4,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).background(Color(0xFF0D1117))
                .clickable { expanded = !expanded }.padding(8.dp),
        )
    }
    val files = (details.paths + details.diffs.map { it.path }).distinct()
    if (files.isNotEmpty() && (open || expanded)) {
        val shown = if (expanded) files else files.take(3)
        Text(
            shown.joinToString("\n") { PermissionDetails.relativeTo(it, cwd) } + if (shown.size < files.size) "\n… and ${files.size - shown.size} more" else "",
            style = MaterialTheme.typography.labelMedium,
            fontFamily = FontFamily.Monospace,
            color = colors.onTertiaryContainer,
        )
    }
    val changes = details.diffs.isNotEmpty()
    if (changes || !open || files.size > 3) {
        Text(
            when {
                expanded -> "Hide details"
                changes -> "Show changes (${details.diffs.size} file${if (details.diffs.size == 1) "" else "s"})"
                else -> "Show details"
            },
            style = MaterialTheme.typography.labelMedium,
            color = colors.primary,
            modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { expanded = !expanded }.padding(vertical = 2.dp),
        )
    }
    if (expanded) for (d in details.diffs) {
        Text(PermissionDetails.relativeTo(d.path, cwd), style = MaterialTheme.typography.labelSmall, color = colors.onTertiaryContainer)
        DiffView(d)
    }
}

@Composable
private fun ChatFooter(chat: ChatModel, onLogin: (Agent) -> Unit, onInstallAgents: (() -> Unit)?, onSystemChecks: (() -> Unit)?) {
    // Crossfade between states (starting → ready → working → …) instead of jumping.
    AnimatedContent(
        targetState = chat.state,
        contentKey = { it::class },
        transitionSpec = { (fadeIn(tween(Motion.MEDIUM)) + expandVertically(tween(Motion.MEDIUM))).togetherWith(fadeOut(tween(Motion.SHORT))) },
        label = "chat-footer",
    ) { state -> FooterContent(chat, state, onLogin, onInstallAgents, onSystemChecks) }
}

@Composable
private fun FooterContent(
    chat: ChatModel,
    state: ChatState,
    onLogin: (Agent) -> Unit,
    onInstallAgents: (() -> Unit)?,
    onSystemChecks: (() -> Unit)?,
) {
    val context = LocalContext.current
    when (state) {
        ChatState.Saved -> StatusCard(
            title = "Session ended",
            body = "${chat.agent.displayName} isn't running. Resume to continue where you left off.",
        ) {
            Button(onClick = chat::resume) { Text("Resume") }
        }
        ChatState.Starting -> if (chat.items.isEmpty()) {
            // Skeleton of the conversation that's about to appear.
            SkeletonChat(Modifier.padding(vertical = 8.dp))
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
                WaveDots()
                Spacer(Modifier.width(10.dp))
                // A queued first message alone means a fresh chat; agent output means we're resuming.
                val resuming = chat.items.any { it !is ChatItem.User }
                Text(
                    (if (resuming) "Reconnecting to " else "Starting ") + "${chat.agent.displayName}…",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        ChatState.Running -> Box(Modifier.padding(vertical = 6.dp)) { WaveDots() }
        ChatState.AuthRequired -> StatusCard(
            title = "${chat.agent.displayName} needs you to sign in",
            body = if (chat.isRemote) {
                "Sign in on the host phone: Yurlungr → ☰ → Agents → ${chat.agent.displayName} → Sign in. Then tap “I've signed in”."
            } else {
                chat.agent.loginHint
            },
        ) {
            if (!chat.isRemote) Button(onClick = { onLogin(chat.agent) }) { Text("Sign in (terminal)") }
            FilledTonalButton(onClick = chat::restart) { Text("I've signed in") }
            (chat.loginUrl ?: RuntimeBridge.lastUrl)?.let { url ->
                TextButton(onClick = { RuntimeBridge.open(context, url) }) { Text("Open sign-in link") }
            }
        }
        is ChatState.Failed -> {
            // Restarting can't help when the agent (or Node, or a library) is missing: offer the fix too.
            val missing = !chat.isRemote && failureLooksLikeMissingTool(state.message)
            StatusCard(
                title = "${chat.agent.displayName} stopped",
                body = if (missing) state.message + "\n\nThe agent or something it needs isn't installed or can't run." else state.message,
                isError = true,
            ) {
                if (missing && onInstallAgents != null) Button(onClick = onInstallAgents) { Text("Reinstall agents") }
                if (missing) FilledTonalButton(onClick = chat::restart) { Text("Restart") } else Button(onClick = chat::restart) { Text("Restart") }
                if (missing && onSystemChecks != null) TextButton(onClick = onSystemChecks) { Text("System checks") }
            }
        }
        ChatState.Ready -> if (chat.items.isEmpty()) {
            Text(
                "Ask ${chat.agent.displayName} anything about ${chat.folderName}. It works in this folder and asks before risky actions.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 12.dp),
            )
        }
    }
}

@Composable
private fun StatusCard(title: String, body: String, isError: Boolean = false, actions: @Composable () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            SelectionContainer { Text(body, style = MaterialTheme.typography.bodySmall) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { actions() }
        }
    }
}
