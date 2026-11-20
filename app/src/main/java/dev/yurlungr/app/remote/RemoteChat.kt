package dev.yurlungr.app.remote

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.yurlungr.app.agents.Agent
import android.util.Base64
import dev.yurlungr.app.chat.AcpExtras
import dev.yurlungr.app.chat.Attachment
import dev.yurlungr.app.chat.ChatItem
import dev.yurlungr.app.chat.ConfigOption
import dev.yurlungr.app.chat.PromptCaps
import dev.yurlungr.app.chat.SlashCommand
import dev.yurlungr.app.chat.Usage
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import dev.yurlungr.app.chat.ChatModel
import dev.yurlungr.app.chat.ChatState
import dev.yurlungr.app.chat.ChatStore
import dev.yurlungr.app.chat.PermissionOption
import dev.yurlungr.app.chat.QueuedMessage
import kotlinx.serialization.json.contentOrNull
import dev.yurlungr.app.chat.SessionMode
import dev.yurlungr.app.remote.RemoteProtocol.long
import dev.yurlungr.app.remote.RemoteProtocol.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A chat that lives on another phone, mirrored from its event stream. [send] delivers
 * commands to the host; [apply] folds host messages into local state (main thread).
 */
class RemoteChat(
    override val id: String,
    agent: Agent,
    private val send: (JsonObject) -> Unit,
    private val onDeleted: (RemoteChat) -> Unit = {},
) : ChatModel {
    override var agent by mutableStateOf(agent)
        private set
    override val items = mutableStateListOf<ChatItem>()
    override var state by mutableStateOf<ChatState>(ChatState.Saved)
        private set
    override var title by mutableStateOf(agent.displayName)
        private set
    override var cwdLabel by mutableStateOf("")
        private set
    override var folderName by mutableStateOf("")
        private set
    override var modes by mutableStateOf<List<SessionMode>>(emptyList())
        private set
    override var currentModeId by mutableStateOf<String?>(null)
        private set
    override var loginUrl by mutableStateOf<String?>(null)
        private set
    override var commands by mutableStateOf<List<SlashCommand>>(emptyList())
        private set
    override var configOptions by mutableStateOf<List<ConfigOption>>(emptyList())
        private set
    override var usage by mutableStateOf<Usage?>(null)
        private set
    override var caps by mutableStateOf(PromptCaps())
        private set
    override var skipPermissions by mutableStateOf(false)
        private set
    override val isRemote = true

    /** Host item key ↔ local item (local items get their own keys). */
    private val byHostKey = HashMap<Long, ChatItem>()
    private val hostKeyOf = HashMap<ChatItem, Long>()

    fun apply(msg: JsonObject) {
        when (msg.str("t")) {
            "snapshot" -> {
                val chat = msg["chat"] as? JsonObject ?: return
                RemoteProtocol.agentOf(chat.str("agent"))?.let { agent = it }
                folderName = chat.str("folder").orEmpty()
                applyMeta(chat)
                state = RemoteProtocol.decodeState(chat.str("state"), chat.str("message"))
                items.clear()
                byHostKey.clear()
                hostKeyOf.clear()
                (chat["items"] as? JsonArray).orEmpty().forEach { el -> (el as? JsonObject)?.let(::upsert) }
            }
            "item" -> (msg["item"] as? JsonObject)?.let(::upsert)
            "append" -> {
                val delta = msg.str("text") ?: return
                when (val item = msg.long("k")?.let { byHostKey[it] }) {
                    is ChatItem.AgentText -> item.text += delta
                    is ChatItem.Thought -> item.text += delta
                    else -> Unit
                }
            }
            "state" -> {
                state = RemoteProtocol.decodeState(msg.str("state"), msg.str("message"))
                sendLocalQueue()
            }
            "meta" -> applyMeta(msg)
        }
    }

    /** The host queues messages itself (it sends `queued` in meta); else this side holds them. */
    private var hostQueues = false

    /** Messages waiting behind the host's turn: mirrored from the host, or held here for an older host. */
    override val queued = mutableStateListOf<QueuedMessage>()

    override fun enqueue(text: String, attachments: List<Attachment>) {
        if (text.isBlank() && attachments.isEmpty()) return
        when {
            hostQueues -> sendMessage("enqueue", text, attachments)
            // An older host drops a message sent while it's busy: hold it until the chat is ready.
            state == ChatState.Running || state == ChatState.Starting -> queued += QueuedMessage(text, attachments)
            else -> send(text, attachments)
        }
    }

    override fun removeQueued(index: Int) {
        val m = queued.getOrNull(index) ?: return
        if (hostQueues) command("unqueue", "index" to index.toLong(), "text" to m.text)
        else queued.removeAt(index)
    }

    override fun sendQueuedNow() {
        if (hostQueues) command("sendNow")
        else if (state == ChatState.Ready) sendLocalQueue()
        else cancel() // the next one goes when the host says it's ready again
    }

    /** For an older host: the next held message, once the chat is free. */
    private fun sendLocalQueue() {
        if (hostQueues || state != ChatState.Ready) return
        val next = queued.removeFirstOrNull() ?: return
        send(next.text, next.attachments)
    }

    private fun applyMeta(obj: JsonObject) {
        skipPermissions = (obj["skipPermissions"] as? JsonPrimitive)?.booleanOrNull ?: false
        obj.str("title")?.let { title = it }
        obj.str("cwd")?.let { cwdLabel = it }
        currentModeId = obj.str("mode")
        loginUrl = obj.str("loginUrl")
        modes = RemoteProtocol.modes(obj)
        obj["commands"]?.let { commands = AcpExtras.parseCommands(it) }
        obj["configOptions"]?.let { configOptions = AcpExtras.parseConfigOptions(it) }
        usage = AcpExtras.parseUsage(obj["usage"] as? JsonObject)
        (obj["queued"] as? JsonArray)?.let { arr ->
            if (!hostQueues) {
                // Held here until now (or from before a host update): they go to the host's queue.
                hostQueues = true
                val held = queued.toList()
                queued.clear()
                held.forEach { sendMessage("enqueue", it.text, it.attachments) }
            }
            val texts = arr.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            if (texts != queued.map { it.text }) {
                queued.clear()
                queued += texts.map { QueuedMessage(it) }
            }
        }
        (obj["caps"] as? JsonObject)?.let { c ->
            caps = PromptCaps(
                image = (c["image"] as? JsonPrimitive)?.booleanOrNull ?: false,
                embeddedContext = (c["embeddedContext"] as? JsonPrimitive)?.booleanOrNull ?: false,
            )
        }
    }

    private fun upsert(obj: JsonObject) {
        val key = obj.long("k") ?: return
        val incoming = ChatStore.decodeItem(obj, wire = true) ?: return
        val existing = byHostKey[key]
        if (existing == null) {
            items += incoming
            byHostKey[key] = incoming
            hostKeyOf[incoming] = key
            return
        }
        when {
            existing is ChatItem.AgentText && incoming is ChatItem.AgentText -> existing.text = incoming.text
            existing is ChatItem.Thought && incoming is ChatItem.Thought -> existing.text = incoming.text
            existing is ChatItem.ToolCall && incoming is ChatItem.ToolCall -> {
                existing.title = incoming.title
                existing.kind = incoming.kind
                existing.status = incoming.status
                existing.content = incoming.content
                existing.locations = incoming.locations
            }
            existing is ChatItem.Plan && incoming is ChatItem.Plan -> existing.entries = incoming.entries
            existing is ChatItem.Permission && incoming is ChatItem.Permission -> existing.resolved = incoming.resolved
        }
    }

    private fun command(type: String, vararg fields: Pair<String, Any>) = send(buildJsonObject {
        put("t", type)
        put("chat", id)
        for ((key, value) in fields) {
            when (value) {
                is String -> put(key, value)
                is Long -> put(key, value)
                is Boolean -> put(key, value)
                is JsonElement -> put(key, value)
            }
        }
    })

    /** Subscribe to the host's event stream for this chat (sent again after reconnects). */
    fun open() = command("open")

    override fun resume() = command("resume")
    override fun restart() = command("restart")
    override fun send(text: String, attachments: List<Attachment>) {
        if (text.isBlank() && attachments.isEmpty()) return
        sendMessage("send", text, attachments)
    }

    private fun sendMessage(type: String, text: String, attachments: List<Attachment>) {
        val files = JsonArray(attachments.map { a ->
            buildJsonObject {
                put("name", a.name)
                put("mime", a.mimeType)
                put("data", Base64.encodeToString(a.bytes, Base64.NO_WRAP))
            }
        })
        command(type, "text" to text.trim(), "attachments" to files)
    }

    override fun setConfigOption(id: String, value: String) = command("config", "id" to id, "value" to value)
    override fun setConfigOption(id: String, value: Boolean) = command("config", "id" to id, "value" to value)
    override fun cancel() = command("cancel")
    override fun setMode(modeId: String) = command("mode", "mode" to modeId)

    override fun answerPermission(item: ChatItem.Permission, option: PermissionOption) {
        val key = hostKeyOf[item] ?: return
        if (item.resolved != null) return
        item.resolved = option.name // optimistic; the host confirms with an item update
        command("permission", "k" to key, "option" to option.optionId)
    }

    override fun endSession() = command("stop")

    override fun delete() {
        command("delete")
        onDeleted(this)
    }
}
