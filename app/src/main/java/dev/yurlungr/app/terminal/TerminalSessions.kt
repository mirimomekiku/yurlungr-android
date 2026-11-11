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
import dev.yurlungr.app.runtime.RuntimeStatus
import java.util.UUID
import java.io.File

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
    private val statusIds = HashMap<TerminalSession, String>()
    private val cwds = HashMap<TerminalSession, File>()
    private val startedAt = HashMap<TerminalSession, Long>()
    private val finishedAt = HashMap<TerminalSession, Long>()
    /** Relaunches waiting for their old process to exit (see [restart]). */
    private val pendingRestarts = HashMap<TerminalSession, () -> Unit>()
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private val managed = HashMap<TerminalSession, ManagedLifecycle>()
    data class ManagedLifecycle(val end: () -> Unit, val close: () -> Unit, val restart: () -> Unit, val exited: () -> Unit)
    fun manage(session: TerminalSession, lifecycle: ManagedLifecycle) { managed[session] = lifecycle }
    fun endProcess(session: TerminalSession) { managed[session]?.end?.invoke() ?: stopProcess(session) }
    fun statusId(session: TerminalSession): String = statusIds[session].orEmpty()

    /** An app-launched command (setup, sign-in, clone…), as opposed to a plain shell. */
    fun isCommand(session: TerminalSession): Boolean = commands[session] != null

    /** The process ended within a few seconds of starting: it most likely never ran at all. */
    fun exitedAtStart(session: TerminalSession): Boolean {
        val start = startedAt[session] ?: return false
        val end = finishedAt[session] ?: return false
        return end - start < 3_000
    }

    /**
     * Stops the session's shell and everything it started (jobs, apt, npm, node…), like closing
     * a terminal window. Never uses TerminalSession.finishIfRunning(): for a session whose process
     * never started its pid is 0, and kill(0, SIGKILL) would kill this app's whole process group.
     */
    private fun stopProcess(session: TerminalSession) {
        if (!session.isRunning) return
        val pid = session.pid
        if (pid <= 0) return // never started: nothing to stop
        // termux.c calls setsid(), so the shell's pid is also its process group id.
        dev.yurlungr.app.runtime.ProcessTree.terminate(pid, alsoGroup = true)
    }

    fun rename(session: TerminalSession, name: String) {
        if (session !in sessions) return
        if (name.isBlank()) names.remove(session) else names[session] = name.trim()
        changeTick++
        RuntimeStatus.refresh()
    }

    /**
     * Reruns the session's command (or shell) in the same place in the tab list, in the same
     * folder, once the old process has exited: a setup restarted while its predecessor still
     * holds the setup lock would fail with "Another setup is already running".
     */
    fun restart(context: Context, session: TerminalSession) {
        if (session !in sessions) return
        managed[session]?.let { it.restart(); return }
        val command = commands[session]
        val name = names[session]
        val cwd = cwds[session]
        val relaunch = {
            val index = sessions.indexOf(session).takeIf { it >= 0 }
            closeDirect(context, session)
            runCatching { create(context, command, name, workingDirectory = cwd, index = index) }.onFailure {
                Log.w("TerminalSessions", "Restart failed", it)
                android.widget.Toast.makeText(context, "Couldn't restart: ${it.message}", android.widget.Toast.LENGTH_LONG).show()
            }
            Unit
        }
        if (!session.isRunning || session.pid <= 0) {
            relaunch()
            return
        }
        pendingRestarts[session] = relaunch
        stopProcess(session)
        // If the exit callback never comes (a process that won't die), relaunch anyway.
        main.postDelayed({ pendingRestarts.remove(session)?.invoke() }, 4_000)
    }

    /**
     * Opens a terminal tab. With [command], runs it in a login bash and then leaves an
     * interactive shell open so the output stays readable.
     */
    fun create(
        context: Context,
        command: String? = null,
        name: String? = null,
        keepShell: Boolean = true,
        workingDirectory: File? = null,
        /** Position in the tab list (a restarted tab keeps its place); appended when null. */
        index: Int? = null,
    ): TerminalSession {
        val paths = RuntimePaths(context)
        // Before (or during a re)install there's no shell to run: say so instead of "exited with code 1".
        check(paths.isBootstrapInstalled) { "The Linux environment isn't installed (or is being reinstalled). Open System checks first." }
        val statusId = UUID.randomUUID().toString()
        val cwd = workingDirectory ?: paths.home
        // The child would otherwise print an error and carry on in "/".
        check(cwd.isDirectory) { "The folder ${cwd.path} no longer exists." }
        val welcome = "if [ ! -f \"\$HOME/.hushlogin\" ] && [ -z \"\$TERMUX_HUSHLOGIN\" ] && [ -f '${paths.libexec}/motd.sh' ]; then '${paths.bash}' '${paths.libexec}/motd.sh'; fi"
        val (shell, args) = if (command == null) {
            // Like Termux: prefer $PREFIX/bin/login (sets up LD_PRELOAD, motd), run as a login shell.
            val login = if (paths.login.canExecute()) paths.login else paths.bash
            login to arrayOf("-${login.name}")
        } else {
            val status = "_yurlungr_exit=\$?; if [ \"\$_yurlungr_exit\" -ne 0 ]; then printf '\\nCommand failed (exit %s). Review the output above before retrying.\\n' \"\$_yurlungr_exit\"; fi"
            // Plain (non-login) bash for the command: the environment already comes from RuntimeEnv, and a
            // profile that exits, execs another shell or starts tmux must not hijack setups and sign-ins.
            // The interactive shell left open afterwards is a normal login shell. Packages' own
            // profile.d scripts (JAVA_HOME…) are still loaded, so commands see what terminals see.
            val profiled = "${dev.yurlungr.app.runtime.RuntimeEnv.PROFILE_SNIPPET}\n$command"
            paths.bash to arrayOf("bash", "-c", if (keepShell) "$profiled\n$status\n$welcome\nexec bash -l" else profiled)
        }
        val session = TerminalSession(
            shell.path,
            cwd.path,
            args,
            (RuntimeEnv.map(paths, cwd) + ("YURLUNGR_SESSION_ID" to statusId)).map { (key, value) -> "$key=$value" }.toTypedArray(),
            TRANSCRIPT_ROWS,
            client,
        )
        statusIds[session] = statusId
        commands[session] = command
        cwds[session] = cwd
        startedAt[session] = System.currentTimeMillis()
        if (name != null) names[session] = name
        if (index != null && index in 0..sessions.size) sessions.add(index, session) else sessions += session
        current = session
        RuntimeService.refresh(context)
        return session
    }

    fun select(session: TerminalSession) {
        if (session in sessions) current = session
    }

    fun close(context: Context, session: TerminalSession) {
        managed[session]?.let { it.close(); return }
        closeDirect(context,session)
    }

    /** Used after a managed process has finished its own cleanup. */
    fun closeDirect(context: Context, session: TerminalSession) {
        managed.remove(session)
        stopProcess(session)
        sessions.remove(session)
        names.remove(session)
        commands.remove(session)
        statusIds.remove(session)
        cwds.remove(session)
        startedAt.remove(session)
        finishedAt.remove(session)
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
            RuntimeStatus.refresh()
        }

        override fun onSessionFinished(finishedSession: TerminalSession) {
            finishedAt[finishedSession] = System.currentTimeMillis()
            managed[finishedSession]?.exited?.invoke()
            pendingRestarts.remove(finishedSession)?.invoke()
            changeTick++
            RuntimeStatus.refresh()
            // An exited tab no longer needs the foreground service (or a place in its count).
            RuntimeService.refresh(appContext)
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
