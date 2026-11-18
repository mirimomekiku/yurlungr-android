package dev.yurlungr.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import dev.yurlungr.app.chat.ChatSessions
import dev.yurlungr.app.remote.RemoteHosts
import dev.yurlungr.app.remote.RemoteServer
import dev.yurlungr.app.runtime.RuntimeAssets
import dev.yurlungr.app.runtime.RuntimeBridge
import dev.yurlungr.app.runtime.RuntimePaths
import dev.yurlungr.app.terminal.TerminalSessions

class YurlungrApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Before any UI: the first frame must already use the chosen light/dark theme.
        dev.yurlungr.app.ui.ThemePrefs.load(this)
        dev.yurlungr.app.ui.Feedback.load(this)
        dev.yurlungr.app.widgets.OpenIn.updateShortcuts(this)
        TerminalSessions.init(this)
        dev.yurlungr.app.web.WebRunSessions.init(this)
        ChatSessions.init(this)
        dev.yurlungr.app.runtime.RuntimeStatus.init(this)
        RemoteHosts.init(this)
        RemoteServer.init(this)
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).deleteNotificationChannel(CHANNEL_AGENTS)
            getSystemService(NotificationManager::class.java).createNotificationChannels(
                listOf(
                    NotificationChannel(CHANNEL_RUNTIME, "Runtime", NotificationManager.IMPORTANCE_LOW).apply {
                        description = "Shown while chats or terminal sessions are running"
                    },
                    // Heads-up with sound: only when an agent is waiting for the user's answer.
                    NotificationChannel(CHANNEL_ATTENTION, "Needs your answer", NotificationManager.IMPORTANCE_HIGH).apply {
                        description = "An agent is waiting for you to approve or answer"
                        enableVibration(true)
                    },
                    // Silent: a turn finishing needs no answer, but its Reply button saves opening the app.
                    NotificationChannel(CHANNEL_DONE, "Agent finished", NotificationManager.IMPORTANCE_LOW).apply {
                        description = "An agent finished while you were away, with a Reply button"
                    },
                    NotificationChannel(CHANNEL_TASKS, "Finished tasks", NotificationManager.IMPORTANCE_DEFAULT).apply {
                        description = "A long terminal command (build, install, tests) finished, or a script asked to tell you"
                    },
                ),
            )
        }
        onRuntimeReady()
    }

    /** Called at launch and again after the base system is installed. */
    fun onRuntimeReady() {
        val main = Handler(Looper.getMainLooper())
        Thread {
            runCatching { RuntimeAssets.install(this, RuntimePaths(this)) }
                .onFailure { Log.w("YurlungrApp", "Installing runtime assets failed", it) }
            main.post {
                RuntimeBridge.start(this)
                dev.yurlungr.app.runtime.RuntimeStatus.refresh()
            }
        }.start()
    }

    companion object {
        const val CHANNEL_RUNTIME = "runtime"
        const val CHANNEL_ATTENTION = "attention"
        const val CHANNEL_TASKS = "tasks"
        const val CHANNEL_DONE = "agent_done"
        /** Retired: finished-turn alerts no longer notify. Removed at startup so it doesn't linger in settings. */
        const val CHANNEL_AGENTS = "agents"
    }
}
