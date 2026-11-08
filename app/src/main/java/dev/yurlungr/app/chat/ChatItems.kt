package dev.yurlungr.app.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.atomic.AtomicLong

private val ids = AtomicLong()

/** One entry in a chat transcript. Mutable items update in place as the agent streams. */
sealed class ChatItem {
    val key: Long = ids.incrementAndGet()

    /** [attachments] are display names of files/images sent with the message. */
    class User(val text: String, val attachments: List<String> = emptyList()) : ChatItem()

    class AgentText(val messageId: String?) : ChatItem() {
        var text by mutableStateOf("")
    }

    class Thought(val messageId: String?) : ChatItem() {
        var text by mutableStateOf("")
    }

    class ToolCall(val toolCallId: String) : ChatItem() {
        var title by mutableStateOf("")
        var kind by mutableStateOf("other")
        var status by mutableStateOf("pending")
        var content by mutableStateOf<List<ToolContent>>(emptyList())
        var locations by mutableStateOf<List<String>>(emptyList())
    }

    class Plan : ChatItem() {
        var entries by mutableStateOf<List<PlanEntry>>(emptyList())
    }

    class Permission(
        val toolTitle: String,
        val options: List<PermissionOption>,
        val decision: CompletableDeferred<String?>,
    ) : ChatItem() {
        /** Chosen option name, "Cancelled", or null while waiting. */
        var resolved by mutableStateOf<String?>(null)
    }

    class Notice(val text: String, val isError: Boolean = false) : ChatItem()
}

sealed interface ToolContent {
    data class Text(val text: String) : ToolContent
    data class Diff(val path: String, val oldText: String?, val newText: String) : ToolContent
    data class Terminal(val terminalId: String) : ToolContent
}

data class PlanEntry(val content: String, val status: String, val priority: String)

data class PermissionOption(val optionId: String, val name: String, val kind: String) {
    val isAllow: Boolean get() = kind.startsWith("allow")
}

data class SessionMode(val id: String, val name: String, val description: String?)

/** A command the agent advertises (`available_commands_update`); invoked by sending "/name args". */
data class SlashCommand(val name: String, val description: String, val hint: String?)

data class ConfigChoice(val value: String, val name: String, val description: String?)

/**
 * A session setting the agent exposes (`configOptions`): a select (model, reasoning level…)
 * or a boolean. Exactly one of [currentValue] / [booleanValue] is set.
 */
data class ConfigOption(
    val id: String,
    val name: String,
    val description: String?,
    val category: String?,
    val choices: List<ConfigChoice>,
    val currentValue: String?,
    val booleanValue: Boolean?,
) {
    val isBoolean: Boolean get() = booleanValue != null
    val currentName: String get() = choices.firstOrNull { it.value == currentValue }?.name ?: currentValue.orEmpty()
}

/** Context-window usage from `usage_update`. */
data class Usage(val used: Long, val size: Long, val costAmount: Double?, val currency: String?)

/** What the agent accepts in a prompt besides text and resource links. */
data class PromptCaps(val image: Boolean = false, val embeddedContext: Boolean = false)

/** A file or image picked on the phone, waiting to be sent with the next message. */
class Attachment(val name: String, val mimeType: String, val bytes: ByteArray) {
    val isImage: Boolean get() = mimeType.startsWith("image/")
}
