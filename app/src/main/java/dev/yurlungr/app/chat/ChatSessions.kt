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
            }
        }
    }

    val activeCount: Int get() = chats.count { it.isActive }

    fun create(context: Context, agent: Agent, cwd: File, skipPermissions: Boolean = false): ChatSession {
        val chat = wire(ChatSession(context.applicationContext, agent, cwd, skipPermissions = skipPermissions))
        chats.add(0, chat)
        // Save right away: a chat stuck at sign-in would otherwise vanish on the next restart.
        chat.onChanged?.invoke(chat)
        onRegistryChanged?.invoke() // before start(), so listeners see its first events
        chat.start()
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
        chat.close()
        chats.remove(chat)
        clearUrgent(chat.id)
        onRegistryChanged?.invoke()
        NotificationManagerCompat.from(context).cancel(chat.id.hashCode())
        val id = chat.id
        Drafts.set(context, id, "")
        io.execute { ChatStore.delete(appContext, id) }
        cleanup.launch { runCatching { Checkpoints.delete(appContext, id) } }
        RuntimeService.refresh(context)
    }

    private fun wire(chat: ChatSession) = chat.apply {
        listeners += dev.yurlungr.app.runtime.RuntimeStatus
        onChanged = { c ->
            val snapshot = c.snapshot() // taken on the main thread
            io.execute { ChatStore.write(appContext, snapshot) }
            // Most recently active first.
            val index = chats.indexOf(c)
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
        }
        onNeedsAttention = { c, what ->
            awaitingApproval += c.id
            clearUrgent(c.id)
            urgent += Urgent(c.id, c.agent.displayName, what)
            notify(c, "${c.agent.displayName} needs you", what)
        }
        onAttentionResolved = { c ->
            clearUrgent(c.id)
            if (awaitingApproval.remove(c.id)) {
                runCatching { NotificationManagerCompat.from(appContext).cancel(c.id.hashCode()) }
            }
        }
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
        val open = PendingIntent.getActivity(
            context,
            chat.id.hashCode(),
            Intent(context, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_CHAT_ID, chat.id)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, YurlungrApp.CHANNEL_ATTENTION)
            .setPriority(NotificationCompat.PRIORITY_HIGH) // heads-up on Android 7.x, before channels
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setSmallIcon(R.drawable.ic_stat_terminal)
            .setContentTitle(title)
            .setContentText(text.take(200))
            .setStyle(NotificationCompat.BigTextStyle().bigText(text.take(1000)))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(chat.id.hashCode(), notification) }
    }
}
