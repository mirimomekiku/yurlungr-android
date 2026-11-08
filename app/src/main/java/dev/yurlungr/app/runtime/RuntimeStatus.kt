package dev.yurlungr.app.runtime

import android.content.Context
import android.util.Log
import dev.yurlungr.app.chat.ChatItem
import dev.yurlungr.app.chat.ChatListener
import dev.yurlungr.app.chat.ChatSession
import dev.yurlungr.app.chat.ChatSessions
import dev.yurlungr.app.chat.ChatState
import dev.yurlungr.app.terminal.TerminalSessions
import java.io.File
import java.util.concurrent.Executors

/** Non-secret app state for the MOTD. No chat messages, account identities or credentials are exported. */
object RuntimeStatus : ChatListener {
    private var appContext: Context? = null
    private val io = Executors.newSingleThreadExecutor { task -> Thread(task, "runtime-status").apply { isDaemon = true } }

    fun init(context: Context) {
        appContext = context.applicationContext
        refresh()
    }

    /** Read Compose-backed registries on the main thread; only immutable text crosses to IO. */
    fun refresh() {
        val context = appContext ?: return
        if (!RuntimePaths(context).isBootstrapInstalled) return
        val text = formatRuntimeStatus(
            TerminalSessions.sessions.mapIndexed { index, session ->
                MotdSession(TerminalSessions.statusId(session), index + 1, TerminalSessions.title(session, index))
            },
            working = ChatSessions.chats.count { it.state == ChatState.Running || it.state == ChatState.Starting },
            idle = ChatSessions.chats.count { it.state == ChatState.Ready },
            approvals = ChatSessions.chats.sumOf { chat -> chat.items.count { it is ChatItem.Permission && it.resolved == null } },
        )
        io.execute {
            runCatching {
                val directory = File(RuntimePaths(context).prefix, "var/run/yurlungr").apply { mkdirs() }
                val temporary = File(directory, ".status.tmp")
                temporary.writeText(text)
                check(temporary.renameTo(File(directory, "status.tsv")))
            }.onFailure { Log.w("RuntimeStatus", "Couldn't publish terminal status", it) }
        }
    }

    override fun onStateChanged(chat: ChatSession) = refresh()
    override fun onItemAdded(chat: ChatSession, item: ChatItem) { if (item is ChatItem.Permission) refresh() }
    override fun onItemUpdated(chat: ChatSession, item: ChatItem) { if (item is ChatItem.Permission) refresh() }
}

internal data class MotdSession(val id: String, val number: Int, val name: String)

internal fun formatRuntimeStatus(sessions: List<MotdSession>, working: Int, idle: Int, approvals: Int): String = buildString {
    for (session in sessions) {
        // A session name must never inject terminal controls or extra TSV rows.
        val safeName = session.name.filter { it >= ' ' && it != '\u007f' && it !in '\u0080'..'\u009f' }.take(32)
        append("session\t${session.id}\t${session.number}\t${sessions.size}\t$safeName\n")
    }
    append("agents\t$working\t$idle\t$approvals\n")
}
