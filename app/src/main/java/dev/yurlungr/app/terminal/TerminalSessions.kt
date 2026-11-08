package dev.yurlungr.app.terminal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import dev.yurlungr.app.runtime.RuntimeEnv
import dev.yurlungr.app.runtime.RuntimePaths
import dev.yurlungr.app.runtime.RuntimeService

/**
 * App-wide registry of terminal sessions. Lives as long as the process; [RuntimeService]
 * keeps the process alive while sessions exist. All calls must be on the main thread
 * (TerminalSession posts its callbacks to the main looper).
 */
object TerminalSessions {
    private const val TRANSCRIPT_ROWS = 5000

    val sessions = mutableStateListOf<TerminalSession>()
    var current by mutableStateOf<TerminalSession?>(null)
        private set

    /** Bumped on title changes / exits so Compose re-reads session state. */
    var changeTick by mutableIntStateOf(0)
        private set

    private lateinit var appContext: Context
    val client = SessionClient()

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /** Custom tab names (e.g. "Claude login"); otherwise the shell-set title is used. */
    private val names = HashMap<TerminalSession, String>()
    private val commands = HashMap<TerminalSession, String?>()
    fun endProcess(session: TerminalSession) { session.finishIfRunning() }

    fun rename(session: TerminalSession, name: String) {
        if (session !in sessions) return
        if (name.isBlank()) names.remove(session) else names[session] = name.trim()
        changeTick++
    }

    fun restart(context: Context, session: TerminalSession) {
        if (session !in sessions) return
        val command = commands[session]
        val name = names[session]
        close(context, session)
        create(context, command, name)
    }

    /**
     * Opens a terminal tab. With [command], runs it in a login bash and then leaves an
     * interactive shell open so the output stays readable.
     */
    fun create(context: Context, command: String? = null, name: String? = null): TerminalSession {
        val paths = RuntimePaths(context)
        val (shell, args) = if (command == null) {
            // Like Termux: prefer $PREFIX/bin/login (sets up LD_PRELOAD, motd), run as a login shell.
            val login = if (paths.login.canExecute()) paths.login else paths.bash
            login to arrayOf("-${login.name}")
        } else {
            paths.bash to arrayOf("-bash", "-c", "$command\nexec bash -l")
        }
        val session = TerminalSession(
            shell.path,
            paths.home.path,
            args,
            RuntimeEnv.array(paths),
            TRANSCRIPT_ROWS,
            client,
        )
        commands[session] = command
        if (name != null) names[session] = name
        sessions += session
        current = session
        RuntimeService.refresh(context)
        return session
    }

    fun select(session: TerminalSession) {
        if (session in sessions) current = session
    }

    fun close(context: Context, session: TerminalSession) {
        session.finishIfRunning()
        sessions.remove(session)
        names.remove(session)
        commands.remove(session)
        if (current === session) current = sessions.lastOrNull()
        RuntimeService.refresh(context)
    }

    fun closeAll(context: Context) {
        sessions.toList().forEach { close(context, it) }
    }

    fun title(session: TerminalSession, index: Int): String =
        names[session] ?: session.title?.takeIf { it.isNotBlank() } ?: "shell ${index + 1}"

    class SessionClient : TerminalSessionClient {
        /** The view currently showing [current]; set/cleared by the terminal screen. */
        var view: TerminalView? = null

        override fun onTextChanged(changedSession: TerminalSession) {
            view?.takeIf { it.currentSession === changedSession }?.onScreenUpdated()
        }

        override fun onTitleChanged(changedSession: TerminalSession) {
            changeTick++
        }

        override fun onSessionFinished(finishedSession: TerminalSession) {
            changeTick++
        }

        override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
            val cm = appContext.getSystemService(ClipboardManager::class.java)
            cm.setPrimaryClip(ClipData.newPlainText("terminal", text))
        }

        override fun onPasteTextFromClipboard(session: TerminalSession) {
            val cm = appContext.getSystemService(ClipboardManager::class.java)
            val text = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(appContext)
            if (!text.isNullOrEmpty()) session.emulator?.paste(text.toString())
        }

        override fun onBell(session: TerminalSession) = Unit

        override fun onColorsChanged(session: TerminalSession) {
            view?.invalidate()
        }

        override fun onTerminalCursorStateChange(state: Boolean) {
            view?.setTerminalCursorBlinkerState(state, true)
        }

        override fun getTerminalCursorStyle(): Int? = null // emulator default (block)

        override fun logError(tag: String, message: String) { Log.e(tag, message) }
        override fun logWarn(tag: String, message: String) { Log.w(tag, message) }
        override fun logInfo(tag: String, message: String) { Log.i(tag, message) }
        override fun logDebug(tag: String, message: String) { Log.d(tag, message) }
        override fun logVerbose(tag: String, message: String) { Log.v(tag, message) }
        override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) { Log.e(tag, message, e) }
        override fun logStackTrace(tag: String, e: Exception) { Log.e(tag, "error", e) }
    }
}
