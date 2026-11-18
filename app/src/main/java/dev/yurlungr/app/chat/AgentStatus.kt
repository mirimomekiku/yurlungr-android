package dev.yurlungr.app.chat

import android.content.Context
import android.os.Handler
import android.os.Looper
import dev.yurlungr.app.widgets.OpenIn

/**
 * What the agents are doing, for the Quick Settings tile and the home-screen widget, which are
 * refreshed (at most a few times a second) whenever a chat starts, stops or needs an answer.
 */
object AgentStatus : ChatListener {
    data class Summary(val working: Int, val ready: Int, val needsYou: Int) {
        val active: Boolean get() = working + ready + needsYou > 0
    }

    // The application context only (set from changed()), which lives as long as the process.
    @android.annotation.SuppressLint("StaticFieldLeak")
    private var app: Context? = null
    private val main = Handler(Looper.getMainLooper())
    private val refresh = Runnable { app?.let(OpenIn::refreshAgents) }

    /** Main thread only (reads the chat registry). */
    fun summary(): Summary {
        val chats = ChatSessions.chats.toList()
        return Summary(
            working = chats.count { it.state == ChatState.Running || it.state == ChatState.Starting },
            ready = chats.count { it.state == ChatState.Ready },
            needsYou = ChatSessions.urgent.map { it.chatId }.distinct().size +
                chats.count { it.state == ChatState.AuthRequired },
        )
    }

    fun label(s: Summary): String = when {
        !s.active -> "None running"
        else -> listOfNotNull(
            s.needsYou.takeIf { it > 0 }?.let { "$it need${if (it == 1) "s" else ""} you" },
            s.working.takeIf { it > 0 }?.let { "$it working" },
            s.ready.takeIf { it > 0 && s.working == 0 && s.needsYou == 0 }?.let { "$it idle" },
        ).joinToString(" · ")
    }

    /** The chat to open from the tile or widget: the oldest one waiting for an answer, if any. */
    fun chatNeedingYou(): String? =
        ChatSessions.urgent.firstOrNull()?.chatId ?: ChatSessions.chats.firstOrNull { it.state == ChatState.AuthRequired }?.id

    fun changed(context: Context) {
        app = context.applicationContext
        main.removeCallbacks(refresh)
        main.postDelayed(refresh, 400)
    }

    override fun onStateChanged(chat: ChatSession) {
        app?.let(::changed)
    }
}
