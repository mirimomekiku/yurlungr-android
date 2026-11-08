package dev.yurlungr.app.terminal

import dev.yurlungr.app.ui.PhIcon
import dev.yurlungr.app.ui.Motion
import dev.yurlungr.app.ui.AppColors
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextOverflow
import dev.yurlungr.app.ui.Ph
import dev.yurlungr.app.ui.LocalOpenDrawer
import dev.yurlungr.app.ui.LocalTopLevel
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.ListItem
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import android.content.Context
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.termux.terminal.KeyHandler
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView

private val Bg = Color(0xFF0B0D10)
private val Bar = Color(0xFF161B22)
private val Fg = Color(0xFFE6EDF3)
private val Muted = Color(0xFF8B949E)
private val Accent = Color(0xFF7EE787)
private val KeyTile = Color(0xFF1A1E25)

@Composable
fun TerminalScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    BackHandler(onBack = onBack)

    val session = TerminalSessions.current
    if (session == null) {
        LaunchedEffect(Unit) { TerminalSessions.create(context) }
        Box(Modifier.fillMaxSize().background(Bg))
        return
    }

    val density = LocalDensity.current
    val minFont = with(density) { 8.sp.roundToPx() }
    val maxFont = with(density) { 32.sp.roundToPx() }
    var fontPx by remember { mutableIntStateOf(with(density) { 12.sp.roundToPx() }) }
    val modifiers = remember { ModifierKeys() }
    var terminalView by remember { mutableStateOf<TerminalView?>(null) }

    var actionSession by remember { mutableStateOf<TerminalSession?>(null) }
    var requestedAction by remember { mutableStateOf<SessionAction?>(null) }
    fun requestAction(target: TerminalSession, action: SessionAction?) {
        actionSession = target
        requestedAction = action
    }

    var railOpen by remember { mutableStateOf(false) }
    val prefs = remember { context.getSharedPreferences("terminal", Context.MODE_PRIVATE) }
    var extraKeysVisible by rememberSaveable { mutableStateOf(prefs.getBoolean("extra_keys", true)) }

    Column(
        Modifier
            .fillMaxSize()
            .background(Bg)
            .systemBarsPadding()
            .imePadding(),
    ) {
        HeaderPills(
            onBack = onBack,
            railOpen = railOpen,
            onToggleRail = { railOpen = !railOpen },
            extraKeysVisible = extraKeysVisible,
            onToggleExtraKeys = {
                extraKeysVisible = !extraKeysVisible
                prefs.edit().putBoolean("extra_keys", extraKeysVisible).apply()
                modifiers.ctrl = false
                modifiers.alt = false
            },
        )

        Box(Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    TerminalView(ctx, null).also { view ->
                        val client = ViewClient(
                            modifiers = modifiers,
                            onFontScale = { bigger ->
                                fontPx = (fontPx + if (bigger) 2 else -2).coerceIn(minFont, maxFont)
                            },
                            onRestartFinished = { TerminalSessions.current?.let { requestAction(it, SessionAction.Restart) } },
                        )
                        client.view = view
                        view.setTerminalViewClient(client)
                        view.setTextSize(fontPx)
                        view.isFocusable = true
                        view.isFocusableInTouchMode = true
                        view.keepScreenOn = true
                        TerminalSessions.client.view = view
                        view.attachSession(session)
                        terminalView = view
                        // requestFocus() is a no-op until the view is attached to the window.
                        view.post { view.requestFocus() }
                    }
                },
                update = { view ->
                    if (view.currentSession !== session) {
                        view.attachSession(session)
                        view.onScreenUpdated()
                    }
                },
            )

            // Re-read on exit events so the overlay appears when the shell ends.
            @Suppress("UNUSED_EXPRESSION") TerminalSessions.changeTick
            if (!session.isRunning) {
                Column(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(16.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Bar)
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("Process exited with code ${session.exitStatus}", color = Fg)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { requestAction(session, SessionAction.Close) }) { Text("Close") }
                        Button(onClick = { requestAction(session, SessionAction.Restart) }) { Text("Restart") }
                    }
                }
            }

            // Floating, translucent session rail over the left edge; hides after picking.
            FloatingRail(railOpen, Modifier.align(Alignment.TopStart).padding(start = 10.dp, top = 6.dp),
                onActions = ::requestAction, onPicked = { railOpen = false })
        }

        AnimatedVisibility(visible = extraKeysVisible) { ExtraKeysGrid(modifiers = modifiers, view = terminalView) }
    }

    actionSession?.let { target ->
        SessionActionsSheet(target, requestedAction,
            onDismiss = { actionSession = null; requestedAction = null },
            onRestarted = { railOpen = false })
    }

    LaunchedEffect(fontPx) { terminalView?.setTextSize(fontPx) }
    DisposableEffect(Unit) {
        onDispose {
            if (TerminalSessions.client.view === terminalView) TerminalSessions.client.view = null
        }
    }
}

