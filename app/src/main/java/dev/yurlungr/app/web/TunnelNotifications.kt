package dev.yurlungr.app.web

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.yurlungr.app.MainActivity
import dev.yurlungr.app.R
import dev.yurlungr.app.YurlungrApp
import dev.yurlungr.app.widgets.OpenIn

/**
 * An ongoing notification while any public link is up, so a link isn't forgotten once you leave the
 * Servers screen: what's shared, when it stops by itself, and Stop.
 */
internal object TunnelNotifications {
    const val ACTION_STOP = "dev.yurlungr.app.action.STOP_PUBLIC_LINKS"
    private const val ID = 0x7a11

    /** The notification's title and text for [tunnels] (the active ones), or null for none. */
    fun describe(tunnels: List<Tunnel>, now: Long): Pair<String, String>? {
        if (tunnels.isEmpty()) return null
        val title = if (tunnels.size == 1) "Public link on · port ${tunnels[0].port}" else "${tunnels.size} public links on"
        val soonest = tunnels.mapNotNull { it.expiresAt }.minOrNull()
        val names = tunnels.joinToString(", ") { it.label }
        val ends = when {
            tunnels.any { it.state != TunnelState.Live } -> "Connecting"
            tunnels.all { it.expiresAt == null } -> "Until you stop it"
            else -> TunnelLinks.remaining(now, soonest) ?: ""
        }
        return title to "$names · $ends"
    }

    @SuppressLint("MissingPermission") // targetSdk 28: notification permission isn't a runtime grant
    fun update(context: Context) {
        val app = context.applicationContext
        val active = TunnelSessions.live()
        val (title, text) = describe(active, System.currentTimeMillis()) ?: return cancel(app)
        val open = PendingIntent.getActivity(
            app, ID,
            Intent(app, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN, OpenIn.SERVERS).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        // Explicit: only this app's receiver gets it.
        val stop = PendingIntent.getBroadcast(
            app, ID, Intent(app, TunnelActionReceiver::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = NotificationCompat.Builder(app, YurlungrApp.CHANNEL_RUNTIME)
            .setSmallIcon(R.drawable.ic_stat_terminal)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .addAction(0, "Stop", stop)
        // The system counts down to the soonest end, so the text needn't be refreshed every minute.
        val soonest = active.mapNotNull { it.expiresAt }.minOrNull()
        if (soonest != null) builder.setWhen(soonest).setShowWhen(true).setUsesChronometer(true).setChronometerCountDown(true)
        else builder.setShowWhen(false)
        runCatching { NotificationManagerCompat.from(app).notify(ID, builder.build()) }
    }

    fun cancel(context: Context) {
        runCatching { NotificationManagerCompat.from(context).cancel(ID) }
    }
}

/** Stop on the public-link notification. Not exported: only this app's notification reaches it. */
class TunnelActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TunnelNotifications.ACTION_STOP) return
        val app = context.applicationContext
        // Started fresh by the tap, the process has no tunnels (they died with the old one): just clear it.
        TunnelSessions.stopAll(app)
        TunnelNotifications.cancel(app)
    }
}
