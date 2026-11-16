package dev.yurlungr.app.chat

import androidx.compose.material3.IconButton
import dev.yurlungr.app.ui.PhIcon
import dev.yurlungr.app.ui.Ph
import dev.yurlungr.app.ui.Microphone
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import dev.yurlungr.app.ui.Motion
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import dev.yurlungr.app.ui.Chip
import dev.yurlungr.app.ui.AppColors
import androidx.compose.foundation.border
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.IconButtonDefaults
import dev.yurlungr.app.ui.MenuChip
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Composer bound to an existing chat. */
@Composable
fun Composer(chat: ChatModel) {
    val appContext = LocalContext.current
    var input by rememberSaveable(chat.id, stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue(Drafts.get(appContext, chat.id))) }
    // Unsent text survives restarts: saved shortly after the last keystroke.
    androidx.compose.runtime.LaunchedEffect(chat.id, input.text) {
        kotlinx.coroutines.delay(400)
        Drafts.set(appContext, chat.id, input.text)
    }
    // Leaving within the debounce would otherwise keep an older draft (or bring back a sent message).
    val latest by androidx.compose.runtime.rememberUpdatedState(input.text)
    androidx.compose.runtime.DisposableEffect(chat.id) {
        onDispose { Drafts.set(appContext, chat.id, latest) }
    }
    val attachments = remember(chat.id) { mutableStateListOf<Attachment>() }
    Column {
    QueueChips(chat)
    ComposerField(
        value = input,
        onValueChange = { input = it },
        attachments = attachments,
        enabled = chat.state == ChatState.Ready || chat.isRunning,
        running = chat.isRunning,
        commands = chat.commands,
        acceptsImages = chat.caps.image,
        agentName = chat.agent.displayName,
        footnote = chat.usage?.let(AcpExtras::usageLabel),
        onSend = { text, files ->
            // While the agent works the message waits its turn; otherwise it goes out now.
            chat.enqueue(text, files)
            Drafts.set(appContext, chat.id, "")
            input = TextFieldValue("")
            attachments.clear()
        },
        onCancel = chat::cancel,
        queueing = true,
        suggestFiles = chat::suggestFiles,
    )
    }
}

/** Messages typed while the agent was busy, in the order they'll be sent. */
@Composable
private fun QueueChips(chat: ChatModel) {
    val queued = chat.queued
    if (queued.isEmpty()) return
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).padding(horizontal = 10.dp).horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PhIcon(Ph.ListChecks, null, size = 16.dp, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        queued.forEachIndexed { i, m ->
            InputChip(
                selected = false,
                onClick = { chat.removeQueued(i) },
                label = { Text("${i + 1}. " + m.text.ifBlank { m.attachments.firstOrNull()?.name.orEmpty() }, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 180.dp)) },
                trailingIcon = { PhIcon(Ph.X, "Remove from queue", size = 14.dp) },
            )
        }
        if (chat.isRunning) TextButton(onClick = chat::sendQueuedNow) { Text("Send now") }
        else if (chat.state == ChatState.Ready) TextButton(onClick = chat::sendQueuedNow) { Text("Send") }
    }
}

/**
 * Message box with attachments (photos/screenshots and files) and slash-command suggestions
 * from the agent's advertised commands. State is hoisted so a new chat can use it before the
 * chat exists.
 */