/** Session rail on the left; session arrows and a direct extra-keys toggle on the right. */
@Composable
private fun HeaderPills(
    onBack: () -> Unit,
    railOpen: Boolean,
    onToggleRail: () -> Unit,
    extraKeysVisible: Boolean,
    onToggleExtraKeys: () -> Unit,
) {
    @Suppress("UNUSED_EXPRESSION") TerminalSessions.changeTick // recompose on title changes
    val sessions = TerminalSessions.sessions
    val index = sessions.indexOf(TerminalSessions.current).coerceAtLeast(0)
    Row(
        Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.weight(1f, fill = false).height(48.dp).mist(24.dp), verticalAlignment = Alignment.CenterVertically) {
            val openDrawer = LocalOpenDrawer.current
            if (LocalTopLevel.current && openDrawer != null) {
                BarIcon(Ph.List, "Open menu", onClick = openDrawer)
            } else {
                BarIcon(Ph.CaretLeft, "Back", onClick = onBack)
            }
            Row(
                Modifier
                    .weight(1f, fill = false)
                    .clip(RoundedCornerShape(18.dp))
                    .touchOnlyClickable(onToggleRail)
                    .padding(start = 2.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    TerminalSessions.current?.let { TerminalSessions.title(it, index) } ?: "Terminal",
                    color = Fg,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false).widthIn(max = 170.dp),
                )
                Spacer(Modifier.width(6.dp))
                PhIcon(if (railOpen) Ph.CaretLeft else Ph.CaretRight, if (railOpen) "Hide sessions" else "Show sessions", size = 12.dp, tint = Muted)
            }
        }
        Spacer(Modifier.width(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.height(48.dp).mist(24.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                BarIcon(Ph.CaretLeft, "Previous session", size = 16.dp, enabled = sessions.size > 1) {
                    sessions.getOrNull((index - 1 + sessions.size) % sessions.size)?.let(TerminalSessions::select)
                }
                Text("${index + 1}/${sessions.size}", color = Fg.copy(alpha = 0.85f), fontFamily = FontFamily.Monospace, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                BarIcon(Ph.CaretRight, "Next session", size = 16.dp, enabled = sessions.size > 1) {
                    sessions.getOrNull((index + 1) % sessions.size)?.let(TerminalSessions::select)
                }
            }
            Row(
                Modifier.height(48.dp).mist(24.dp)
                    .semantics {
                        contentDescription = if (extraKeysVisible) "Hide extra keys" else "Show extra keys"
                        stateDescription = if (extraKeysVisible) "Shown" else "Hidden"
                    }
                    .touchOnlyClickable(onToggleExtraKeys)
                    .padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PhIcon(Ph.Keyboard, null, size = 20.dp, tint = if (extraKeysVisible) Accent else Fg)
                PhIcon(if (extraKeysVisible) Ph.CaretDown else Ph.CaretUp, null, size = 12.dp, tint = Muted)
            }
        }
    }
}

@Composable
private fun FloatingRail(visible: Boolean, modifier: Modifier,
    onActions: (TerminalSession, SessionAction?) -> Unit, onPicked: () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(Motion.SHORT)) + slideInHorizontally(tween(Motion.MEDIUM, easing = Motion.Emphasized)) { -it / 2 },
        exit = fadeOut(tween(Motion.SHORT)) + slideOutHorizontally(tween(Motion.SHORT)) { -it / 2 },
        modifier = modifier,
    ) { SessionRail(onPicked, onActions) }
}

/** Numbered session tiles (dot = still running), + for a new one, × closes the current. */
private enum class SessionAction(val label: String, val explanation: String) {
    Rename("Rename", ""),
    Restart("Restart process", "Stop the current process and rerun this session's original command."),
    End("End process", "Stop the running process. Its output will stay visible in this session."),
    Close("Close session", "Stop its process if running and remove this terminal tab."),
}

