package dev.yurlungr.app.web

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Switch
import androidx.compose.runtime.produceState
import dev.yurlungr.app.ui.Chip
import kotlinx.coroutines.delay
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.yurlungr.app.runtime.RuntimeBridge
import dev.yurlungr.app.runtime.setupScriptCommand
import dev.yurlungr.app.terminal.TerminalSessions
import dev.yurlungr.app.ui.AppColors
import dev.yurlungr.app.ui.AppSheet
import dev.yurlungr.app.ui.FlowBar
import dev.yurlungr.app.ui.Ph
import dev.yurlungr.app.ui.PhIcon

/**
 * Share a local port as a public https link (cloudflared Quick Tunnel: no account, no domain;
 * the address is random and changes every run). Asks first, since anyone with the link can reach the server.
 */
@Composable
fun TunnelSheet(port: Int, label: String, onDismiss: () -> Unit, onOpenTerminal: () -> Unit) {
    val context = LocalContext.current
    val tunnel = TunnelSessions.tunnels[port]
    val installed = remember(tunnel?.state) { TunnelSessions.isInstalled(context) }
    var confirmed by remember { mutableStateOf(false) }
    val url = tunnel?.url
    AppSheet(
        "Public link", onDismiss = onDismiss, icon = Ph.Globe, tile = AppColors.tiles.sky,
        subtitle = "$label · port $port",
    ) {
        when {
            !installed -> {
                Text("Sharing uses cloudflared, from the Servers & tunnels pack. It needs no account.", style = MaterialTheme.typography.bodyMedium)
                Button(
                    onClick = {
                        runCatching { TerminalSessions.create(context, setupScriptCommand("servers-setup.sh"), "Servers & tunnels") }
                            .onSuccess { onDismiss(); onOpenTerminal() }
                            .onFailure { Toast.makeText(context, it.message, Toast.LENGTH_LONG).show() }
                    },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) { Text("Install Servers & tunnels") }
            }
            tunnel == null || !tunnel.active && !confirmed && tunnel.state != TunnelState.Failed ->
                Confirm(port, null) { confirmed = true; TunnelSessions.start(context, port, label) }
            tunnel.state == TunnelState.Failed -> {
                Text(tunnel.message.ifBlank { "The tunnel stopped." }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                Button(onClick = { TunnelSessions.start(context, port, label) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Try again") }
            }
            tunnel.state == TunnelState.Stopped -> Confirm(port, tunnel.message.takeIf { it.isNotBlank() }) { TunnelSessions.start(context, port, label) }
            tunnel.state != TunnelState.Live || url == null -> {
                Text(if (url == null) "Asking Cloudflare for a link…" else "Got a link, waiting for it to go live…", style = MaterialTheme.typography.bodyMedium)
                FlowBar(Modifier.fillMaxWidth())
                if (url != null) Text(url, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick = { TunnelSessions.stop(context, tunnel) }, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
            }
            else -> {
                // The link as shared: with its access key, so whoever gets it is let in with one tap.
                val link = tunnel.shareUrl ?: url
                val now by produceState(System.currentTimeMillis(), tunnel.expiresAt) {
                    while (true) { value = System.currentTimeMillis(); delay(30_000) }
                }
                Text(link, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.titleSmall)
                // Scan it from another phone or tablet to try the app there.
                QrCode(link, Modifier.align(androidx.compose.ui.Alignment.CenterHorizontally))
                val ends = TunnelLinks.remaining(now, tunnel.expiresAt)?.let { "Stops by itself: ${it.replaceFirstChar(Char::lowercase)}." } ?: "Stays up until you stop it or the server stops."
                Text(
                    (if (tunnel.key != null) "Only people with this exact link (it carries an access key) can reach port $port. "
                        else "Anyone with this link can reach port $port. ") + "It changes next time. $ends",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = { copy(context, link) }, modifier = Modifier.weight(1f)) { Text("Copy") }
                    FilledTonalButton(onClick = {
                        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, link)
                        context.startActivity(Intent.createChooser(send, "Share link").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }, modifier = Modifier.weight(1f)) { Text("Share") }
                    FilledTonalButton(onClick = { RuntimeBridge.open(context, link) }, modifier = Modifier.weight(1f)) { Text("Open") }
                }
                OutlinedButton(onClick = { TunnelSessions.stop(context, tunnel) }, modifier = Modifier.fillMaxWidth()) { Text("Stop sharing") }
            }
        }
    }
}

/** Asks first, with how long the link lasts and whether it needs an access key ([note]: why the last one stopped). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Confirm(port: Int, note: String?, onStart: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant) }
    Text(
        "This makes the server on port $port reachable from the internet through a random https address. " +
            "Anyone who has the link can open it, including pages that show your data. The link changes each time; stop sharing when you're done.",
        style = MaterialTheme.typography.bodyMedium,
    )
    Text("Stop sharing after", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        for (option in TunnelLifetime.entries) Chip(option.label, onClick = { TunnelSessions.lastLifetime = option }, selected = TunnelSessions.lastLifetime == option)
    }
    // Only Yurlungr's own runs can check a key: other servers get a plain link.
    if (TunnelSessions.canProtect(port)) Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Require an access key", style = MaterialTheme.typography.bodyMedium)
            Text("The link you share includes it; a guessed address without it gets nothing.", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
        Switch(TunnelSessions.lastProtect, { TunnelSessions.lastProtect = it })
    } else Text(
        "This server isn't run by Yurlungr, so its link can't require an access key.",
        style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
    )
    Button(onClick = onStart, modifier = Modifier.fillMaxWidth().padding(top = 4.dp).heightIn(min = 48.dp)) {
        PhIcon(Ph.Globe, null, size = 18.dp)
        Text("  Share publicly")
    }
}

private fun copy(context: android.content.Context, text: String) {
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Public link", text))
    Toast.makeText(context, "Link copied", Toast.LENGTH_SHORT).show()
}
