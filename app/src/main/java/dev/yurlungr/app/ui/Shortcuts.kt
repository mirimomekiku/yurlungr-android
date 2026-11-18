package dev.yurlungr.app.ui

import android.view.KeyEvent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** App-wide keyboard shortcuts (hardware keyboards, Chromebooks). */
enum class Shortcut(val keys: String, val label: String) {
    NewChat("Ctrl+N", "New chat"),
    Search("Ctrl+K", "Search everything"),
    GoToFile("Ctrl+P", "Go to a file (Search everything)"),
    FindCode("Ctrl+Shift+F", "Find code in projects"),
    Settings("Ctrl+,", "Settings"),
    NextChat("Ctrl+Tab", "Next recent chat"),
    PreviousChat("Ctrl+Shift+Tab", "Previous recent chat"),
    Help("Ctrl+/", "Keyboard shortcuts"),
    Back("Esc", "Back, or close the sidebar"),
}

/** A key press with its modifiers; [alt] also covers Meta (the Search/Launcher key). */
data class KeyChord(val keyCode: Int, val ctrl: Boolean = false, val shift: Boolean = false, val alt: Boolean = false)

object Shortcuts {
    /** Handled where the text is (the message box), listed in the help sheet only. */
    const val SEND_KEYS = "Ctrl+Enter"

    fun match(c: KeyChord): Shortcut? {
        if (c.alt) return null
        if (!c.ctrl) return if (!c.shift && c.keyCode == KeyEvent.KEYCODE_ESCAPE) Shortcut.Back else null
        return when (c.keyCode) {
            KeyEvent.KEYCODE_N -> Shortcut.NewChat.takeIf { !c.shift }
            KeyEvent.KEYCODE_K -> Shortcut.Search.takeIf { !c.shift }
            KeyEvent.KEYCODE_P -> Shortcut.GoToFile.takeIf { !c.shift }
            KeyEvent.KEYCODE_F -> Shortcut.FindCode.takeIf { c.shift }
            KeyEvent.KEYCODE_COMMA -> Shortcut.Settings.takeIf { !c.shift }
            KeyEvent.KEYCODE_TAB -> if (c.shift) Shortcut.PreviousChat else Shortcut.NextChat
            KeyEvent.KEYCODE_SLASH -> Shortcut.Help
            else -> null
        }
    }

    /**
     * The chat after (or before) [current] in [recent] (newest first), wrapping around; the newest
     * when no chat is showing. Null when there's nowhere else to go.
     */
    fun cycle(recent: List<String>, current: String?, forward: Boolean): String? {
        if (recent.isEmpty()) return null
        val i = recent.indexOf(current)
        if (i < 0) return recent.first()
        if (recent.size == 1) return null
        val next = if (forward) (i + 1) % recent.size else (i - 1 + recent.size) % recent.size
        return recent[next]
    }
}

/**
 * Feeds the activity's key events to the shortcut handler (see MainActivity.dispatchKeyEvent).
 * Dialogs and sheets have their own windows, so their keys never come through here.
 */
class ShortcutKeys {
    internal var handler: ((Shortcut) -> Boolean)? = null
    /** A handled key's release is swallowed too, so nothing sees half a press. */
    private var swallowUp = -1

    /**
     * Sees the window's key events before any view or Compose node does, even when nothing has
     * focus (overriding the activity's dispatchKeyEvent is restricted API in androidx).
     */
    fun attach(window: android.view.Window) {
        val original = window.callback ?: return
        window.callback = object : android.view.Window.Callback by original {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean = dispatch(event) || original.dispatchKeyEvent(event)
        }
    }

    fun dispatch(event: KeyEvent): Boolean {
        val h = handler ?: return false
        if (event.action == KeyEvent.ACTION_UP && event.keyCode == swallowUp) {
            swallowUp = -1
            return true
        }
        if (event.action != KeyEvent.ACTION_DOWN) return false
        val s = Shortcuts.match(KeyChord(event.keyCode, event.isCtrlPressed, event.isShiftPressed, event.isAltPressed || event.isMetaPressed)) ?: return false
        // Held down: only the first press acts.
        if (event.repeatCount > 0) return swallowUp == event.keyCode
        if (!h(s)) return false
        swallowUp = event.keyCode
        return true
    }
}

/**
 * Connects [keys] to [onShortcut] while this is composed; returns false from [onShortcut] to let a
 * key through. Ctrl+/ (the help sheet) is handled here.
 */
@Composable
fun ShortcutHost(keys: ShortcutKeys, onShortcut: (Shortcut) -> Boolean) {
    var help by remember { mutableStateOf(false) }
    val current by rememberUpdatedState(onShortcut)
    DisposableEffect(keys) {
        keys.handler = { s ->
            // Asked first, so a screen that wants every key (the terminal) can say no to all of them.
            val handled = current(s)
            if (handled && s == Shortcut.Help) help = true
            handled
        }
        onDispose { keys.handler = null }
    }
    if (help) KeyboardShortcutsSheet(onDismiss = { help = false })
}

@Composable
fun KeyboardShortcutsSheet(onDismiss: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    AppSheet("Keyboard shortcuts", onDismiss = onDismiss, icon = Ph.Keyboard, subtitle = "With a keyboard attached. The terminal and web previews keep every key for themselves.") {
        val rows = Shortcut.entries.map { it.keys to it.label }.toMutableList()
        rows.add(rows.indexOfFirst { it.first == Shortcut.Help.keys }, Shortcuts.SEND_KEYS to "Send the message")
        for ((keys, label) in rows) {
            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Text(
                    keys, fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(colors.surfaceContainerHigh).padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
        }
    }
}