@Composable
private fun SessionRail(onPicked: () -> Unit, onActions: (TerminalSession, SessionAction?) -> Unit) {
    val context = LocalContext.current
    @Suppress("UNUSED_EXPRESSION") TerminalSessions.changeTick
    val accent = AppColors.tiles.lime.fill
    Column(
        Modifier
            .width(64.dp)
            .mist(22.dp)
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        TerminalSessions.sessions.forEachIndexed { i, s ->
            val selected = s === TerminalSessions.current
            Box(
                Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(15.dp))
                    .background(if (selected) accent else Color.White.copy(alpha = 0.07f))
                    .focusProperties { canFocus = false }
                    .combinedClickable(
                        onClick = { TerminalSessions.select(s); onPicked() },
                        onLongClickLabel = "Session actions",
                        onLongClick = { onActions(s, null) },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "${i + 1}",
                    color = if (selected) AppColors.tiles.lime.on else Fg,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                )
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(if (s.isRunning) Accent else Muted),
                )
            }
        }
        Box(
            Modifier
                .size(46.dp)
                .clip(RoundedCornerShape(15.dp))
                .border(1.5.dp, Muted.copy(alpha = 0.5f), RoundedCornerShape(15.dp))
                .touchOnlyClickable {
                    TerminalSessions.create(context)
                    onPicked()
                },
            contentAlignment = Alignment.Center,
        ) { PhIcon(Ph.Plus, "New terminal", size = 18.dp, tint = Muted) }
        Box(Modifier.width(28.dp).height(1.dp).background(Color.White.copy(alpha = 0.08f)))
        BarIcon(Ph.X, "Close terminal", tint = Muted) {
            TerminalSessions.current?.let { onActions(it, SessionAction.Close) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionActionsSheet(
    target: TerminalSession,
    initialAction: SessionAction?,
    onDismiss: () -> Unit,
    onRestarted: () -> Unit,
) {
    val context = LocalContext.current
    @Suppress("UNUSED_EXPRESSION") TerminalSessions.changeTick
    var action by remember(target, initialAction) { mutableStateOf(initialAction) }
    var renameText by remember(target) { mutableStateOf("") }
    // The target may exit or be removed while the sheet is open.
    val exists = target in TerminalSessions.sessions
    val title = TerminalSessions.title(target, TerminalSessions.sessions.indexOf(target).coerceAtLeast(0))
    LaunchedEffect(exists) { if (!exists) { onDismiss() } }
    ModalBottomSheet(
        onDismissRequest = { onDismiss() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(Modifier.fillMaxWidth().imePadding().padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(when (val selectedAction = action) {
                null -> "Session actions"
                SessionAction.Rename -> "Rename terminal"
                else -> "${selectedAction.label}?"
            }, style = MaterialTheme.typography.titleLarge)
            Text(title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            when (val selectedAction = action) {
                null -> SessionAction.entries.forEach { option ->
                    val enabled = exists && (option != SessionAction.End || target.isRunning)
                    ListItem(
                        headlineContent = { Text(option.label, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant) },
                        leadingContent = { PhIcon(when (option) {
                            SessionAction.Rename -> Ph.PencilSimple
                            SessionAction.Restart -> Ph.ArrowClockwise
                            SessionAction.End -> Ph.Stop
                            SessionAction.Close -> Ph.X
                        }, null) },
                        modifier = Modifier.clip(RoundedCornerShape(16.dp)).clickable(enabled = enabled) {
                            if (option == SessionAction.Rename) renameText = title
                            action = option
                        },
                    )
                }
                SessionAction.Rename -> {
                    OutlinedTextField(renameText, { renameText = it }, singleLine = true,
                        label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { action = null }) { Text("Cancel") }
                        Button(enabled = exists, onClick = {
                            TerminalSessions.rename(target, renameText)
                            onDismiss()
                        }) { Text("Save") }
                    }
                }
                else -> {
                    Text(selectedAction.explanation, style = MaterialTheme.typography.bodyLarge)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { onDismiss() }) { Text("Cancel") }
                        Button(
                            enabled = exists && (selectedAction != SessionAction.End || target.isRunning),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error,
                                contentColor = MaterialTheme.colorScheme.onError),
                            onClick = {
                                // Clear the pending action first so it cannot be confirmed twice.
                                onDismiss()
                                if (target in TerminalSessions.sessions) when (selectedAction) {
                                    SessionAction.Restart -> { TerminalSessions.restart(context, target); onRestarted() }
                                    SessionAction.End -> TerminalSessions.endProcess(target)
                                    SessionAction.Close -> TerminalSessions.close(context, target)
                                    else -> Unit
                                }
                            },
                        ) { Text(selectedAction.label) }
                    }
                }
            }
        }
    }
}

