package dev.yurlungr.app.voice

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale

/**
 * The app's one text-to-speech engine. Main thread only. Created on first use, shut down after a
 * minute of silence (an idle engine still holds a service connection). Takes transient audio
 * focus while speaking and stops when another app takes it (a call, navigation).
 */
object Speaker {
    enum class End { Done, Interrupted, Failed }

    /** Who's speaking now (e.g. a chat's reply), for Stop buttons; null when silent. */
    var owner by mutableStateOf<String?>(null)
        private set

    /** False once the engine failed to start (none installed): the UI says so. */
    var available by mutableStateOf(true)
        private set

    private const val IDLE_RELEASE_MS = 60_000L
    private val main = Handler(Looper.getMainLooper())
    @SuppressLint("StaticFieldLeak") // always the application context
    private lateinit var app: Context
    private var tts: TextToSpeech? = null
    private var ready = false
    private var seq = 0
    private var current: Request? = null
    private var warnedLanguage: String? = null
    private val waiters = ArrayList<(TextToSpeech?) -> Unit>()

    private class Request(val text: String, val owner: String, val onEnd: (End) -> Unit) {
        var base = ""
        var last = -1
    }

    private val attributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    /** Speaks [text] (plain, already speakable) for [owner], replacing whatever was being said. */
    fun speak(context: Context, text: String, owner: String, onEnd: (End) -> Unit = {}) {
        app = context.applicationContext
        main.removeCallbacks(releaseIdle)
        finish(End.Interrupted)
        val request = Request(text, owner, onEnd)
        current = request
        this.owner = owner
        if (text.isBlank()) return finish(End.Done)
        val engine = tts
        when {
            engine != null && ready -> start(engine, request)
            engine == null -> create()
            // Still starting: start() runs from onInit.
        }
    }

    /** Stops speaking; with [ownerPrefix], only if that's who is speaking. */
    fun stop(ownerPrefix: String? = null) {
        val o = owner ?: return
        if (ownerPrefix == null || o.startsWith(ownerPrefix)) finish(End.Interrupted)
    }

    fun isSpeaking(ownerPrefix: String): Boolean = owner?.startsWith(ownerPrefix) == true

    /** Runs [block] with a started engine (null if there's none), e.g. to list voices. */
    fun withEngine(context: Context, block: (TextToSpeech?) -> Unit) {
        app = context.applicationContext
        main.removeCallbacks(releaseIdle)
        val engine = tts
        if (engine != null && ready) {
            block(engine)
            scheduleRelease()
            return
        }
        waiters += block
        if (engine == null) create()
    }

    /** Settings changed: applies them to the next utterance. */
    fun applySettings() {
        val engine = tts ?: return
        if (ready) configure(engine)
    }

    private fun create() {
        ready = false
        tts = TextToSpeech(app) { status -> main.post { onInit(status) } }
    }

    private fun onInit(status: Int) {
        val engine = tts ?: return
        if (status != TextToSpeech.SUCCESS) {
            runCatching { engine.shutdown() }
            tts = null
            available = false
            if (current != null) {
                Toast.makeText(app, "No text-to-speech engine. Install one (like Speech Services by Google) to hear replies.", Toast.LENGTH_LONG).show()
            }
            flushWaiters(null)
            finish(End.Failed)
            return
        }
        ready = true
        available = true
        engine.setAudioAttributes(attributes)
        engine.setOnUtteranceProgressListener(progress)
        configure(engine)
        flushWaiters(engine)
        val request = current
        if (request != null) start(engine, request) else scheduleRelease()
    }

    private fun flushWaiters(engine: TextToSpeech?) {
        val pending = waiters.toList()
        waiters.clear()
        pending.forEach { runCatching { it(engine) } }
    }

    private fun configure(engine: TextToSpeech) {
        engine.setSpeechRate(VoicePrefs.rate)
        engine.setPitch(VoicePrefs.pitch)
        val locale = VoicePrefs.language.takeIf { it.isNotBlank() }?.let(Locale::forLanguageTag) ?: Locale.getDefault()
        val chosen = VoicePrefs.voice.takeIf { it.isNotBlank() }?.let { name ->
            runCatching { engine.voices?.firstOrNull { it.name == name } }.getOrNull()
        }
        if (chosen != null && runCatching { engine.setVoice(chosen) }.getOrDefault(TextToSpeech.ERROR) == TextToSpeech.SUCCESS) return
        val result = runCatching { engine.setLanguage(locale) }.getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            // The engine keeps its own default voice; say so once per language rather than going silent.
            val tag = locale.toLanguageTag()
            if (warnedLanguage != tag) {
                warnedLanguage = tag
                Toast.makeText(app, "No voice installed for ${locale.displayName}; using the engine's default.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun start(engine: TextToSpeech, request: Request) {
        if (!requestFocus()) return finish(End.Interrupted)
        val max = (runCatching { TextToSpeech.getMaxSpeechInputLength() }.getOrDefault(4000) - 100).coerceIn(500, 3900)
        val pieces = SpeakableText.chunks(request.text, max)
        request.base = "u${++seq}"
        request.last = pieces.lastIndex
        pieces.forEachIndexed { i, piece ->
            val mode = if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            if (engine.speak(piece, mode, null, "${request.base}#$i") == TextToSpeech.ERROR) return finish(End.Failed)
        }
    }

    /** Engine callbacks come on a binder thread: hop to main and ignore stale utterances. */
    private val progress = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) = Unit

        override fun onDone(utteranceId: String?) {
            main.post {
                val r = current ?: return@post
                if (utteranceId == "${r.base}#${r.last}") finish(End.Done)
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) = failed(utteranceId)

        override fun onError(utteranceId: String?, errorCode: Int) = failed(utteranceId)

        private fun failed(utteranceId: String?) {
            main.post {
                val r = current ?: return@post
                if (utteranceId?.substringBefore('#') == r.base) finish(End.Failed)
            }
        }
    }

    private fun finish(end: End) {
        val request = current ?: return
        current = null
        owner = null
        if (end != End.Done) runCatching { tts?.stop() }
        abandonFocus()
        scheduleRelease()
        // Posted: a callback that speaks again or starts listening shouldn't re-enter this one.
        main.post { request.onEnd(end) }
    }

    private fun scheduleRelease() {
        main.removeCallbacks(releaseIdle)
        main.postDelayed(releaseIdle, IDLE_RELEASE_MS)
    }

    private val releaseIdle = Runnable {
        if (current != null || waiters.isNotEmpty()) return@Runnable
        runCatching { tts?.shutdown() }
        tts = null
        ready = false
    }

    // ---- Audio focus -------------------------------------------------------------------

    private var focusRequest: AudioFocusRequest? = null

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        // Ducking under a notification is fine; a call or another player taking over stops us.
        if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            main.post { finish(End.Interrupted) }
        }
    }

    private fun requestFocus(): Boolean {
        val am = app.getSystemService(AudioManager::class.java) ?: return true
        val result = if (Build.VERSION.SDK_INT >= 26) {
            val r = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(attributes)
                .setOnAudioFocusChangeListener(focusListener, main)
                .build()
            focusRequest = r
            am.requestAudioFocus(r)
        } else {
            @Suppress("DEPRECATION")
            am.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        }
        return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonFocus() {
        val am = app.getSystemService(AudioManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= 26) {
            focusRequest?.let { am.abandonAudioFocusRequest(it) }
            focusRequest = null
        } else {
            @Suppress("DEPRECATION")
            am.abandonAudioFocus(focusListener)
        }
    }
}
