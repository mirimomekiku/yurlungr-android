package dev.yurlungr.app.runtime

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.yurlungr.app.MainActivity
import dev.yurlungr.app.R
import dev.yurlungr.app.YurlungrApp
import dev.yurlungr.app.chat.ChatSessions
import dev.yurlungr.app.remote.RemoteServer
import dev.yurlungr.app.terminal.TerminalSessions

/**
 * Foreground service that keeps the process (and therefore every runtime process it
 * spawned) alive while the UI is in the background. Optional wake lock for long jobs
 * such as `npm install` or Gradle builds with the screen off.
 */
class RuntimeService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_EXIT -> {
                ChatSessions.stopAll()
                // Otherwise a paired phone could start agents again right after Exit. Sharing stays
                // enabled in settings, so it comes back when the app is next opened.
                dev.yurlungr.app.remote.RemoteServer.stop()
                dev.yurlungr.app.web.WebRunSessions.stopAll(this)
                dev.yurlungr.app.databases.ProjectDatabases.stopAll(this)
                TerminalSessions.closeAll(this)
                // Exit means nothing should keep the phone awake or the service alive.
                wakeLock?.takeIf { it.isHeld }?.release()
                wakeLock = null
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_TOGGLE_WAKE_LOCK -> toggleWakeLock()
        }
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
        )
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        super.onDestroy()
    }

    // Held deliberately until the user turns it off (a toggle), and released in onDestroy.
    @SuppressLint("WakelockTimeout", "Wakelock")
    private fun toggleWakeLock() {
        val held = wakeLock?.isHeld == true
        if (held) {
            wakeLock?.release()
            wakeLock = null
        } else {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "yurlungr:runtime")
                .apply { acquire() }
        }
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val wakeLockHeld = wakeLock?.isHeld == true
        return NotificationCompat.Builder(this, YurlungrApp.CHANNEL_RUNTIME)
            .setSmallIcon(R.drawable.ic_stat_terminal)
            .setContentTitle("Runtime active")
            .setContentText(
                buildList {
                    val chats = ChatSessions.activeCount
                    val terminals = TerminalSessions.sessions.count { it.isRunning }
                    if (chats > 0) add(if (chats == 1) "1 chat" else "$chats chats")
                    if (terminals > 0) add(if (terminals == 1) "1 terminal" else "$terminals terminals")
                    if (RemoteServer.isRunning) add("sharing with ${RemoteServer.clientCount} device(s)")
                    val databases = dev.yurlungr.app.databases.ProjectDatabases.running.size
                    if (databases > 0) add(if (databases == 1) "1 database" else "$databases databases")
                    if (wakeLockHeld) add("wake lock held")
                }.joinToString(" · "),
            )
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .addAction(0, if (wakeLockHeld) "Release wake lock" else "Acquire wake lock", selfIntent(ACTION_TOGGLE_WAKE_LOCK, 1))
            .addAction(0, "Exit", selfIntent(ACTION_EXIT, 2))
            .build()
    }

    private fun selfIntent(action: String, requestCode: Int): PendingIntent = PendingIntent.getService(
        this, requestCode, Intent(this, RuntimeService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val ACTION_EXIT = "dev.yurlungr.app.action.EXIT"
        private const val ACTION_TOGGLE_WAKE_LOCK = "dev.yurlungr.app.action.TOGGLE_WAKE_LOCK"
        /** Starts/refreshes the service while chats, terminals or project databases run, stops it when none are left. */
        fun refresh(context: Context) {
            RuntimeStatus.refresh()
            val intent = Intent(context, RuntimeService::class.java)
            if (ChatSessions.activeCount > 0 || TerminalSessions.sessions.any { it.isRunning } || RemoteServer.isRunning ||
                dev.yurlungr.app.databases.ProjectDatabases.anyRunning
            ) {
                ContextCompat.startForegroundService(context, intent)
            } else {
                context.stopService(intent)
            }
        }
    }
}