/** Translucent "mist" surface for controls floating over the terminal. */
private fun Modifier.mist(radius: androidx.compose.ui.unit.Dp): Modifier =
    clip(RoundedCornerShape(radius))
        .background(Brush.verticalGradient(listOf(Color(0xE0262B33), Color(0xD01C2027))))
        .border(1.dp, Color.White.copy(alpha = 0.07f), RoundedCornerShape(radius))

@Composable
private fun BarIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    size: androidx.compose.ui.unit.Dp = 20.dp,
    tint: Color = Fg,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .then(if (enabled) Modifier.touchOnlyClickable(onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) { PhIcon(icon, description, size = size, tint = if (enabled) tint else tint.copy(alpha = 0.35f)) }
}

/**
 * Clickable that never takes keyboard focus. Otherwise Compose moves focus off the terminal
 * onto these buttons, and typed Space/Enter from a hardware (or adb) keyboard "clicks" them.
 */
private fun Modifier.touchOnlyClickable(onClick: () -> Unit): Modifier =
    focusProperties { canFocus = false }.clickable(onClick = onClick)

private sealed interface ExtraKey {
    val label: String
    data class Code(override val label: String, val keyCode: Int) : ExtraKey
    data class Text(override val label: String, val text: String) : ExtraKey
    data object Ctrl : ExtraKey { override val label = "ctrl" }
    data object Alt : ExtraKey { override val label = "alt" }
}

/** Two rows of eight; the arrows form a cluster (↑ above ← ↓ →). */
private val EXTRA_KEYS = listOf(
    listOf(
        ExtraKey.Code("esc", KeyEvent.KEYCODE_ESCAPE),
        ExtraKey.Ctrl,
        ExtraKey.Alt,
        ExtraKey.Code("tab", KeyEvent.KEYCODE_TAB),
        ExtraKey.Text("-", "-"),
        ExtraKey.Code("↑", KeyEvent.KEYCODE_DPAD_UP),
        ExtraKey.Text("/", "/"),
        ExtraKey.Text("|", "|"),
    ),
    listOf(
        ExtraKey.Code("home", KeyEvent.KEYCODE_MOVE_HOME),
        ExtraKey.Code("end", KeyEvent.KEYCODE_MOVE_END),
        ExtraKey.Code("pgup", KeyEvent.KEYCODE_PAGE_UP),
        ExtraKey.Code("pgdn", KeyEvent.KEYCODE_PAGE_DOWN),
        ExtraKey.Code("←", KeyEvent.KEYCODE_DPAD_LEFT),
        ExtraKey.Code("↓", KeyEvent.KEYCODE_DPAD_DOWN),
        ExtraKey.Code("→", KeyEvent.KEYCODE_DPAD_RIGHT),
        ExtraKey.Text("~", "~"),
    ),
)

@Composable
private fun ExtraKeysGrid(modifiers: ModifierKeys, view: TerminalView?) {
    val accent = AppColors.tiles.lime.fill
    Column(
        Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 6.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (row in EXTRA_KEYS) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (key in row) {
                    val active = (key == ExtraKey.Ctrl && modifiers.ctrl) || (key == ExtraKey.Alt && modifiers.alt)
                    val bg by animateColorAsState(if (active) accent else KeyTile, tween(Motion.SHORT), label = "key")
                    Box(
                        Modifier
                            .weight(1f)
                            .height(38.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(bg)
                            .touchOnlyClickable { view?.let { onExtraKey(key, modifiers, it) } },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            key.label,
                            color = if (active) AppColors.tiles.lime.on else Fg,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = if (key.label.length == 1) 14.sp else 12.sp,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

private fun onExtraKey(key: ExtraKey, modifiers: ModifierKeys, view: TerminalView) {
    val session = view.currentSession ?: return
    when (key) {
        ExtraKey.Ctrl -> modifiers.ctrl = !modifiers.ctrl
        ExtraKey.Alt -> modifiers.alt = !modifiers.alt
        is ExtraKey.Code -> {
            var mod = 0
            if (modifiers.consumeCtrl()) mod = mod or KeyHandler.KEYMOD_CTRL
            if (modifiers.consumeAlt()) mod = mod or KeyHandler.KEYMOD_ALT
            view.handleKeyCode(key.keyCode, mod)
        }
        is ExtraKey.Text -> {
            // Route through inputCodePoint so a pending CTRL/ALT applies (e.g. CTRL then "-").
            key.text.codePoints().forEach { cp ->
                view.inputCodePoint(cp, modifiers.consumeCtrl(), modifiers.consumeAlt())
            }
        }
    }
    if (session.isRunning) view.requestFocus()
}