@Composable
fun ComposerField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    attachments: MutableList<Attachment>,
    enabled: Boolean,
    running: Boolean,
    commands: List<SlashCommand>,
    /** Null when unknown (new chat: the agent hasn't reported its capabilities yet). */
    acceptsImages: Boolean?,
    agentName: String,
    onSend: (String, List<Attachment>) -> Unit,
    onCancel: () -> Unit,
    placeholder: String? = null,
    /** Small text between + and send, e.g. context-window use. */
    footnote: String? = null,
    /** While the agent works, Send adds the message to the queue instead of being replaced by Stop. */
    queueing: Boolean = false,
    /** Project files matching a typed "@query", for mentions. Null: no mentions. */
    suggestFiles: (suspend (String) -> List<String>)? = null,
    /** Focus the message box and raise the keyboard when this turns true (quick ask). */
    autoFocus: Boolean = false,
) {
    val focusRequester = remember { androidx.compose.ui.focus.FocusRequester() }
    androidx.compose.runtime.LaunchedEffect(autoFocus, enabled) {
        if (autoFocus && enabled) runCatching { focusRequester.requestFocus() }
    }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var attachMenu by remember { mutableStateOf(false) }
    var showPrompts by remember { mutableStateOf(false) }
    val speech = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val spoken = r.data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!spoken.isNullOrBlank()) {
            val joined = (if (value.text.isBlank()) "" else value.text.trimEnd() + " ") + spoken
            onValueChange(TextFieldValue(joined, selection = TextRange(joined.length)))
        }
    }
    fun listen() {
        val intent = android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        runCatching { speech.launch(intent) }.onFailure { Toast.makeText(context, "No speech recognition on this phone", Toast.LENGTH_LONG).show() }
    }
    // "@" mention: the word being typed, if it starts with @.
    val cursor = value.selection.end.coerceIn(0, value.text.length)
    val mentionStart = value.text.substring(0, cursor).let { head -> head.lastIndexOfAny(charArrayOf(' ', '\n')) + 1 }
    val mention = value.text.substring(mentionStart, cursor).takeIf { it.startsWith("@") && suggestFiles != null }?.removePrefix("@")
    val fileHits by androidx.compose.runtime.produceState(emptyList<String>(), mention) {
        this.value = if (mention == null) emptyList() else suggestFiles?.invoke(mention).orEmpty()
    }
    if (showPrompts) PromptLibrarySheet(
        current = value.text,
        onPick = { p ->
            val joined = (if (value.text.isBlank()) "" else value.text.trimEnd() + "\n") + p
            onValueChange(TextFieldValue(joined, selection = TextRange(joined.length)))
        },
        onDismiss = { showPrompts = false },
    )

    fun add(uris: List<android.net.Uri>) = scope.launch {
        for (uri in uris) {
            val (attachment, error) = withContext(Dispatchers.IO) {
                runCatching { Attachments.read(context, uri) }.getOrElse { null to "Couldn't read file: ${it.message}" }
            }
            if (attachment != null) attachments += attachment
            if (error != null) Toast.makeText(context, error, Toast.LENGTH_LONG).show()
        }
    }

    val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(5)) { add(it) }
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { add(it) }

    // "/" at the start, no space yet: suggest the agent's commands.
    val slashQuery = value.text.takeIf { it.startsWith("/") && ' ' !in it }?.removePrefix("/")
    val suggestions = slashQuery?.let { q -> commands.filter { it.name.startsWith(q, ignoreCase = true) } }.orEmpty()
    // Keep the last list while the panel animates closed.
    var shownSuggestions by remember { mutableStateOf(suggestions) }
    if (suggestions.isNotEmpty()) shownSuggestions = suggestions

    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).padding(horizontal = 10.dp, vertical = 8.dp)) {
        AnimatedVisibility(
            visible = suggestions.isNotEmpty(),
            enter = fadeIn(tween(Motion.SHORT)) + expandVertically(tween(Motion.MEDIUM, easing = Motion.Emphasized), expandFrom = Alignment.Bottom),
            exit = fadeOut(tween(Motion.SHORT)) + shrinkVertically(tween(Motion.SHORT), shrinkTowards = Alignment.Bottom),
        ) {
            Column {
                LazyColumn(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 220.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                ) {
                    items(shownSuggestions, key = { it.name }) { cmd ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    val text = "/${cmd.name} "
                                    onValueChange(TextFieldValue(text, selection = TextRange(text.length)))
                                }
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                        ) {
                            Text("/${cmd.name}" + (cmd.hint?.let { " <$it>" } ?: ""), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
                            if (cmd.description.isNotBlank()) {
                                Text(
                                    cmd.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
            }
        }

        if (mention != null && fileHits.isNotEmpty()) {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh),
            ) {
                for (path in fileHits) {
                    Text(
                        path,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.StartEllipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                val text = value.text.replaceRange(mentionStart, cursor, "@$path ")
                                onValueChange(TextFieldValue(text, selection = TextRange(mentionStart + path.length + 2)))
                            }
                            .padding(horizontal = 16.dp, vertical = 9.dp),
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
        }

        // One rounded "pill" like Gemini/ChatGPT: attachments, text, then + and send in a row.
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(28.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(28.dp))
                .animateContentSize(tween(Motion.MEDIUM, easing = Motion.Emphasized))
                .padding(horizontal = 6.dp, vertical = 4.dp),
        ) {
            if (attachments.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    for (a in attachments.toList()) {
                        InputChip(
                            selected = false,
                            onClick = { attachments.remove(a) },
                            label = { Text(a.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            leadingIcon = { PhIcon(if (a.isImage) Ph.Image else Ph.Paperclip, null, size = 16.dp) },
                            trailingIcon = { PhIcon(Ph.X, "Remove", size = 14.dp) },
                        )
                    }
                }
                if (attachments.any { it.isImage } && acceptsImages == false) {
                    Text(
                        "$agentName doesn't take images directly; it gets the file path instead.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 10.dp),
                    )
                }
            }
            TextField(
                value = value,
                onValueChange = onValueChange,
                placeholder = { Text(placeholder ?: if (commands.isEmpty()) "Ask $agentName" else "Ask $agentName, or / for commands") },
                maxLines = 6,
                enabled = enabled,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                ),
                modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box {
                    IconButton(
                        onClick = { attachMenu = true },
                        enabled = enabled,
                        colors = IconButtonDefaults.iconButtonColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                    ) { PhIcon(Ph.Plus, "Attach", size = 20.dp) }
                    DropdownMenu(expanded = attachMenu, onDismissRequest = { attachMenu = false }) {
                        DropdownMenuItem(leadingIcon = { PhIcon(Ph.Image, null) }, text = { Text("Photo or screenshot") }, onClick = {
                            attachMenu = false
                            pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        })
                        DropdownMenuItem(leadingIcon = { PhIcon(Ph.Paperclip, null) }, text = { Text("File") }, onClick = {
                            attachMenu = false
                            pickFiles.launch(arrayOf("*/*"))
                        })
                        DropdownMenuItem(leadingIcon = { PhIcon(Ph.ListChecks, null) }, text = { Text("Prompts") }, onClick = {
                            attachMenu = false
                            showPrompts = true
                        })
                    }
                }
                IconButton(onClick = ::listen, enabled = enabled) { PhIcon(Ph.Microphone, "Speak", size = 20.dp) }
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    if (footnote != null) {
                        Text(footnote, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                }
                AnimatedContent(
                    // Stop only when there's nothing to queue; typed text turns it into a queueing Send.
                    targetState = running && !(queueing && (value.text.isNotBlank() || attachments.isNotEmpty())),
                    transitionSpec = {
                        (scaleIn(tween(Motion.MEDIUM, easing = Motion.Emphasized), initialScale = 0.6f) + fadeIn(tween(Motion.SHORT)))
                            .togetherWith(scaleOut(tween(Motion.SHORT), targetScale = 0.6f) + fadeOut(tween(Motion.SHORT)))
                    },
                    label = "send-stop",
                ) { running ->
                    if (running) {
                        FilledTonalButton(onClick = onCancel, shape = CircleShape, modifier = Modifier.size(44.dp), contentPadding = PaddingValues(0.dp)) {
                            PhIcon(Ph.Stop, "Stop", size = 18.dp)
                        }
                    } else {
                        val lime = AppColors.tiles.lime
                        Button(
                            onClick = { onSend(value.text, attachments.toList()) },
                            enabled = enabled && (value.text.isNotBlank() || attachments.isNotEmpty()),
                            shape = CircleShape,
                            colors = ButtonDefaults.buttonColors(containerColor = lime.fill, contentColor = lime.on),
                            modifier = Modifier.size(44.dp),
                            contentPadding = PaddingValues(0.dp),
                        ) { PhIcon(if (running) Ph.ListChecks else Ph.ArrowUp, if (running) "Add to queue" else "Send", size = 20.dp) }
                    }
                }
            }
        }
    }
}

/**
 * The agent's session settings (model, reasoning level, mode, …) as a row of pill pickers under
 * the top bar: "Sonnet 5.5 ⌄", "Effort: High ⌄", "Auto ⌄".
 */
@Composable
fun OptionsBar(chat: ChatModel) {
    // An agent that reports both ACP modes and a "mode" config option gets one picker, not two.
    val options = chat.configOptions.filter { !(it.category == "mode" && chat.modes.isNotEmpty()) }
    AnimatedVisibility(
        visible = options.isNotEmpty() || chat.modes.isNotEmpty(),
        enter = fadeIn(tween(Motion.MEDIUM)) + expandVertically(tween(Motion.MEDIUM, easing = Motion.Emphasized)),
        exit = fadeOut(tween(Motion.SHORT)) + shrinkVertically(tween(Motion.SHORT)),
    ) { OptionsRow(chat, options) }
}

@Composable
private fun OptionsRow(chat: ChatModel, options: List<ConfigOption>) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(start = 14.dp, end = 14.dp, top = 2.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (option in options) {
            if (option.isBoolean) {
                Chip(option.name, selected = option.booleanValue == true, onClick = { chat.setConfigOption(option.id, option.booleanValue != true) })
            } else {
                // The model speaks for itself; other settings keep their name for context.
                val label = if (option.category == "model") option.currentName else "${option.name}: ${option.currentName}"
                MenuChip(label) { close ->
                    for (choice in option.choices) {
                        DropdownMenuItem(
                            text = { ChoiceText(choice.name, choice.description, choice.value == option.currentValue) },
                            onClick = {
                                close()
                                if (choice.value != option.currentValue) chat.setConfigOption(option.id, choice.value)
                            },
                        )
                    }
                }
            }
        }
        if (chat.modes.isNotEmpty()) {
            MenuChip(chat.modes.firstOrNull { it.id == chat.currentModeId }?.name ?: "Mode") { close ->
                for (mode in chat.modes) {
                    DropdownMenuItem(
                        text = { ChoiceText(mode.name, mode.description, mode.id == chat.currentModeId) },
                        onClick = {
                            close()
                            if (mode.id != chat.currentModeId) chat.setMode(mode.id)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ChoiceText(name: String, description: String?, current: Boolean) {
    Column {
        Text(name, style = MaterialTheme.typography.bodyMedium, fontWeight = if (current) FontWeight.Bold else FontWeight.Normal)
        description?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
fun AttachmentLabels(names: List<String>) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
        for (n in names.take(5)) {
            Row(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                    .width(130.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PhIcon(Ph.Paperclip, null, size = 12.dp)
                Spacer(Modifier.width(4.dp))
                Text(n, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
