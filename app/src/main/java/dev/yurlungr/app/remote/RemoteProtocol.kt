package dev.yurlungr.app.remote

import dev.yurlungr.app.agents.Agent
import dev.yurlungr.app.agents.AgentStatus
import dev.yurlungr.app.chat.AcpExtras
import dev.yurlungr.app.chat.ChatSession
import dev.yurlungr.app.chat.ChatState
import dev.yurlungr.app.chat.ChatStore
import dev.yurlungr.app.chat.SessionMode
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Messages between a host phone (runs the agents) and a client phone (shows them), as JSON
 * text frames over a WebSocket. Every message has a type field "t".
 *
 * client → host: hello{token,device} · open{chat} · close{chat} · create{req,agent,folder}
 *                send{chat,text} · cancel · permission{chat,k,option} · mode{chat,mode}
 *                resume · restart · stop · delete (all {chat})
 * host → client: welcome{device,agents,chats} · chats{chats} · created{req,chat}
 *                snapshot{chat} · item{chat,item} · append{chat,k,text} · state{chat,…} · meta{chat,…}
 *                error{message}
 */
object RemoteProtocol {
    const val VERSION = 1
    const val DEFAULT_PORT = 8765

    fun encodeState(state: ChatState): Pair<String, String?> = when (state) {
        ChatState.Saved -> "saved" to null
        ChatState.Starting -> "starting" to null
        ChatState.Ready -> "ready" to null
        ChatState.Running -> "running" to null
        ChatState.AuthRequired -> "auth" to null
        is ChatState.Failed -> "failed" to state.message
    }

    fun decodeState(name: String?, message: String?): ChatState = when (name) {
        "starting" -> ChatState.Starting
        "ready" -> ChatState.Ready
        "running" -> ChatState.Running
        "auth" -> ChatState.AuthRequired
        "failed" -> ChatState.Failed(message ?: "Stopped")
        else -> ChatState.Saved
    }

    fun summary(chat: ChatSession) = buildJsonObject {
        put("id", chat.id)
        put("agent", chat.agent.name)
        put("title", chat.title)
        put("folder", chat.folderName)
        put("updatedAt", chat.updatedAt)
        val (state, _) = encodeState(chat.state)
        put("state", state)
    }

    fun agents(statuses: List<AgentStatus>) = statuses.map { s ->
        buildJsonObject {
            put("agent", s.agent.name)
            put("installed", s.installed)
            s.signedIn?.let { put("signedIn", it) }
        }
    }

    fun stateMessage(chat: ChatSession) = buildJsonObject {
        put("t", "state")
        put("chat", chat.id)
        val (state, message) = encodeState(chat.state)
        put("state", state)
        message?.let { put("message", it) }
    }

    fun metaMessage(chat: ChatSession) = buildJsonObject {
        put("t", "meta")
        put("chat", chat.id)
        put("title", chat.title)
        put("skipPermissions", chat.skipPermissions)
        put("cwd", chat.cwdLabel)
        chat.currentModeId?.let { put("mode", it) }
        chat.loginUrl?.let { put("loginUrl", it) }
        put("commands", AcpExtras.encodeCommands(chat.commands))
        put("configOptions", AcpExtras.encodeConfigOptions(chat.configOptions))
        chat.usage?.let { put("usage", AcpExtras.encodeUsage(it)) }
        put("caps", buildJsonObject {
            put("image", chat.caps.image)
            put("embeddedContext", chat.caps.embeddedContext)
        })
        putJsonArray("modes") {
            chat.modes.forEach { m ->
                addJsonObject {
                    put("id", m.id)
                    put("name", m.name)
                    m.description?.let { put("description", it) }
                }
            }
        }
    }

    fun snapshot(chat: ChatSession) = buildJsonObject {
        put("t", "snapshot")
        put("chat", buildJsonObject {
            put("id", chat.id)
            put("agent", chat.agent.name)
            put("folder", chat.folderName)
            metaMessage(chat).forEach { (k, v) -> if (k != "t" && k != "chat") put(k, v) }
            stateMessage(chat).forEach { (k, v) -> if (k != "t" && k != "chat") put(k, v) }
            putJsonArray("items") { chat.items.forEach { add(ChatStore.encodeItem(it, wire = true)) } }
        })
    }

    // ---- decoding helpers --------------------------------------------------------------

    fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull
    fun JsonObject.bool(key: String): Boolean = (this[key] as? JsonPrimitive)?.booleanOrNull ?: false

    fun agentOf(name: String?): Agent? = Agent.entries.firstOrNull { it.name == name }

    fun modes(obj: JsonObject): List<SessionMode> = (obj["modes"] as? JsonArray).orEmpty().mapNotNull { el ->
        val m = el as? JsonObject ?: return@mapNotNull null
        SessionMode(m.str("id") ?: return@mapNotNull null, m.str("name").orEmpty(), m.str("description"))
    }
}
