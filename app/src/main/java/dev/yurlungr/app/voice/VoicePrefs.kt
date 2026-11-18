package dev.yurlungr.app.voice

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Read-aloud settings (Settings → Read aloud) and which chats read their replies aloud. */
object VoicePrefs {
    private const val PREFS = "voice"
    const val MIN = 0.5f
    const val MAX = 2f

    var rate by mutableFloatStateOf(1f)
        private set
    var pitch by mutableFloatStateOf(1f)
        private set
    /** BCP 47 tag; empty for the phone's language. */
    var language by mutableStateOf("")
        private set
    /** An engine voice name; empty for the language's default voice. */
    var voice by mutableStateOf("")
        private set
    /** Chats with "Read replies aloud" on. */
    var readAloudChats by mutableStateOf<Set<String>>(emptySet())
        private set

    private var loaded = false

    /** Reads the settings once, off the main thread (the first prefs access hits the disk). */
    suspend fun load(context: Context) {
        if (loaded) return
        val app = context.applicationContext
        val p = withContext(Dispatchers.IO) { app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).also { it.all } }
        if (loaded) return
        rate = p.getFloat("rate", 1f).coerceIn(MIN, MAX)
        pitch = p.getFloat("pitch", 1f).coerceIn(MIN, MAX)
        language = p.getString("language", "").orEmpty()
        voice = p.getString("voice", "").orEmpty()
        readAloudChats = p.getStringSet("read_aloud", emptySet()).orEmpty().toSet()
        loaded = true
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun setRate(context: Context, value: Float) { rate = value.coerceIn(MIN, MAX); prefs(context).edit { putFloat("rate", rate) } }
    fun setPitch(context: Context, value: Float) { pitch = value.coerceIn(MIN, MAX); prefs(context).edit { putFloat("pitch", pitch) } }

    /** A new language drops the chosen voice (it belonged to the old one). */
    fun setLanguage(context: Context, tag: String) {
        language = tag
        voice = ""
        prefs(context).edit { putString("language", tag); putString("voice", "") }
    }

    fun setVoice(context: Context, name: String) { voice = name; prefs(context).edit { putString("voice", name) } }

    fun readsAloud(chatId: String) = chatId in readAloudChats

    fun setReadAloud(context: Context, chatId: String, on: Boolean) {
        readAloudChats = if (on) readAloudChats + chatId else readAloudChats - chatId
        val copy = readAloudChats.toSet()
        prefs(context).edit { putStringSet("read_aloud", copy) }
    }
}
