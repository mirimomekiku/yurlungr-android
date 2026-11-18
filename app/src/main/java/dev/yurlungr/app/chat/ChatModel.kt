package dev.yurlungr.app.chat

import dev.yurlungr.app.agents.Agent

sealed interface ChatState {
    /** History only; no agent process. Opening the chat resumes it. */
    data object Saved : ChatState
    data object Starting : ChatState
    data object Ready : ChatState
    data object Running : ChatState
    data object AuthRequired : ChatState
    data class Failed(val message: String) : ChatState
}

/**
 * What the chat screen needs from a conversation. Implemented by [ChatSession] (agent runs
 * on this phone) and by remote chats mirrored from another device.
 */
interface ChatModel {
    val id: String
    val agent: Agent
    /** Working folder for display, e.g. "~/projects/app". */
    val cwdLabel: String
    val folderName: String
    val title: String
    val state: ChatState
    val items: List<ChatItem>
    val modes: List<SessionMode>
    val currentModeId: String?
    val loginUrl: String?
    /** Whether this chat opted into the agent's skip-permissions override. */
    val skipPermissions: Boolean get() = false
    /** The agent runs on another device: sign-in has to happen there. */
    val isRemote: Boolean
    val isRunning: Boolean get() = state == ChatState.Running
    val commands: List<SlashCommand>
    val configOptions: List<ConfigOption>
    val usage: Usage?
    val caps: PromptCaps

    /** Messages waiting for the current turn to end (sent in order, automatically). */
    val queued: List<QueuedMessage> get() = emptyList()
    /** Sends [text] now when idle; otherwise queues it behind the running turn. */
    fun enqueue(text: String, attachments: List<Attachment>) = send(text, attachments)
    fun removeQueued(index: Int) {}
    /** Queued messages can be reordered and taken back for editing (chats on this phone). */
    val canEditQueue: Boolean get() = false
    /** Moves a queued message from [from] to [to] (both indexes into [queued]). */
    fun moveQueued(from: Int, to: Int) {}
    /** Takes a queued message out of the queue (to edit it in the message box). */
    fun takeQueued(index: Int): QueuedMessage? = null
    /** Stops the running turn so the first queued message goes out right away. */
    fun sendQueuedNow() {}
    /** Project files matching [query] for @-mentions (relative paths). */
    suspend fun suggestFiles(query: String): List<String> = emptyList()

    fun resume()
    fun restart()
    fun send(text: String) = send(text, emptyList())
    fun send(text: String, attachments: List<Attachment>)
    fun setConfigOption(id: String, value: String)
    fun setConfigOption(id: String, value: Boolean)
    fun cancel()
    fun setMode(modeId: String)
    fun answerPermission(item: ChatItem.Permission, option: PermissionOption)
    /** Approves this request and every later one of the same kind in this project. */
    fun alwaysAllow(item: ChatItem.Permission) {}
    /** Stops the agent; the chat stays and can be resumed. */
    fun endSession()
    fun delete()
}

/** Fine-grained change events (all on the main thread), used to stream chats to other devices. */
interface ChatListener {
    fun onItemAdded(chat: ChatSession, item: ChatItem) {}
    fun onItemUpdated(chat: ChatSession, item: ChatItem) {}
    fun onTextAppended(chat: ChatSession, item: ChatItem, delta: String) {}
    fun onStateChanged(chat: ChatSession) {}
    fun onMetaChanged(chat: ChatSession) {}
}

private val MISSING_TOOL = Regex(
    "(?i)\\b(code|exit) 12[67]\\b|command not found|not found|no such file or directory|cannot link executable|" +
        "cannot execute|permission denied|exec format error|error while loading shared libraries",
)

/**
 * Whether a chat failure means the agent, Node or a library is missing or can't run (exit 126/127,
 * "not found", a linker error): reinstalling is the fix, restarting alone won't help.
 */
fun failureLooksLikeMissingTool(message: String): Boolean = MISSING_TOOL.containsMatchIn(message)

fun cwdLabel(path: String): String =
    path.substringAfter("/files/home", missingDelimiterValue = "").let { if (it.isEmpty() && !path.contains("/files/home")) path else "~$it" }
