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
        TerminalSessions.init(this)
        dev.yurlungr.app.web.WebRunSessions.init(this)
        ChatSessions.init(this)
        dev.yurlungr.app.runtime.RuntimeStatus.init(this)
        RemoteHosts.init(this)
        RemoteServer.init(this)
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannels(
                listOf(
                    NotificationChannel(CHANNEL_RUNTIME, "Runtime", NotificationManager.IMPORTANCE_LOW).apply {
                        description = "Shown while chats or terminal sessions are running"
                    },
                    NotificationChannel(CHANNEL_AGENTS, "Agent updates", NotificationManager.IMPORTANCE_DEFAULT).apply {
                        description = "An agent finished or needs your approval"
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
        const val CHANNEL_AGENTS = "agents"
    }
}
