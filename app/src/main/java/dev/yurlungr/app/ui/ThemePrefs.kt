package dev.yurlungr.app.ui

import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit

/** Light, dark, or whatever the phone uses. */
enum class ThemeMode(val label: String) { System("System"), Light("Light"), Dark("Dark") }

/** The app's appearance setting (Settings → Appearance). Reading [mode] in composition subscribes to changes. */
object ThemePrefs {
    private const val PREFS = "appearance"
    private const val KEY = "theme_mode"

    var mode by mutableStateOf(ThemeMode.System)
        private set

    /** Call before the first frame, so the app never flashes the wrong theme. */
    fun load(context: Context) {
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
        mode = ThemeMode.entries.firstOrNull { it.name == saved } ?: ThemeMode.System
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
