package dev.yurlungr.app.ui

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShortcutsTest {
    private fun ctrl(code: Int, shift: Boolean = false) = Shortcuts.match(KeyChord(code, ctrl = true, shift = shift))

    @Test fun `ctrl chords map to shortcuts`() {
        assertEquals(Shortcut.NewChat, ctrl(KeyEvent.KEYCODE_N))
        assertEquals(Shortcut.Search, ctrl(KeyEvent.KEYCODE_K))
        assertEquals(Shortcut.GoToFile, ctrl(KeyEvent.KEYCODE_P))
        assertEquals(Shortcut.FindCode, ctrl(KeyEvent.KEYCODE_F, shift = true))
        assertEquals(Shortcut.Settings, ctrl(KeyEvent.KEYCODE_COMMA))
        assertEquals(Shortcut.NextChat, ctrl(KeyEvent.KEYCODE_TAB))
        assertEquals(Shortcut.PreviousChat, ctrl(KeyEvent.KEYCODE_TAB, shift = true))
        assertEquals(Shortcut.Help, ctrl(KeyEvent.KEYCODE_SLASH))
        assertEquals(Shortcut.Back, Shortcuts.match(KeyChord(KeyEvent.KEYCODE_ESCAPE)))
    }

    @Test fun `everything else is left alone`() {
        // Ctrl+F alone (find in a page or editor) and text-editing chords aren't taken.
        assertNull(ctrl(KeyEvent.KEYCODE_F))
        assertNull(ctrl(KeyEvent.KEYCODE_C))
        assertNull(ctrl(KeyEvent.KEYCODE_V))
        assertNull(ctrl(KeyEvent.KEYCODE_ENTER))
        assertNull(ctrl(KeyEvent.KEYCODE_N, shift = true))
        assertNull(Shortcuts.match(KeyChord(KeyEvent.KEYCODE_N)))
        assertNull(Shortcuts.match(KeyChord(KeyEvent.KEYCODE_ESCAPE, shift = true)))
        assertNull(Shortcuts.match(KeyChord(KeyEvent.KEYCODE_K, ctrl = true, alt = true)))
    }

    @Test fun `every shortcut has its own keys`() {
        val keys = Shortcut.entries.map { it.keys } + Shortcuts.SEND_KEYS
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test fun `recent chats cycle and wrap`() {
        val recent = listOf("a", "b", "c")
        assertEquals("a", Shortcuts.cycle(recent, null, forward = true))
        assertEquals("b", Shortcuts.cycle(recent, "a", forward = true))
        assertEquals("a", Shortcuts.cycle(recent, "c", forward = true))
        assertEquals("c", Shortcuts.cycle(recent, "a", forward = false))
        assertNull(Shortcuts.cycle(listOf("a"), "a", forward = true))
        assertNull(Shortcuts.cycle(emptyList(), null, forward = true))
    }

    @Test fun `wide windows pin the sidebar and split when there's room`() {
        assertFalse(WindowLayout.pinnedSidebar(411))
        assertFalse(WindowLayout.pinnedSidebar(839))
        assertTrue(WindowLayout.pinnedSidebar(840))
        // Phone and small tablet portrait: no split.
        assertFalse(WindowLayout.twoPane(411, sidebarShown = true))
        assertFalse(WindowLayout.twoPane(700, sidebarShown = true))
        // Tablet portrait: the drawer isn't pinned, so the whole width counts.
        assertTrue(WindowLayout.twoPane(800, sidebarShown = true))
        // Unfolded foldable: the pinned sidebar leaves too little, until it's hidden.
        assertFalse(WindowLayout.twoPane(900, sidebarShown = true))
        assertTrue(WindowLayout.twoPane(900, sidebarShown = false))
        assertTrue(WindowLayout.twoPane(1280, sidebarShown = true))
    }
}
