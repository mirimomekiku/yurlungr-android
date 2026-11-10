package dev.yurlungr.app.chat

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.mutableStateListOf
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.yurlungr.app.MainActivity
import dev.yurlungr.app.R
import dev.yurlungr.app.YurlungrApp
import dev.yurlungr.app.agents.Agent
import dev.yurlungr.app.runtime.RuntimeService
import java.io.File
import java.util.concurrent.Executors

/**
 * App-wide registry of chats, saved ones included. Main thread only. Chats with a running
 * agent keep [RuntimeService] in the foreground; saved chats cost nothing until opened.
 */
object ChatSessions {
    val chats = mutableStateListOf<ChatSession>()

    /** True while an activity is visible; agent alerts only notify when it isn't. */
    @Volatile var appVisible = false

    /** Chats were added or removed (the remote server re-attaches and re-broadcasts). */
    var onRegistryChanged: (() -> Unit)? = null

    private lateinit var appContext: Context
    /** Single thread: saves of the same chat land in order. */
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "chat-store").apply { isDaemon = true } }

    /** Loads saved chats (history only; agents start when a chat is opened). */
    fun init(context: Context) {
        appContext = context.applicationContext
        if (chats.isNotEmpty()) return
        for (saved in ChatStore.readAll(appContext)) {
            val agent = Agent.entries.firstOrNull { it.name == saved.agent } ?: continue
            chats += wire(ChatSession(appContext, agent, File(saved.cwd), saved))
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

    /** Ends the agent process but keeps the chat. */
    fun stop(chat: ChatSession) = chat.stop()

    /** Ends every agent process (notification "Exit"); history is kept. */
    fun stopAll() = chats.filter { it.isActive || it.state != ChatState.Saved }.forEach { it.stop() }

    fun delete(context: Context, chat: ChatSession) {
        chat.close()
        chats.remove(chat)
        onRegistryChanged?.invoke()
        NotificationManagerCompat.from(context).cancel(chat.id.hashCode())
        val id = chat.id
        io.execute { ChatStore.delete(appContext, id) }
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
            awaitingApproval -= c.id // replaced by this notification (same id)
            notify(c, "${c.agent.displayName} finished", summary.ifBlank { "Turn complete" })
        }
        onNeedsAttention = { c, what ->
            awaitingApproval += c.id
            notify(c, "${c.agent.displayName} needs you", what)
        }
        onAttentionResolved = { c ->
            if (awaitingApproval.remove(c.id)) {
                runCatching { NotificationManagerCompat.from(appContext).cancel(c.id.hashCode()) }
            }
        }
    }

    /** Chats whose current notification asks for an approval (cleared once it's answered). */
    private val awaitingApproval = mutableSetOf<String>()

    @SuppressLint("MissingPermission") // targetSdk 28: notification permission isn't a runtime grant
    private fun notify(chat: ChatSession, title: String, text: String) {
        if (appVisible) return
        val context = appContext
        val open = PendingIntent.getActivity(
            context,
            chat.id.hashCode(),
            Intent(context, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_CHAT_ID, chat.id)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, YurlungrApp.CHANNEL_AGENTS)
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
