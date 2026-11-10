package dev.yurlungr.app.projects

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import dev.yurlungr.app.projects.Git.Companion.shellQuote
import dev.yurlungr.app.terminal.TerminalSessions
import dev.yurlungr.app.terminal.ViewClient
import dev.yurlungr.app.ui.AppColors
import dev.yurlungr.app.ui.AppSheet
import dev.yurlungr.app.ui.FlowBar
import dev.yurlungr.app.ui.Ph
import dev.yurlungr.app.ui.PhIcon
import java.io.File

/**
 * Runs one git command (pull, push…) in a real terminal inside a bottom sheet, so its progress and
 * prompts show as git prints them. The terminal session ends by itself when git exits; the sheet
 * stays with the output and the result until it's closed, which removes the finished session.
 */
@Composable
fun GitTerminalSheet(
    dir: File,
    /** "Pull", "Push"… */
    title: String,
    /** The git arguments, e.g. listOf("pull", "--ff-only"). */
    args: List<String>,
    onDismiss: () -> Unit,
    /** Git finished (either way): refresh the project's status. */
    onFinished: (ok: Boolean) -> Unit,
) {
    val context = LocalContext.current
    var startError by remember { mutableStateOf<String?>(null) }
    val session: TerminalSession? = remember {
        runCatching {
            TerminalSessions.create(
                context,
                // exec: git is the session's process, so its exit code is the session's.
                command = "exec git " + args.joinToString(" ") { shellQuote(it) },
                name = "Git · ${title.lowercase()} · ${dir.name}",
                keepShell = false,
                workingDirectory = dir,
            )
        }.onFailure { startError = it.message ?: it.toString() }.getOrNull()
    }
    // Re-read when sessions change (the exit callback bumps this).
    @Suppress("UNUSED_VARIABLE") val tick = TerminalSessions.changeTick
    val running = session?.isRunning == true
    val exit = if (session != null && !running) session.exitStatus else null

    LaunchedEffect(running) {
        if (session != null && !running) onFinished(session.exitStatus == 0)
    }
    // Closing the sheet removes the session (stopping git first if it's still going).
    DisposableEffect(session) {
        onDispose { session?.let { TerminalSessions.closeDirect(context, it) } }
    }

    AppSheet(
        "Git $title".trim(),
        onDismiss = onDismiss,
        subtitle = "git ${args.joinToString(" ")} · ${dir.name}",
        icon = if (title.equals("push", ignoreCase = true)) Ph.CloudArrowUp else Ph.CloudArrowDown,
        tile = AppColors.tiles.sky,
        actions = {
            Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                Text(if (running) "Stop and close" else "Close")
            }
        },
    ) {
        // Status line: running, or how it ended.
        val tones = AppColors.status
        val (bg, fg, text) = when {
            startError != null -> Triple(MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer, "Couldn't start git: $startError")
            running -> Triple(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.colorScheme.onSurface, "Running…")
            exit == 0 -> Triple(tones.successContainer, tones.onSuccessContainer, "$title finished")
            exit != null && exit < 0 -> Triple(MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer, "$title stopped (signal ${-exit})")
            else -> Triple(MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer, "$title failed (exit $exit). See git's output above it.")
        }
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(bg).padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!running && startError == null) {
                PhIcon(if (exit == 0) Ph.CheckCircle else Ph.XCircle, null, size = 18.dp, tint = fg)
                Spacer(Modifier.width(8.dp))
            }
            Text(text, color = fg, style = MaterialTheme.typography.bodyMedium)
        }
        if (running) FlowBar(Modifier.fillMaxWidth())

        if (session != null) {
            TerminalBox(session)
        }
    }
}

/** A read-mostly terminal for one session (tap to type, e.g. to answer a credential prompt). */
@Composable
private fun TerminalBox(session: TerminalSession) {
    val density = LocalDensity.current
    val fontPx = with(density) { 11.sp.roundToPx() }
    var view by remember { mutableStateOf<TerminalView?>(null) }
    // The shared session client draws into one view; take it while this sheet shows, then give it back.
    DisposableEffect(view) {
        val previous = TerminalSessions.client.view
        view?.let { TerminalSessions.client.view = it }
        onDispose { if (TerminalSessions.client.view === view) TerminalSessions.client.view = previous }
    }
    Box(
        Modifier
            .fillMaxWidth()
            .height(320.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF0B0D10))
            .padding(6.dp),
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                TerminalView(ctx, null).also { v ->
                    val client = ViewClient(
                        modifiers = dev.yurlungr.app.terminal.ModifierKeys(),
                        onFontScale = {},
                        onRestartFinished = {},
                    )
                    client.view = v
                    v.setTerminalViewClient(client)
                    v.setTextSize(fontPx)
                    v.isFocusable = true
                    v.isFocusableInTouchMode = true
                    TerminalSessions.client.view = v
                    v.attachSession(session)
                    view = v
                }
            },
            update = { v ->
                if (v.currentSession !== session) {
                    v.attachSession(session)
                    v.onScreenUpdated()
                }
            },
        )
    }
}
