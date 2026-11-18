package dev.yurlungr.app.web

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket

/** A port that's taken: by one of the app's web runs, or by [holder]; [next] is the next free one. */
private data class PortConflict(val port: Int, val run: WebRun?, val holder: PortHolder?, val next: Int?)

internal object PortCheck {
    fun isFree(port: Int): Boolean =
        runCatching { ServerSocket(port, 1, InetAddress.getByName("127.0.0.1")).close(); true }.getOrDefault(false)

    /** The next free port after [port], or null when the next 50 are all taken. */
    fun nextFree(port: Int): Int? = ((port + 1) until (port + 51).coerceAtMost(65536)).firstOrNull(::isFree)
}

/**
 * Under the port field of Run options: when the port is taken, says by what (a process of this
 * app's runtime, another web run, another app) and offers Stop and "Use <next> instead".
 */
@Composable
internal fun PortConflictNotice(port: Int, root: File, onUsePort: (Int) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var conflict by remember { mutableStateOf<PortConflict?>(null) }
    var recheck by remember { mutableIntStateOf(0) }
    var stopping by remember { mutableStateOf(false) }
    LaunchedEffect(port, recheck) {
        conflict = null
        if (port !in 1024..65535) return@LaunchedEffect
        delay(400) // typing settles first
        val run = WebRunSessions.all().firstOrNull { it.state.active && it.config.port == port }
        // This project's own run: the sheet already says it's running.
        if (run != null && run.root == root.canonicalFile) return@LaunchedEffect
        conflict = withContext(Dispatchers.IO) {
            if (PortCheck.isFree(port)) null
            else PortConflict(port, run, if (run == null) PortOwners.find(port) else null, PortCheck.nextFree(port))
        }
    }
    val found = conflict ?: return
    val colors = MaterialTheme.colorScheme
    val holder = found.holder
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(colors.errorContainer.copy(alpha = 0.35f)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            when {
                found.run != null -> "Port ${found.port} is used by ${found.run.config.project.framework} · ${found.run.config.project.directory.name}, another running web app."
                holder is PortHolder.Process -> "Port ${found.port} is used by:"
                holder == PortHolder.ThisApp -> "Port ${found.port} is used by Yurlungr itself."
                holder == PortHolder.OtherApp -> "Port ${found.port} is used by another app on this phone."
                else -> "Port ${found.port} is in use, but Yurlungr couldn't tell by what."
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        if (holder is PortHolder.Process) {
            Text("${holder.command} (pid ${holder.pid})", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, maxLines = 4)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (found.run != null || holder is PortHolder.Process) OutlinedButton(
                enabled = !stopping,
                onClick = {
                    found.run?.let { WebRunSessions.stop(context, it) }
                    stopping = true
                    scope.launch {
                        val ok = if (holder is PortHolder.Process) withContext(Dispatchers.IO) { PortOwners.stop(holder) } else true
                        if (!ok) Toast.makeText(context, "It already changed. Checking again.", Toast.LENGTH_SHORT).show()
                        delay(2_500) // SIGTERM first; the kill follows after a grace period
                        stopping = false
                        recheck++
                    }
                },
                modifier = Modifier.weight(1f),
            ) { Text(if (stopping) "Stopping…" else "Stop it") }
            found.next?.let { next ->
                FilledTonalButton(onClick = { onUsePort(next) }, modifier = Modifier.weight(1f)) { Text("Use $next instead") }
            }
        }
    }
}
