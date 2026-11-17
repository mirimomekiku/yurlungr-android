package dev.yurlungr.app.remote

import dev.yurlungr.app.ui.SkeletonLine
import dev.yurlungr.app.ui.SkeletonCard
import dev.yurlungr.app.ui.NavButton
import dev.yurlungr.app.ui.Keyboards
import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.yurlungr.app.agents.Agent
import dev.yurlungr.app.chat.ChatState

/** Another phone's chats and agents, used over the network. */
@Composable
fun RemoteHomeScreen(host: RemoteHost, onBack: () -> Unit, onOpenChat: (String) -> Unit) {
    BackHandler(onBack = onBack)
    val conn = remember(host.id) { RemoteConnections.get(host) }
    var newChatFor by remember { mutableStateOf<Agent?>(null) }
    var pairingAgain by remember { mutableStateOf(false) }

    LazyColumn(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                NavButton(onBack)
                Column {
                    Text(conn.deviceName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text(
                        when (val s = conn.status) {
                            RemoteConnection.Status.Connecting -> "Connecting to ${host.address}…"
                            RemoteConnection.Status.Connected -> "Connected · ${host.address}"
                            is RemoteConnection.Status.Failed -> if (s.retrying) "Reconnecting…" else "Not connected"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        when (val s = conn.status) {
            RemoteConnection.Status.Connecting -> {
                item { SkeletonLine(Modifier.padding(top = 12.dp), width = 0.2f, height = 12.dp) }
                items(3) { SkeletonCard(lines = 2) }
            }
            is RemoteConnection.Status.Failed -> item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(s.message, style = MaterialTheme.typography.bodyMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { conn.connect() }) { Text("Try again") }
                            if (s.refused) TextButton(onClick = { pairingAgain = true }) { Text("Pair again") }
                        }
                    }
                }
            }
            RemoteConnection.Status.Connected -> {
                if (conn.chats.isNotEmpty()) {
                    item { Label("Chats") }
                    items(conn.chats, key = { it.id }) { chat ->
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                            modifier = Modifier.fillMaxWidth().clickable { onOpenChat(chat.id) },
                        ) {
                            Column(Modifier.padding(14.dp)) {
                                Text(chat.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    "${stateText(chat.state)} · " +
                                        DateUtils.getRelativeTimeSpanString(chat.updatedAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                item { Label("Agents on ${conn.deviceName}") }
                items(conn.agents, key = { it.agent.name }) { status ->
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer), modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(status.agent.displayName, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    when {
                                        !status.installed -> "Not installed on the host"
                                        status.signedIn == false -> "Not signed in on the host"
                                        else -> "Ready"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            Button(onClick = { newChatFor = status.agent }, enabled = status.installed) { Text("New chat") }
                        }
                    }
                }
            }
        }
    }

    newChatFor?.let { agent ->
        var folder by remember { mutableStateOf("scratch") }
        var skipPermissions by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { newChatFor = null },
            title = { Text("New ${agent.displayName} chat on ${conn.deviceName}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    dev.yurlungr.app.chat.PermissionPicker(skipPermissions) { skipPermissions = it }
                    OutlinedTextField(
                        folder,
                        { folder = it },
                        label = { Text("Project folder") },
                        supportingText = { Text("~/projects/${folder.trim()} on the host") },
                        singleLine = true,
                        keyboardOptions = Keyboards.Identifier,
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    newChatFor = null
                    conn.createChat(agent, folder.trim().ifEmpty { "scratch" }, skipPermissions) { onOpenChat(it.id) }
                }) { Text("Start") }
            },
            dismissButton = { TextButton(onClick = { newChatFor = null }) { Text("Cancel") } },
        )
    }

    if (pairingAgain) {
        var code by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { pairingAgain = false },
            title = { Text("Pair again with ${conn.deviceName}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "This phone forgets its key for this host and pairs with the code. On the host: Devices → Pair a device.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedTextField(code, { code = it }, label = { Text("Pairing code") }, singleLine = true, keyboardOptions = Keyboards.Code)
                }
            },
            confirmButton = {
                Button(onClick = {
                    pairingAgain = false
                    conn.pairAgain(code)
                }, enabled = code.isNotBlank()) { Text("Pair") }
            },
            dismissButton = { TextButton(onClick = { pairingAgain = false }) { Text("Cancel") } },
        )
    }
}

private fun stateText(state: ChatState) = when (state) {
    ChatState.Running -> "Working…"
    ChatState.Ready -> "Active"
    ChatState.Starting -> "Starting…"
    ChatState.AuthRequired -> "Needs sign-in"
    is ChatState.Failed -> "Stopped"
    ChatState.Saved -> "Saved"
}

@Composable
private fun Label(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp),
    )
}
