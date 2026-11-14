package dev.yurlungr.app.widgets

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.RemoteViews
import dev.yurlungr.app.MainActivity
import dev.yurlungr.app.R
import dev.yurlungr.app.projects.LastProject
import dev.yurlungr.app.web.WebRunSessions
import dev.yurlungr.app.web.WebRunState

/** Intents that open a place in the app (tiles, the widget, shortcuts). */
/** Set by the widget's "Ask an agent…" bar: the new-chat screen focuses its message box once, then clears it. */
object QuickAsk {
    private val state = androidx.compose.runtime.mutableStateOf(false)
    var pending: Boolean
        get() = state.value
        set(v) { state.value = v }
}

object OpenIn {
    const val SERVERS = "servers"
    const val TERMINAL = "terminal"
    const val NEW_CHAT = "new_chat"
    /** New chat with the keyboard already up: type and send. */
    const val QUICK_ASK = "quick_ask"
    const val PROJECT = "project:"

    fun intent(context: Context, place: String): Intent =
        Intent(context, MainActivity::class.java).setAction(Intent.ACTION_VIEW).putExtra(MainActivity.EXTRA_OPEN, place)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    fun pending(context: Context, place: String): PendingIntent =
        PendingIntent.getActivity(context, place.hashCode(), intent(context, place), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    /** Running web apps, for the tile and widget. */
    fun runningServers(): Int = WebRunSessions.all().count { it.state == WebRunState.Running }

    /** Long-press the launcher icon: New chat, the last project, Servers, Terminal. */
    fun updateShortcuts(context: Context) = runCatching {
        val icon = androidx.core.graphics.drawable.IconCompat.createWithResource(context, R.mipmap.ic_launcher)
        fun shortcut(id: String, label: String, place: String) =
            androidx.core.content.pm.ShortcutInfoCompat.Builder(context, id).setShortLabel(label).setIcon(icon).setIntent(intent(context, place)).build()
        val project = LastProject.get(context)
        androidx.core.content.pm.ShortcutManagerCompat.setDynamicShortcuts(context, listOfNotNull(
            shortcut("new_chat", "New chat", NEW_CHAT),
            shortcut("quick_ask", "Ask an agent", QUICK_ASK),
            project?.let { shortcut("last_project", it.name.take(24), PROJECT + it.path) },
            shortcut("servers", "Servers", SERVERS),
            shortcut("terminal", "Terminal", TERMINAL),
        ))
    }

    /** Refreshes the widget, the servers tile and the launcher shortcuts after something they show changed. */
    fun refresh(context: Context) {
        HomeWidget.update(context)
        updateShortcuts(context)
        runCatching { TileService.requestListeningState(context, ComponentName(context, ServersTileService::class.java)) }
    }
}

// Android 14 takes a PendingIntent; older versions only the Intent form.
@android.annotation.SuppressLint("StartActivityAndCollapseDeprecated")
private fun TileService.open(place: String) {
    val intent = OpenIn.intent(this, place)
    if (Build.VERSION.SDK_INT >= 34) startActivityAndCollapse(OpenIn.pending(this, place))
    else @Suppress("DEPRECATION") startActivityAndCollapse(intent)
}

/** Quick Settings: how many dev servers are running; tap for Servers. */
class ServersTileService : TileService() {
    override fun onStartListening() {
        val tile = qsTile ?: return
        val n = OpenIn.runningServers()
        tile.label = "Dev servers"
        tile.state = if (n > 0) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= 29) tile.subtitle = if (n == 0) "None running" else "$n running"
        tile.icon = Icon.createWithResource(this, R.drawable.ic_stat_terminal)
        tile.updateTile()
    }

    override fun onClick() = open(OpenIn.SERVERS)
}

/** Quick Settings: straight to the terminal. */
class TerminalTileService : TileService() {
    override fun onStartListening() {
        val tile = qsTile ?: return
        tile.label = "Terminal"
        tile.state = Tile.STATE_INACTIVE
        tile.icon = Icon.createWithResource(this, R.drawable.ic_stat_terminal)
        tile.updateTile()
    }

    override fun onClick() = open(OpenIn.TERMINAL)
}

/** Home screen: New chat, the last project, and Servers (with how many run). */
class HomeWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { manager.updateAppWidget(it, views(context)) }
    }

    companion object {
        fun views(context: Context): RemoteViews = RemoteViews(context.packageName, R.layout.widget_home).apply {
            val project = LastProject.get(context)
            setTextViewText(R.id.widget_project, project?.name ?: "Projects")
            setOnClickPendingIntent(R.id.widget_project, OpenIn.pending(context, project?.let { OpenIn.PROJECT + it.path } ?: OpenIn.PROJECT))
            setOnClickPendingIntent(R.id.widget_chat, OpenIn.pending(context, OpenIn.NEW_CHAT))
            setOnClickPendingIntent(R.id.widget_ask, OpenIn.pending(context, OpenIn.QUICK_ASK))
            val n = OpenIn.runningServers()
            setTextViewText(R.id.widget_servers, if (n == 0) "Servers" else "Servers · $n")
            setOnClickPendingIntent(R.id.widget_servers, OpenIn.pending(context, OpenIn.SERVERS))
        }

        fun update(context: Context) = runCatching {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, HomeWidget::class.java))
            if (ids.isNotEmpty()) manager.updateAppWidget(ids, views(context))
        }
    }
}
