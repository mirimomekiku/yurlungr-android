package dev.yurlungr.app.voice

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.yurlungr.app.chat.ChatItem
import dev.yurlungr.app.chat.ChatModel
import dev.yurlungr.app.chat.ChatState
import dev.yurlungr.app.ui.AppColors
import dev.yurlungr.app.ui.Ph
import dev.yurlungr.app.ui.PhIcon
import dev.yurlungr.app.ui.Microphone

/**
 * Voice for one open chat: reading replies aloud and the hands-free loop (speak the reply →
 * listen → send → wait for the next reply). Lives only while the chat screen is shown; leaving
 * the screen or the app ends hands-free and stops this chat's speech. Main thread only.
 */
@Stable
class VoiceChat internal constructor(val chat: ChatModel, private val context: Context) {
    enum class Phase { Idle, Speaking, Listening, Waiting }

    var handsFree by mutableStateOf(false)
        private set
    var phase by mutableStateOf(Phase.Idle)
        private set

    /** Starts the system speech recognizer; set by [rememberVoiceChat]. Throws when there's none. */
    internal var launchRecognizer: ((Intent) -> Unit)? = null
    /** The recognizer's screen is up: our activity may stop meanwhile, and that isn't leaving. */
    internal var recognizing = false
    internal var visible = true

    private val prefix = "chat:${chat.id}:"
    private val handsFreeOwner = prefix + "hands-free"
    /** The permission prompt the current listen answers; null when listening for a message. */
    private var answering: ChatItem.Permission? = null
    private var retried = false
    /** Prompts already spoken, so one left for the user isn't asked again and again. */
    private val asked = HashSet<Long>()

    val readAloud: Boolean get() = VoicePrefs.readsAloud(chat.id)
    val speaking: Boolean get() = Speaker.isSpeaking(prefix)

    fun setReadAloud(on: Boolean) {
        VoicePrefs.setReadAloud(context, chat.id, on)
        if (!on) Speaker.stop(prefix + "turn")
    }

    /** The last agent text of each turn: the reply worth a read-aloud button. */
    private val replyEnds by derivedStateOf {
        val out = HashSet<Long>()
        var open = true
        val items = chat.items
        for (i in items.indices.reversed()) {
            when (val item = items[i]) {
                is ChatItem.User -> open = true
                is ChatItem.AgentText -> if (open) { out += item.key; open = false }
                else -> Unit
            }
        }
        out
    }

    fun canRead(item: ChatItem.AgentText): Boolean {
        if (item.key !in replyEnds || item.text.isBlank()) return false
        // Still streaming: reading half a reply helps no one.
        return !(chat.isRunning && chat.items.lastOrNull { it is ChatItem.AgentText } === item)
    }

    fun isReading(item: ChatItem.AgentText) = Speaker.owner == prefix + "item:" + item.key

    fun read(item: ChatItem.AgentText) {
        if (isReading(item)) return Speaker.stop()
        Speaker.speak(context, SpeakableText.fromMarkdown(item.text), prefix + "item:" + item.key)
    }

    /** Stop button: silences this chat; in hands-free mode that also ends it. */
    fun stop() {
        if (handsFree) end(say = false)
        Speaker.stop(prefix)
    }

    fun startHandsFree() {
        if (handsFree) return
        handsFree = true
        retried = false
        Speaker.stop(prefix)
        next()
    }

    fun end(say: Boolean) {
        if (!handsFree) return
        handsFree = false
        answering = null
        phase = Phase.Idle
        Speaker.stop(handsFreeOwner)
        if (say && visible) Speaker.speak(context, "Hands-free off.", prefix + "bye")
    }

    /** Leaving the chat screen or the app. */
    internal fun leave() {
        end(say = false)
        Speaker.stop(prefix)
    }

    internal fun onTurnFinished() {
        if (!visible || (!handsFree && !readAloud)) return
        if (chat.state != ChatState.Ready) {
            // Stopped or failed: nothing will answer, so don't keep listening.
            if (handsFree) {
                end(say = false)
                Speaker.speak(context, "${chat.agent.displayName} stopped. Hands-free off.", prefix + "bye")
            }
            return
        }
        val reply = lastReply()?.text?.let(SpeakableText::fromMarkdown).orEmpty()
        if (!handsFree) {
            if (reply.isNotBlank()) Speaker.speak(context, reply, prefix + "turn")
            return
        }
        speakThen(reply.ifBlank { "Done." }) { next() }
    }

