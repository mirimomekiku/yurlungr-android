package dev.yurlungr.app.chat

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.yurlungr.app.MainActivity
import dev.yurlungr.app.R
import dev.yurlungr.app.YurlungrApp
import dev.yurlungr.app.agents.Agent
import dev.yurlungr.app.runtime.RuntimeService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.Executors

/**
 * App-wide registry of chats, saved ones included. Main thread only. Chats with a running
 * agent keep [RuntimeService] in the foreground; saved chats cost nothing until opened.
 */
object ChatSessions {
    val chats = mutableStateListOf<ChatSession>()

    /** A chat waiting for the user's answer; shown as a sticky banner until resolved. */
    data class Urgent(val chatId: String, val agentName: String, val what: String)

    /** Open alerts, oldest first. Cleared as each is answered, finished or deleted. */
    val urgent = mutableStateListOf<Urgent>()

    private fun clearUrgent(id: String) = urgent.removeAll { it.chatId == id }

    /** True while an activity is visible; agent alerts only notify when it isn't. */
    @Volatile var appVisible = false

    /** Chats were added or removed (the remote server re-attaches and re-broadcasts). */
    var onRegistryChanged: (() -> Unit)? = null

    private lateinit var appContext: Context
    /** Single thread: saves of the same chat land in order. */
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "chat-store").apply { isDaemon = true } }

    /** Deleting a chat's checkpoints can take a long gc: kept off [io], so saves don't wait for it. */
    private val cleanup = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** Saved chats have been read (they load in the background at startup). */
    var loaded by mutableStateOf(false)
        private set

    /**
     * Loads saved chats (history only; agents start when a chat is opened). Parsing runs on
     * [io], so it's ordered with saves and off the main thread; the chats appear when it's done.
     */
    fun init(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
        val main = Handler(Looper.getMainLooper())
        io.execute {
            val saved = runCatching { ChatStore.readAll(appContext) }.getOrDefault(emptyList())
            main.post {
                for (s in saved) {
                    val agent = Agent.entries.firstOrNull { it.name == s.agent } ?: continue
                    if (get(s.id) != null) continue
                    chats += wire(ChatSession(appContext, agent, File(s.cwd), s))
                    io.execute { Checkpoints.closeInterrupted(appContext, s.id) }
                }
                loaded = true
                if (saved.isNotEmpty()) onRegistryChanged?.invoke()
                AgentStatus.changed(appContext)
            }
        }
    }

    val activeCount: Int get() = chats.count { it.isActive }

    fun create(context: Context, agent: Agent, cwd: File, skipPermissions: Boolean = false): ChatSession {
        val chat = wire(ChatSession(context.applicationContext, agent, cwd, skipPermissions = skipPermissions))
        chats.add(0, chat)
        // Save right away: a chat stuck at sign-in would otherwise vanish on the next restart.
        chat.onChanged?.invoke(chat, false)
        onRegistryChanged?.invoke() // before start(), so listeners see its first events
        chat.start()
        AgentStatus.changed(context)
        return chat
    }

    fun get(id: String?): ChatSession? = chats.firstOrNull { it.id == id }

    /**
     * A new chat with the same agent and folder whose first message is a digest of [from], for
     * when its context is full. The old chat stays as it was.
     */
    fun continueFresh(context: Context, from: ChatSession): ChatSession {
        val digest = ChatExport.digest(from.items.toList(), from.cwdLabel)
        val chat = create(context, from.agent, from.cwd, from.skipPermissions)
        chat.rename("Continued: " + from.title.removePrefix("Continued: "))
        chat.queue(digest, emptyList())
        return chat
    }

    /** Ends the agent process but keeps the chat. */
    fun stop(chat: ChatSession) = chat.stop()

    /** Ends every agent process (notification "Exit"); history is kept. */
    fun stopAll() = chats.filter { it.isActive || it.state != ChatState.Saved }.forEach { it.stop() }

    fun delete(context: Context, chat: ChatSession) {
        AgentStatus.changed(context)
        chat.close()
        chats.remove(chat)
        clearUrgent(chat.id)
        onRegistryChanged?.invoke()
        NotificationManagerCompat.from(context).cancel(chat.id.hashCode())
        val id = chat.id
        Drafts.set(context, id, "")
        pendingSaves.remove(id)
        io.execute { ChatStore.delete(appContext, id) }
        cleanup.launch { runCatching { Checkpoints.delete(appContext, id) } }
        RuntimeService.refresh(context)
    }

    private fun wire(chat: ChatSession) = chat.apply {
        listeners += dev.yurlungr.app.runtime.RuntimeStatus
        onChanged = { c, activity ->
            save(c)
            // Most recently active first (a save of streamed output doesn't move it).
            val index = if (activity) chats.indexOf(c) else -1
            if (index > 0) {
                chats.removeAt(index)
                chats.add(0, c)
            }
        }
        onActiveChanged = { RuntimeService.refresh(appContext) }
        onTurnFinished = { c, summary ->
            clearUrgent(c.id)
            // A finished turn needs no answer: no popup, no sound. Drop a stale approval alert if one is up.
            if (awaitingApproval.remove(c.id)) runCatching { NotificationManagerCompat.from(appContext).cancel(c.id.hashCode()) }
            // Away from the app: a quiet note with Reply, so the next message can go without opening it.
            if (!appVisible) ChatNotifications.finished(appContext, c, summary)
            AgentStatus.changed(appContext)
        }
        onNeedsAttention = { c, what ->
            awaitingApproval += c.id
            clearUrgent(c.id)
            urgent += Urgent(c.id, c.agent.displayName, what)
            notify(c, "${c.agent.displayName} needs you", what)
            AgentStatus.changed(appContext)
        }
        onAttentionResolved = { c ->
            clearUrgent(c.id)
            val next = c.openPermission
            if (next == null) {
                if (awaitingApproval.remove(c.id)) {
                    runCatching { NotificationManagerCompat.from(appContext).cancel(c.id.hashCode()) }
                }
            } else {
                // Another prompt is still open: the banner and the alert (one per chat) move on to it.
                val what = c.attentionText(next)
                urgent += Urgent(c.id, c.agent.displayName, what)
                if (c.id in awaitingApproval && !appVisible) {
                    ChatNotifications.approval(appContext, c, next, "${c.agent.displayName} needs you", what)
                }
            }
            AgentStatus.changed(appContext)
        }
        listeners += AgentStatus
    }

    /** Snapshots waiting to be written, at most one per chat: a newer one replaces it instead of queueing behind it. */
    private val pendingSaves = java.util.concurrent.ConcurrentHashMap<String, SavedChat>()

    /**
     * Saves [chat] on [io]. Snapshots are taken on the main thread; when the writer is behind (a
     * big chat streaming), only the newest snapshot of each chat is written.
     */
    private fun save(chat: ChatSession) {
        val snapshot = chat.snapshot()
        if (pendingSaves.put(chat.id, snapshot) != null) return // a write is already queued; it takes this one
        io.execute { pendingSaves.remove(chat.id)?.let { ChatStore.write(appContext, it) } }
    }

    /** Chats whose current notification asks for an approval (cleared once it's answered). */
    private val awaitingApproval = mutableSetOf<String>()

    @SuppressLint("MissingPermission") // targetSdk 28: notification permission isn't a runtime grant
    private fun notify(chat: ChatSession, title: String, text: String) {
        val context = appContext
        if (appVisible) {
            // In the app: the sticky banner shows it; add the sound so it isn't missed.
            runCatching {
                dev.yurlungr.app.ui.Feedback.signal(context, dev.yurlungr.app.ui.Feedback.Kind.NeedsYou)
            }
            return
        }
        // The prompt just added: its options become Allow / Deny buttons on the notification.
        val item = chat.items.lastOrNull { it is ChatItem.Permission && it.resolved == null } as? ChatItem.Permission
        ChatNotifications.approval(context, chat, item, title, text)
    }
}
