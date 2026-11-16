package dev.yurlungr.app.chat

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.File

/** A chat as stored on disk: enough to show its history and resume the agent session. */
data class SavedChat(
    val id: String,
    val agent: String,
    val cwd: String,
    val title: String,
    val acpSessionId: String?,
    val updatedAt: Long,
    val items: List<ChatItem>,
    val skipPermissions: Boolean = false,
    /** A first message shown but not yet delivered (the agent wasn't ready); sent on resume. */
    val pendingText: String? = null,
    /** Messages queued behind a running turn, sent in order once the agent is free (text only). */
    val queuedTexts: List<String> = emptyList(),
)

/**
 * Persists chats as one JSON file each in app-private storage (outside the runtime's
 * files dir, so tools running in the runtime don't trip over them).
 */
object ChatStore {
    private const val TAG = "ChatStore"
    private const val VERSION = 1
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    private fun dir(context: Context): File = context.getDir("chats", Context.MODE_PRIVATE)

    fun write(context: Context, chat: SavedChat) {
        val file = File(dir(context), "${chat.id}.json")
        val tmp = File(file.path + ".tmp")
        try {
            tmp.writeText(encode(chat).toString())
            if (!tmp.renameTo(file)) error("rename failed")
        } catch (e: Exception) {
            Log.w(TAG, "Saving chat ${chat.id} failed", e)
            tmp.delete()
        }
    }

    fun delete(context: Context, id: String) {
        File(dir(context), "$id.json").delete()
    }

    fun readAll(context: Context): List<SavedChat> =
        dir(context).listFiles { f -> f.name.endsWith(".json") }.orEmpty().mapNotNull { file ->
            runCatching { decode(json.parseToJsonElement(file.readText()).jsonObject) }
                .onFailure { Log.w(TAG, "Unreadable chat file ${file.name}", it) }
                .getOrNull()
        }.sortedByDescending { it.updatedAt }

    // ---- encoding ----------------------------------------------------------------------

    internal fun encode(chat: SavedChat) = buildJsonObject {
        put("version", VERSION)
        put("id", chat.id)
        put("agent", chat.agent)
        put("cwd", chat.cwd)
        put("title", chat.title)
        put("skipPermissions", chat.skipPermissions)
        chat.pendingText?.let { put("pending", it) }
        if (chat.queuedTexts.isNotEmpty()) putJsonArray("queued") { chat.queuedTexts.forEach { add(JsonPrimitive(it)) } }
        chat.acpSessionId?.let { put("acpSessionId", it) }
        put("updatedAt", chat.updatedAt)
        putJsonArray("items") { chat.items.forEach { add(encodeItem(it)) } }
    }

    /**
     * [wire] = live form for another device: carries the item key, open permission prompts
     * with their options, and in-progress tool status (the saved form normalizes those).
     */
    internal fun encodeItem(item: ChatItem, wire: Boolean = false): JsonObject = buildJsonObject {
        if (wire) put("k", item.key)
        when (item) {
            is ChatItem.User -> {
                put("t", "user")
                put("text", item.text)
                if (item.attachments.isNotEmpty()) put("files", buildJsonArray { item.attachments.forEach { add(it) } })
            }
            is ChatItem.AgentText -> { put("t", "agent"); put("text", item.text) }
            is ChatItem.Thought -> { put("t", "thought"); put("text", item.text) }
            is ChatItem.Notice -> { put("t", "notice"); put("text", item.text); put("error", item.isError) }
            is ChatItem.Plan -> {
                put("t", "plan")
                putJsonArray("entries") {
                    item.entries.forEach { e ->
                        addJsonObject { put("content", e.content); put("status", e.status); put("priority", e.priority) }
                    }
                }
            }
            is ChatItem.Permission -> {
                put("t", "permission")
                put("title", item.toolTitle)
                if (wire) {
                    item.resolved?.let { put("resolved", it) }
                    putJsonArray("options") {
                        item.options.forEach { o ->
                            addJsonObject { put("id", o.optionId); put("name", o.name); put("kind", o.kind) }
                        }
                    }
                } else {
                    // A prompt still open when saved can't be answered after a restart.
                    put("resolved", item.resolved ?: "Interrupted")
                }
            }
            is ChatItem.ToolCall -> {
                put("t", "tool")
                put("id", item.toolCallId)
                put("title", item.title)
                put("kind", item.kind)
                put("status", item.status)
                put("locations", buildJsonArray { item.locations.forEach { add(it) } })
                putJsonArray("content") {
                    item.content.forEach { c ->
                        addJsonObject {
                            when (c) {
                                is ToolContent.Text -> { put("type", "text"); put("text", c.text.take(20_000)) }
                                is ToolContent.Diff -> {
                                    put("type", "diff"); put("path", c.path)
                                    c.oldText?.let { put("old", it.take(50_000)) }
                                    put("new", c.newText.take(50_000))
                                }
                                is ToolContent.Terminal -> { put("type", "terminal"); put("id", c.terminalId) }
                            }
                        }
                    }
                }
            }
        }
    }