    internal fun onPermission(item: ChatItem.Permission) {
        // Speaking or listening: [next] picks the prompt up when that's over.
        if (handsFree && visible && phase == Phase.Waiting && item.key !in asked) next()
    }

    internal fun onRecognized(alternatives: List<String>) {
        recognizing = false
        if (!handsFree) return
        val target = answering
        answering = null
        if (alternatives.isEmpty()) {
            // Dismissed (tapped away) or nothing heard: the user is done talking.
            end(say = true)
            return
        }
        val command = VoiceCommands.parse(alternatives, answering = target != null)
        if (command == VoiceCommand.Stop) return end(say = true)
        if (target != null) return answer(target, command)
        chat.enqueue(alternatives.first().trim(), emptyList())
        // A prompt that came in while listening is asked now; otherwise wait for the reply.
        val pending = chat.items.lastOrNull { it is ChatItem.Permission && it.resolved == null }
        if (pending != null && pending.key !in asked) next() else phase = Phase.Waiting
    }

    /** Only the one-time choices by voice: "always" stays a deliberate tap. */
    private fun answer(item: ChatItem.Permission, command: VoiceCommand) {
        if (item.resolved != null) return next() // answered on screen meanwhile
        val option = when (command) {
            VoiceCommand.Allow -> item.options.firstOrNull { it.kind == "allow_once" }
            VoiceCommand.Deny -> item.options.firstOrNull { it.kind == "reject_once" }
            else -> null
        }
        when {
            option != null -> {
                chat.answerPermission(item, option)
                speakThen(if (command == VoiceCommand.Allow) "Allowed." else "Denied.") { next() }
            }
            command != VoiceCommand.None -> speakThen("Please answer that one on screen.") { next() }
            !retried -> {
                retried = true
                speakThen("Sorry, say allow or deny.") { listen(item) }
            }
            else -> speakThen("I'll leave that one for you.") { next() }
        }
    }

    /** What hands-free does when nothing is in flight: ask a waiting prompt, wait, or listen. */
    private fun next() {
        if (!handsFree || !visible) return
        val pending = chat.items.lastOrNull { it is ChatItem.Permission && it.resolved == null } as? ChatItem.Permission
        when {
            pending != null && asked.add(pending.key) -> {
                retried = false
                speakThen(SpeakableText.permissionPrompt(chat.agent.displayName, pending.toolTitle, pending.toolKind)) { listen(pending) }
            }
            pending != null || chat.isRunning || chat.state != ChatState.Ready -> phase = Phase.Waiting
            else -> listen(null)
        }
    }

    private var utterance = 0

    private fun speakThen(text: String, then: () -> Unit) {
        phase = Phase.Speaking
        val mine = ++utterance
        Speaker.speak(context, text, handsFreeOwner) { result ->
            // Replaced by a newer hands-free line: that one carries on the loop.
            if (!handsFree || mine != utterance) return@speak
            // Interrupted means Stop, another app's audio or a call: stop the loop. A missing
            // engine shouldn't stop it, listening still works.
            if (result == Speaker.End.Interrupted) end(say = false) else then()
        }
    }

    private fun listen(item: ChatItem.Permission?) {
        if (!handsFree || !visible) return
        answering = item
        phase = Phase.Listening
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, if (item != null) "Say allow or deny" else "Talk to ${chat.agent.displayName}")
        recognizing = true
        val launched = runCatching { launchRecognizer!!.invoke(intent) }.isSuccess
        if (!launched) {
            recognizing = false
            Toast.makeText(context, "No speech recognition on this phone", Toast.LENGTH_LONG).show()
            end(say = false)
        }
    }

    private fun lastReply(): ChatItem.AgentText? {
        val items = chat.items
        for (i in items.indices.reversed()) {
            when (val item = items[i]) {
                is ChatItem.AgentText -> if (item.text.isNotBlank()) return item
                is ChatItem.User -> return null
                else -> Unit
            }
        }
        return null
    }
}

/**
 * Voice for the chat on screen. Turn ends are seen as the chat leaving Running (works for chats
 * mirrored from another device too); a queued message that starts at once can hide one, which
 * only skips reading that reply.
 */
