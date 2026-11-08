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

    fun resume()
    fun restart()
    fun send(text: String) = send(text, emptyList())
    fun send(text: String, attachments: List<Attachment>)
    fun setConfigOption(id: String, value: String)
    fun setConfigOption(id: String, value: Boolean)
    fun cancel()
    fun setMode(modeId: String)
    fun answerPermission(item: ChatItem.Permission, option: PermissionOption)
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

fun cwdLabel(path: String): String =
    path.substringAfter("/files/home", missingDelimiterValue = "").let { if (it.isEmpty() && !path.contains("/files/home")) path else "~$it" }
