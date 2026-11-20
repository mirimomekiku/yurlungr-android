package dev.yurlungr.app.runtime

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.yurlungr.app.MainActivity
import dev.yurlungr.app.YurlungrApp
import dev.yurlungr.app.chat.ChatSessions
import dev.yurlungr.app.terminal.TerminalSessions
import java.util.concurrent.atomic.AtomicInteger

/**
 * Which notification id each kind of notice gets. Android keeps at most 50 notifications per app
 * and drops the oldest past that, so anything the runtime can post at will must reuse a few ids:
 * otherwise a script calling `yurlungr-notify` in a loop pushes out the agents' "Needs your
 * answer" alerts. Bases sit far from the small ids the app uses elsewhere (the runtime service's 1).
 */
internal object NoticeIds {
    /** `yurlungr-notify` notices: the newest [RUNTIME_POOL] stay, older ones are replaced. */
    const val RUNTIME_BASE = 0x5952_0100
    const val RUNTIME_POOL = 5
    /** Links a tool asked to open while the app was in the background. */
    const val OPEN_BASE = 0x5952_0200
    /** Install prompts for APKs while the app was in the background. */
    const val INSTALL_BASE = 0x5952_0300
    /** Local files a tool asked to open while the app was in the background. */
    const val FILE_BASE = 0x5952_0400
    const val PROMPT_POOL = 3

    /** Groups the runtime's notices in the shade, apart from the app's own. */
    const val RUNTIME_GROUP = "dev.yurlungr.runtime-notices"

    /** The [seq]th id of a pool of [size] starting at [base]: ids cycle, so a pool never grows. */
    fun pooled(base: Int, size: Int, seq: Int): Int = base + Math.floorMod(seq, size)

    /** A terminal command's "finished" notice: one per terminal tab, replaced by its next one. */
    fun done(terminalId: String): Int = ("done:$terminalId").hashCode()
}

/**
 * "Finished" notifications: a terminal command that ran for a while (a build, an install, tests)
 * and `yurlungr-notify` from scripts and agents. While the app is open, a short toast instead.
 */
object TaskNotifications {
    /** Commands quicker than this don't notify: you were probably watching. */
    const val MIN_SECONDS = 20

    private val runtimeSeq = AtomicInteger()

    /** A terminal command ended: "exit\nseconds\nsession id" from the command wrapper. */
    fun commandDone(context: Context, value: String) {
        val (exit, seconds, id) = value.lines().let { Triple(it.getOrNull(0)?.toIntOrNull() ?: return, it.getOrNull(1)?.toLongOrNull() ?: 0, it.getOrNull(2).orEmpty()) }
        val index = TerminalSessions.sessions.indexOfFirst { TerminalSessions.statusId(it) == id }
        val name = TerminalSessions.sessions.getOrNull(index)?.let { TerminalSessions.title(it, index) } ?: "Command"
        val took = if (seconds >= 60) "${seconds / 60} min ${seconds % 60} s" else "$seconds s"
        val killed = PhantomHint.forExit(exit)
        post(
            context,
            when {
                exit == 0 -> "$name finished"
                killed != null -> "$name was stopped by Android"
                else -> "$name failed (exit $exit)"
            },
            killed ?: "Took $took. Tap to see the output.",
            NoticeIds.done(id),
            terminalId = id,
            kind = if (exit == 0) dev.yurlungr.app.ui.Feedback.Kind.Done else dev.yurlungr.app.ui.Feedback.Kind.Failed,
            fixInChecks = killed != null,
        )
    }

    /** The app's own notice (e.g. a device paired): first line is the title. */
    fun notify(context: Context, value: String) {
        val title = value.substringBefore('\n').take(120).ifBlank { return }
        post(context, title, value.substringAfter('\n', "").take(400), title.hashCode())
    }

    /** Marks notices that scripts and agents asked for, so they can't pass as the app's own. */
    const val RUNTIME_LABEL = "From terminal/agent"