    // ---- decoding ----------------------------------------------------------------------

    internal fun decode(obj: JsonObject) = SavedChat(
        id = obj.str("id") ?: error("missing id"),
        agent = obj.str("agent") ?: error("missing agent"),
        cwd = obj.str("cwd") ?: error("missing cwd"),
        title = obj.str("title") ?: "Chat",
        skipPermissions = (obj["skipPermissions"] as? JsonPrimitive)?.booleanOrNull ?: false,
        pendingText = obj.str("pending"),
        queuedTexts = (obj["queued"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
        acpSessionId = obj.str("acpSessionId"),
        updatedAt = (obj["updatedAt"] as? JsonPrimitive)?.longOrNull ?: 0L,
        items = (obj["items"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(::decodeItem) },
    )

    internal fun decodeItem(o: JsonObject, wire: Boolean = false): ChatItem? = when (o.str("t")) {
        "user" -> ChatItem.User(
            o.str("text").orEmpty(),
            (o["files"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
        )
        "agent" -> ChatItem.AgentText(null).apply { text = o.str("text").orEmpty() }
        "thought" -> ChatItem.Thought(null).apply { text = o.str("text").orEmpty() }
        "notice" -> ChatItem.Notice(o.str("text").orEmpty(), (o["error"] as? JsonPrimitive)?.booleanOrNull ?: false)
        "plan" -> ChatItem.Plan().apply {
            entries = (o["entries"] as? JsonArray).orEmpty().mapNotNull { el ->
                val e = el as? JsonObject ?: return@mapNotNull null
                PlanEntry(e.str("content").orEmpty(), e.str("status") ?: "pending", e.str("priority") ?: "medium")
            }
        }
        "permission" -> if (wire) {
            val options = (o["options"] as? JsonArray).orEmpty().mapNotNull { el ->
                val p = el as? JsonObject ?: return@mapNotNull null
                PermissionOption(p.str("id") ?: return@mapNotNull null, p.str("name").orEmpty(), p.str("kind").orEmpty())
            }
            ChatItem.Permission(o.str("title").orEmpty(), options, CompletableDeferred()).apply { resolved = o.str("resolved") }
        } else {
            ChatItem.Permission(o.str("title").orEmpty(), emptyList(), CompletableDeferred<String?>(null))
                .apply { resolved = o.str("resolved") ?: "Interrupted" }
        }
        "tool" -> ChatItem.ToolCall(o.str("id") ?: "tool").apply {
            title = o.str("title").orEmpty()
            kind = o.str("kind") ?: "other"
            // Anything unfinished was interrupted by the restart.
            status = if (wire) {
                o.str("status") ?: "pending"
            } else {
                o.str("status").takeIf { it == "completed" || it == "failed" } ?: "interrupted"
            }
            locations = (o["locations"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            content = (o["content"] as? JsonArray).orEmpty().mapNotNull { el ->
                val c = el as? JsonObject ?: return@mapNotNull null
                when (c.str("type")) {
                    "text" -> ToolContent.Text(c.str("text").orEmpty())
                    "diff" -> ToolContent.Diff(c.str("path") ?: "?", c.str("old"), c.str("new").orEmpty())
                    "terminal" -> ToolContent.Terminal(c.str("id").orEmpty())
                    else -> null
                }
            }
        }
        else -> null
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
}
