package dev.yurlungr.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.yurlungr.app.Route
import dev.yurlungr.app.chat.ChatSession
import dev.yurlungr.app.chat.ChatSessions
import dev.yurlungr.app.chat.ChatState
import dev.yurlungr.app.remote.RemoteHosts
import java.util.Calendar

/** Sidebar like Gemini/ChatGPT: new chat, history grouped by day, then the app's other places. */
@Composable
fun AppDrawer(
    current: Route,
    onNewChat: () -> Unit,
    onOpenChat: (ChatSession) -> Unit,
    onDeleteChat: (ChatSession) -> Unit,
    onNavigate: (Route) -> Unit,
) {
    ModalDrawerSheet(Modifier.width(310.dp)) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 12.dp)) {
            Text(
                "Yurlungr",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 12.dp, bottom = 12.dp),
            )
            FilledTonalButton(onClick = onNewChat, modifier = Modifier.fillMaxWidth()) {
                PhIcon(Ph.NotePencil, null, size = 18.dp)
                Spacer(Modifier.width(10.dp))
                Text("New chat")
            }
        }

        val groups = remember(ChatSessions.chats.toList(), ChatSessions.chats.map { it.updatedAt }) { groupByDay(ChatSessions.chats) }
        LazyColumn(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            if (groups.isEmpty()) {
                item {
                    Text(
                        "Your chats will show up here.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }
            for ((label, chats) in groups) {
                item(key = "label-$label") { GroupLabel(label) }
                items(chats, key = { it.id }) { chat ->
                    ChatEntry(
                        chat,
                        modifier = Modifier.animateItem(),
                        selected = current == Route.Chat(chat.id),
                        onOpen = { onOpenChat(chat) },
                        onDelete = { onDeleteChat(chat) },
                    )
                }
            }
            if (RemoteHosts.hosts.isNotEmpty()) {
                item(key = "label-devices") { GroupLabel("Other devices") }
                items(RemoteHosts.hosts, key = { "host-" + it.id }) { host ->
                    NavigationDrawerItem(
                        label = { Text(host.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        icon = { PhIcon(DeviceIcons.Phone, null) },
                        selected = current == Route.RemoteHome(host.id),
                        onClick = { onNavigate(Route.RemoteHome(host.id)) },
                    )
                }
            }
        }

        HorizontalDivider(Modifier.padding(horizontal = 16.dp))
        Column(Modifier.padding(12.dp)) {
            for ((label, icon, route) in listOf(
                Triple("Projects", Ph.FolderSimple, Route.Projects),
                Triple("Terminal", Ph.TerminalWindow, Route.Terminal),
                Triple("Agents", Ph.Sparkle, Route.Agents),
                Triple("Devices", Ph.Devices, Route.Devices),
                Triple("Settings", Ph.GearSix, Route.Settings),
            )) {
                NavigationDrawerItem(
                    label = { Text(label) },
                    icon = { PhIcon(icon, null) },
                    selected = current == route || (route == Route.Projects && current is Route.Project),
                    onClick = { onNavigate(route) },
                    modifier = Modifier.padding(vertical = 1.dp),
                )
            }
        }
    }
}

@Composable
private fun GroupLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 12.dp, top = 14.dp, bottom = 4.dp),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatEntry(chat: ChatSession, selected: Boolean, onOpen: () -> Unit, onDelete: () -> Unit, modifier: Modifier = Modifier) {
    var menu by remember { mutableStateOf(false) }
    Box(modifier) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(50))
                .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                .combinedClickable(onClick = onOpen, onLongClick = { menu = true })
                .padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(chat.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    chat.agent.displayName + " · " + chat.folderName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            when (chat.state) {
                ChatState.Running -> Activity(MaterialTheme.colorScheme.primary)
                ChatState.AuthRequired -> Activity(Color(0xFFD29922))
                else -> Unit
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("Delete chat", color = MaterialTheme.colorScheme.error) }, onClick = {
                menu = false
                onDelete()
            })
        }
    }
}

@Composable
private fun Activity(color: Color) {
    Spacer(Modifier.width(8.dp))
    Box(Modifier.size(8.dp).clip(CircleShape).background(color))
}

/** Today / Yesterday / Previous 7 days / Older, newest first. */
private fun groupByDay(chats: List<ChatSession>): List<Pair<String, List<ChatSession>>> {
    val today = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    val day = 24 * 60 * 60 * 1000L
    return chats.sortedByDescending { it.updatedAt }
        .groupBy { c ->
            when {
                c.updatedAt >= today -> "Today"
                c.updatedAt >= today - day -> "Yesterday"
                c.updatedAt >= today - 7 * day -> "Previous 7 days"
                else -> "Older"
            }
        }
        .toList()
}
