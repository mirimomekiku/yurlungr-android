package dev.yurlungr.app.ui

import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit

/**
 * The app's main colour (buttons, selections, links). Default is ink in light mode and near-white
 * in dark mode; the others are the Bento tile colours, deepened for light mode.
 */
enum class Accent(val label: String, val light: Long?, val onLight: Long?, val dark: Long?, val onDark: Long?) {
    Default("Default", null, null, null, null),
    Lime("Lime", 0xFF3F6212, 0xFFFFFFFF, 0xFFD4F36B, 0xFF10120A),
    Lilac("Lilac", 0xFF6D28D9, 0xFFFFFFFF, 0xFFC9B8FF, 0xFF17112B),
    Sky("Sky", 0xFF0369A1, 0xFFFFFFFF, 0xFF8FD6FF, 0xFF062033),
    Peach("Peach", 0xFFC2410C, 0xFFFFFFFF, 0xFFFFB98C, 0xFF2B1306),
    Rose("Rose", 0xFFBE123C, 0xFFFFFFFF, 0xFFFDA4AF, 0xFF4C0519),
}

/** Light, dark, or whatever the phone uses. */
enum class ThemeMode(val label: String) { System("System"), Light("Light"), Dark("Dark") }

/** The app's appearance setting (Settings → Appearance). Reading [mode] in composition subscribes to changes. */
object ThemePrefs {
    private const val PREFS = "appearance"
    private const val KEY = "theme_mode"

    var mode by mutableStateOf(ThemeMode.System)
        private set
    var accent by mutableStateOf(Accent.Default)
        private set
    /** Pure black backgrounds in dark mode (OLED screens). */
    var black by mutableStateOf(false)
        private set

    /** Call before the first frame, so the app never flashes the wrong theme. */
    fun load(context: Context) {
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
        mode = ThemeMode.entries.firstOrNull { it.name == saved } ?: ThemeMode.System
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        accent = Accent.entries.firstOrNull { it.name == prefs.getString("accent", null) } ?: Accent.Default
        black = prefs.getBoolean("black", false)
    }

    fun setAccent(context: Context, value: Accent) {
        accent = value
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putString("accent", value.name) }
    }

    fun setBlack(context: Context, value: Boolean) {
        black = value
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean("black", value) }
    }

    fun set(context: Context, value: ThemeMode) {
        mode = value
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putString(KEY, value.name) }
    }

    /** Whether the app is dark right now: the choice, or the system's when following it. */
    @Composable
    fun isDark(): Boolean = when (mode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
}
