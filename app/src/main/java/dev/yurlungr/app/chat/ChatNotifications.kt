package dev.yurlungr.app.chat

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import dev.yurlungr.app.MainActivity
import dev.yurlungr.app.R
import dev.yurlungr.app.YurlungrApp

/**
 * Chat notifications you can act on without opening the app: Allow / Deny on an approval, and
 * Reply when a turn finishes while the app is in the background.
 */
object ChatNotifications {
    const val ACTION_ANSWER = "dev.yurlungr.app.action.ANSWER_PERMISSION"
    const val ACTION_REPLY = "dev.yurlungr.app.action.REPLY"
    const val EXTRA_CHAT = "chat"
    const val EXTRA_ITEM = "item"
    const val EXTRA_OPTION = "option"
    const val KEY_REPLY = "reply"

    fun id(chatId: String) = chatId.hashCode()

    private fun open(context: Context, chat: ChatSession) = PendingIntent.getActivity(
        context,
        chat.id.hashCode(),
        Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_CHAT_ID, chat.id)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun broadcast(context: Context, action: String, requestCode: Int, mutable: Boolean, extras: Intent.() -> Unit): PendingIntent {
        // Explicit: only this app's receiver gets it.
        val intent = Intent(context, ChatActionReceiver::class.java).setAction(action).apply(extras)
        // A reply's text is filled in by the system, so that one has to be mutable (Android 12+).
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (mutable && Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else if (mutable) 0 else PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context, requestCode, intent, flags)
    }

    /** The approval alert: heads-up, with the agent's own Allow / Deny choices as buttons. */
    @SuppressLint("MissingPermission") // targetSdk 28: notification permission isn't a runtime grant
    fun approval(context: Context, chat: ChatSession, item: ChatItem.Permission?, title: String, text: String) {
        val builder = NotificationCompat.Builder(context, YurlungrApp.CHANNEL_ATTENTION)
            .setPriority(NotificationCompat.PRIORITY_HIGH) // heads-up on Android 7.x, before channels
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setSmallIcon(R.drawable.ic_stat_terminal)
            .setContentTitle(title)
            .setContentText(text.take(200))
            .setStyle(NotificationCompat.BigTextStyle().bigText(text.take(1000)))
            .setContentIntent(open(context, chat))
            .setAutoCancel(true)
        if (item != null) for (option in buttons(item.options)) {
            builder.addAction(0, option.name.take(24), broadcast(context, ACTION_ANSWER, (chat.id + item.key + option.optionId).hashCode(), mutable = false) {
                putExtra(EXTRA_CHAT, chat.id)
                putExtra(EXTRA_ITEM, item.key)
                putExtra(EXTRA_OPTION, option.optionId)
            })
        }
        runCatching { NotificationManagerCompat.from(context).notify(id(chat.id), builder.build()) }
    }

    /**
     * The options worth a button: allow once and deny once (a notification has room for few, and
     * "always" choices deserve a look at the chat first). Falls back to the first allow and reject.
     */
    internal fun buttons(options: List<PermissionOption>): List<PermissionOption> {
        val allow = options.firstOrNull { it.kind == "allow_once" } ?: options.firstOrNull { it.isAllow }
        val deny = options.firstOrNull { it.kind == "reject_once" } ?: options.firstOrNull { it.kind.startsWith("reject") }
        return listOfNotNull(allow, deny)
    }

    /** A quiet note that a turn finished (no sound, no pop-up), with Reply. */
    @SuppressLint("MissingPermission")
    fun finished(context: Context, chat: ChatSession, summary: String) {
        val reply = NotificationCompat.Action.Builder(
            0,
            "Reply",
            broadcast(context, ACTION_REPLY, chat.id.hashCode(), mutable = true) { putExtra(EXTRA_CHAT, chat.id) },
        ).addRemoteInput(RemoteInput.Builder(KEY_REPLY).setLabel("Message ${chat.agent.displayName}").build())
            .setAllowGeneratedReplies(false)
            .build()
        val text = summary.ifBlank { "Done" }
        val notification = NotificationCompat.Builder(context, YurlungrApp.CHANNEL_DONE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setSmallIcon(R.drawable.ic_stat_terminal)
            .setContentTitle("${chat.agent.displayName} finished · ${chat.title}".take(80))
            .setContentText(text.take(200))
            .setStyle(NotificationCompat.BigTextStyle().bigText(text.take(1000)))
            .setContentIntent(open(context, chat))
            .addAction(reply)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(id(chat.id), notification) }
    }

    /** Sends [text] from a notification reply: now if the agent is free, after the turn if it's busy, after a restart if it stopped. */
    fun reply(chat: ChatSession, text: String) = chat.deliver(text, emptyList())
}

/** Handles the buttons on chat notifications. Not exported: only this app's notifications reach it. */
class ChatActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val chatId = intent.getStringExtra(ChatNotifications.EXTRA_CHAT) ?: return
        val reply = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(ChatNotifications.KEY_REPLY)?.toString()
        val pending = goAsync()
        val main = Handler(Looper.getMainLooper())
        var tries = 0
        // Started by the tap, the app may still be loading its saved chats.
        fun attempt() {
            val chat = ChatSessions.get(chatId)
            if (chat == null && !ChatSessions.loaded && tries++ < 30) {
                main.postDelayed(::attempt, 100)
                return
            }
            try {
                val nm = NotificationManagerCompat.from(context)
                if (chat == null) {
                    nm.cancel(ChatNotifications.id(chatId))
                    Toast.makeText(context, "That chat no longer exists", Toast.LENGTH_SHORT).show()
                    return
                }
                when (intent.action) {
                    ChatNotifications.ACTION_ANSWER -> {
                        val key = intent.getLongExtra(ChatNotifications.EXTRA_ITEM, -1)
                        val option = intent.getStringExtra(ChatNotifications.EXTRA_OPTION) ?: return
                        val open = chat.items.any { it.key == key && it is ChatItem.Permission && it.resolved == null }
                        if (open) chat.answerPermission(key, option)
                        else Toast.makeText(context, "That request was already answered", Toast.LENGTH_SHORT).show()
                        // Another prompt still waiting keeps the (re-posted) alert for it; only the last answer clears it.
                        if (chat.openPermission == null) nm.cancel(ChatNotifications.id(chatId))
                        else if (!open) chat.openPermission?.let { next ->
                            ChatNotifications.approval(context, chat, next, "${chat.agent.displayName} needs you", chat.attentionText(next))
                        }
                    }
                    ChatNotifications.ACTION_REPLY -> {
                        // Cancelling also ends the notification's "sending" spinner.
                        nm.cancel(ChatNotifications.id(chatId))
                        if (!reply.isNullOrBlank()) ChatNotifications.reply(chat, reply.trim())
                    }
                }
            } finally {
                pending.finish()
            }
        }
        attempt()
    }
}
