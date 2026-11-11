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

/**
 * "Finished" notifications: a terminal command that ran for a while (a build, an install, tests)
 * and `yurlungr-notify` from scripts and agents. While the app is open, a short toast instead.
 */
object TaskNotifications {
    /** Commands quicker than this don't notify: you were probably watching. */
    const val MIN_SECONDS = 20

    /** A terminal command ended: "exit\nseconds\nsession id" from the command wrapper. */
    fun commandDone(context: Context, value: String) {
        val (exit, seconds, id) = value.lines().let { Triple(it.getOrNull(0)?.toIntOrNull() ?: return, it.getOrNull(1)?.toLongOrNull() ?: 0, it.getOrNull(2).orEmpty()) }
        val index = TerminalSessions.sessions.indexOfFirst { TerminalSessions.statusId(it) == id }
        val name = TerminalSessions.sessions.getOrNull(index)?.let { TerminalSessions.title(it, index) } ?: "Command"
        val took = if (seconds >= 60) "${seconds / 60} min ${seconds % 60} s" else "$seconds s"
        post(context, if (exit == 0) "$name finished" else "$name failed (exit $exit)", "Took $took. Tap to see the output.", id)
    }

    /** `yurlungr-notify "title" "text"`: first line is the title. */
    fun notify(context: Context, value: String) {
        val title = value.substringBefore('\n').take(120).ifBlank { return }
        post(context, title, value.substringAfter('\n', "").take(400), null)
    }

    @SuppressLint("MissingPermission") // targetSdk 28: notifications need no runtime grant
    private fun post(context: Context, title: String, text: String, terminalId: String?) {
        if (ChatSessions.appVisible) {
            Toast.makeText(context, title, Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .apply { terminalId?.let { putExtra(MainActivity.EXTRA_TERMINAL_ID, it) } }
        val open = PendingIntent.getActivity(context, (terminalId ?: title).hashCode(), intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(context, YurlungrApp.CHANNEL_TASKS)
            .setSmallIcon(dev.yurlungr.app.R.drawable.ic_stat_terminal)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify((terminalId ?: title).hashCode(), notification) }
    }
}
