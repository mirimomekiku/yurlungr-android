package dev.yurlungr.app.ui

import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.unit.dp

/** Opens the app sidebar; null outside the app shell. */
val LocalOpenDrawer = compositionLocalOf<(() -> Unit)?> { null }

/** Opens a local file from an agent reply. */
val LocalOpenFile = compositionLocalOf<((java.io.File) -> Unit)?> { null }

/** True on top-level screens, which show ☰ (sidebar) instead of ‹ (back). */
val LocalTopLevel = compositionLocalOf { false }

/** ☰ on top-level screens, ‹ on screens deeper in the stack. */
@Composable
fun NavButton(onBack: () -> Unit) {
    val openDrawer = LocalOpenDrawer.current
    if (LocalTopLevel.current && openDrawer != null) {
        IconButton(onClick = openDrawer) { PhIcon(Ph.List, "Open menu", size = 24.dp) }
    } else {
        IconButton(onClick = onBack) { PhIcon(Ph.CaretLeft, "Back", size = 24.dp) }
    }
}

/** Opens Skills & commands for an agent (by name) and a project folder; both null for the general view. */
val LocalOpenExtensions = compositionLocalOf<((String?, String?) -> Unit)?> { null }

/**
 * Starts a new chat in a folder with a first message (e.g. "fix this failure"), with the user's
 * preferred agent, and shows it. Null outside the app shell.
 */
val LocalOpenChat = compositionLocalOf<((cwd: java.io.File, prompt: String) -> Unit)?> { null }

/** Runs a command in a new terminal tab and shows it, like the project's own commands. */
val LocalRunInTerminal = compositionLocalOf<((command: String, name: String) -> Unit)?> { null }