    /**
     * `yurlungr-notify "title" "text"` from inside the runtime: first line is the title. Labelled
     * as coming from the runtime (title prefix and subtext), since any agent can post one. Uses a
     * small pool of ids (see [NoticeIds]), so it can never crowd out other notifications.
     */
    fun notifyFromRuntime(context: Context, value: String) {
        val (title, text) = runtimeNotice(value) ?: return
        val id = NoticeIds.pooled(NoticeIds.RUNTIME_BASE, NoticeIds.RUNTIME_POOL, runtimeSeq.getAndIncrement())
        post(context, title, text, id, subText = RUNTIME_LABEL, group = NoticeIds.RUNTIME_GROUP)
    }

    /** Title and text for a runtime notice, the title labelled; null if it has no title. */
    internal fun runtimeNotice(value: String): Pair<String, String>? {
        val title = value.substringBefore('\n').trim().take(120).ifBlank { return null }
        return "$RUNTIME_LABEL: $title" to value.substringAfter('\n', "").take(400)
    }

    /** What a background request is for: its pool of ids. */
    enum class Prompt(internal val base: Int) {
        Open(NoticeIds.OPEN_BASE),
        Install(NoticeIds.INSTALL_BASE),
        File(NoticeIds.FILE_BASE);

        internal val seq = AtomicInteger()
    }

    /**
     * A request from the runtime that needs the screen (an install prompt, a link, a file to open)
     * while the app is in the background, where Android doesn't let an app start activities: a
     * notification whose tap does it instead. [timeoutMs]: when the request goes stale (a staged
     * copy is cleaned up), 0 for never.
     */
    @SuppressLint("MissingPermission") // targetSdk 28: notifications need no runtime grant
    fun prompt(context: Context, kind: Prompt, title: String, text: String, action: Intent, timeoutMs: Long = 0) {
        val id = NoticeIds.pooled(kind.base, NoticeIds.PROMPT_POOL, kind.seq.getAndIncrement())
        val tap = PendingIntent.getActivity(
            context, id, action.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, YurlungrApp.CHANNEL_TASKS)
            .setSmallIcon(dev.yurlungr.app.R.drawable.ic_stat_terminal)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSubText(RUNTIME_LABEL)
            .setGroup(NoticeIds.RUNTIME_GROUP)
            .setContentIntent(tap)
            .setAutoCancel(true)
            .apply { if (timeoutMs > 0) setTimeoutAfter(timeoutMs) }
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(id, notification) }
    }

    @SuppressLint("MissingPermission") // targetSdk 28: notifications need no runtime grant
    private fun post(
        context: Context,
        title: String,
        text: String,
        id: Int,
        terminalId: String? = null,
        kind: dev.yurlungr.app.ui.Feedback.Kind = dev.yurlungr.app.ui.Feedback.Kind.Done,
        subText: String? = null,
        group: String? = null,
        /** Adds "Fix" opening System checks (Android killed the process). */
        fixInChecks: Boolean = false,
    ) {
        if (ChatSessions.appVisible) {
            Toast.makeText(context, if (fixInChecks) "$title. $text" else title, if (fixInChecks) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
            dev.yurlungr.app.ui.Feedback.signal(context, kind)
            return
        }
        val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .apply { terminalId?.let { putExtra(MainActivity.EXTRA_TERMINAL_ID, it) } }
        val open = PendingIntent.getActivity(context, id, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(context, YurlungrApp.CHANNEL_TASKS)
            .setSmallIcon(dev.yurlungr.app.R.drawable.ic_stat_terminal)
            .setContentTitle(title)
            .setContentText(text)
            .apply { subText?.let { setSubText(it) } }
            .apply { group?.let { setGroup(it) } }
            .apply {
                if (fixInChecks) {
                    setStyle(NotificationCompat.BigTextStyle().bigText(text))
                    val checks = dev.yurlungr.app.widgets.OpenIn.intent(context, dev.yurlungr.app.widgets.OpenIn.SYSTEM_CHECKS)
                    addAction(0, "Fix", PendingIntent.getActivity(context, id + 1, checks, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
                }
            }
            .setContentIntent(open)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(id, notification) }
    }
}