@Composable
fun rememberVoiceChat(chat: ChatModel): VoiceChat {
    val context = LocalContext.current
    val voice = remember(chat.id) { VoiceChat(chat, context.applicationContext) }
    LaunchedEffect(Unit) { VoicePrefs.load(context) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val heard = if (r.resultCode == Activity.RESULT_OK) r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS).orEmpty() else emptyList()
        voice.onRecognized(heard.filter { it.isNotBlank() })
    }
    SideEffect { voice.launchRecognizer = { launcher.launch(it) } }
    LaunchedEffect(voice) {
        var wasRunning = chat.isRunning
        snapshotFlow { chat.isRunning }.collect { running ->
            if (wasRunning && !running) voice.onTurnFinished()
            wasRunning = running
        }
    }
    LaunchedEffect(voice) {
        snapshotFlow { chat.items.lastOrNull { it is ChatItem.Permission && it.resolved == null } as? ChatItem.Permission }
            .collect { p -> if (p != null) voice.onPermission(p) }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, voice) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> voice.visible = true
                Lifecycle.Event.ON_STOP -> {
                    voice.visible = false
                    if (!voice.recognizing) voice.leave()
                }
                else -> Unit
            }
        }
        voice.visible = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            voice.leave()
        }
    }
    return voice
}

/** Chat menu entries: the per-chat read-aloud toggle and hands-free. */
@Composable
fun VoiceMenuItems(voice: VoiceChat, close: () -> Unit) {
    DropdownMenuItem(
        leadingIcon = { PhIcon(SpeakerHigh, null, size = 18.dp) },
        text = { Text("Read replies aloud") },
        trailingIcon = { if (voice.readAloud) PhIcon(Ph.CheckCircle, "On", size = 18.dp, tint = MaterialTheme.colorScheme.primary) },
        onClick = { close(); voice.setReadAloud(!voice.readAloud) },
    )
    DropdownMenuItem(
        leadingIcon = { PhIcon(Ph.Microphone, null, size = 18.dp) },
        text = { Text(if (voice.handsFree) "Stop hands-free" else "Hands-free") },
        enabled = voice.handsFree || voice.chat.state == ChatState.Ready || voice.chat.isRunning,
        onClick = { close(); if (voice.handsFree) voice.end(say = false) else voice.startHandsFree() },
    )
}

/** Small speaker under a finished reply; turns into Stop while that reply is being read. */
@Composable
fun ReadAloudButton(voice: VoiceChat, item: ChatItem.AgentText) {
    if (!voice.canRead(item)) return
    val reading = voice.isReading(item)
    IconButton(onClick = { voice.read(item) }, modifier = Modifier.size(32.dp)) {
        PhIcon(
            if (reading) Ph.Stop else SpeakerHigh,
            if (reading) "Stop reading" else "Read aloud",
            size = 16.dp,
            tint = if (reading) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
        )
    }
}

/** A strip above the message box while this chat speaks or is hands-free, with Stop. */
@Composable
fun VoiceBar(voice: VoiceChat) {
    val speaking = voice.speaking
    if (!voice.handsFree && !speaking) return
    val tile = AppColors.tiles.lime
    val label = when {
        !voice.handsFree -> "Reading aloud…"
        voice.phase == VoiceChat.Phase.Listening -> "Hands-free · listening…"
        speaking || voice.phase == VoiceChat.Phase.Speaking -> "Hands-free · speaking…"
        else -> "Hands-free · waiting for ${voice.chat.agent.displayName}…"
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(tile.fill)
            .padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        PhIcon(if (voice.handsFree) Ph.Microphone else SpeakerHigh, null, size = 16.dp, tint = tile.on)
        Spacer(Modifier.width(8.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = tile.on,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = voice::stop) { Text("Stop", color = tile.on) }
    }
}

/** Phosphor (MIT) "speaker-high", regular weight, on the same 256-unit grid as [Ph]. */
val SpeakerHigh: ImageVector by lazy {
    ImageVector.Builder("SpeakerHigh", 24.dp, 24.dp, 256f, 256f)
        .addPath(
            pathData = addPathNodes(
                "M155.51,24.81a8,8,0,0,0-8.42.88L77.25,80H32A16,16,0,0,0,16,96v64a16,16,0,0,0,16,16H77.25l69.84,54.31A8,8,0,0,0,160,224V32A8,8,0,0,0,155.51,24.81ZM32,96H72v64H32ZM144,207.64,88,164.09V91.91l56-43.55Zm54-106.08a40,40,0,0,1,0,52.88,8,8,0,0,1-12-10.58,24,24,0,0,0,0-31.72,8,8,0,0,1,12-10.58ZM248,128a79.9,79.9,0,0,1-20.37,53.34,8,8,0,0,1-11.92-10.67,64,64,0,0,0,0-85.33,8,8,0,1,1,11.92-10.67A79.83,79.83,0,0,1,248,128Z",
            ),
            fill = SolidColor(Color.Black),
        )
        .build()
}
