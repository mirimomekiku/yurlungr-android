package dev.yurlungr.app.voice

import android.content.Intent
import android.speech.tts.TextToSpeech
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.yurlungr.app.ui.AppColors
import dev.yurlungr.app.ui.AppSheet
import dev.yurlungr.app.ui.Ph
import dev.yurlungr.app.ui.PhIcon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.coroutines.resume

/** One installed engine voice, as offered in the picker. */
private class VoiceOption(val name: String, val tag: String, val language: String, val langCode: String, val online: Boolean)

/** Settings → Read aloud: speed, pitch, language and voice, and a sample to hear them. */
@Composable
fun VoiceSettingsSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { VoicePrefs.load(context) }
    // Null while the engine starts.
    var voices by remember { mutableStateOf<List<VoiceOption>?>(null) }
    LaunchedEffect(Unit) {
        val engine = suspendCancellableCoroutine { cont -> Speaker.withEngine(context) { if (cont.isActive) cont.resume(it) } }
        voices = if (engine == null) emptyList() else withContext(Dispatchers.IO) {
            // Asking the engine is a call into another app: kept off the main thread.
            runCatching {
                engine.voices.orEmpty()
                    .filter { TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features.orEmpty() }
                    .map { VoiceOption(it.name, it.locale.toLanguageTag(), it.locale.displayName, it.locale.language, it.isNetworkConnectionRequired) }
                    .sortedWith(compareBy({ it.language }, { it.online }, { it.name }))
            }.getOrDefault(emptyList())
        }
    }
    DisposableStop()
    AppSheet(
        "Read aloud",
        onDismiss = onDismiss,
        subtitle = "How replies sound when a chat reads them to you. Turn it on per chat from the chat menu.",
        icon = SpeakerHigh,
        tile = AppColors.tiles.lime,
        actions = {
            val playing = Speaker.isSpeaking(SAMPLE)
            Button(
                onClick = { if (playing) Speaker.stop(SAMPLE) else Speaker.speak(context, "Hi! This is how replies will sound.", SAMPLE) },
                enabled = Speaker.available,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            ) { Text(if (playing) "Stop" else "Play a sample") }
        },
    ) {
        if (!Speaker.available) {
            Text(
                "No text-to-speech engine is installed. Install one, like Speech Services by Google, then come back.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            OutlinedButton(onClick = {
                runCatching { context.startActivity(Intent("com.android.settings.TTS_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }) { Text("Text-to-speech settings") }
        }
        Setting("Speed", VoicePrefs.rate) { VoicePrefs.setRate(context, it) }
        Setting("Pitch", VoicePrefs.pitch) { VoicePrefs.setPitch(context, it) }

        val all = voices
        // From the configuration, so the sheet follows a change of the phone's language.
        val phoneLocale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0] ?: Locale.ROOT
        val phoneLanguage = phoneLocale.language
        val languages = all.orEmpty().distinctBy { it.tag }.sortedBy { it.language }
        val selectedLanguage = VoicePrefs.language
        Picker(
            title = "Language",
            value = when {
                selectedLanguage.isBlank() -> "Phone language (${phoneLocale.displayName})"
                else -> Locale.forLanguageTag(selectedLanguage).displayName
            },
            options = listOf("" to "Phone language") + languages.map { it.tag to it.language },
            enabled = all != null,
        ) { tag -> VoicePrefs.setLanguage(context, tag); Speaker.applySettings() }

        val forLanguage = all.orEmpty().filter {
            if (selectedLanguage.isBlank()) it.langCode == phoneLanguage else it.tag == selectedLanguage
        }
        // Engine voice names are codes ("en-us-x-iol-local"): numbered instead.
        val voiceOptions = listOf("" to "Default") + forLanguage.mapIndexed { i, v -> v.name to "Voice ${i + 1}" + (if (v.online) " · needs internet" else "") }
        Picker(
            title = "Voice",
            value = voiceOptions.firstOrNull { it.first == VoicePrefs.voice }?.second ?: "Default",
            options = voiceOptions,
            enabled = forLanguage.isNotEmpty(),
        ) { name -> VoicePrefs.setVoice(context, name); Speaker.applySettings() }
        if (all == null) Text("Looking for voices…", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private const val SAMPLE = "settings:sample"

/** The sample stops when the sheet closes. */
@Composable
private fun DisposableStop() {
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { Speaker.stop(SAMPLE) } }
}

@Composable
private fun Setting(title: String, saved: Float, onSave: (Float) -> Unit) {
    // Saved when the drag ends, not on every step of it.
    var value by remember(saved) { mutableFloatStateOf(saved) }
    Column {
        Text("$title · ${"%.1f".format(Locale.US, value)}×", style = MaterialTheme.typography.labelLarge)
        Slider(
            value = value,
            onValueChange = { value = it },
            onValueChangeFinished = { onSave(value); Speaker.applySettings() },
            valueRange = VoicePrefs.MIN..VoicePrefs.MAX,
            steps = 14,
        )
    }
}

@Composable
private fun Picker(title: String, value: String, options: List<Pair<String, String>>, enabled: Boolean, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.fillMaxWidth().clickable(enabled = enabled) { open = true }.padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.labelLarge)
                Text(
                    value,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            PhIcon(Ph.CaretDown, null, size = 14.dp, tint = MaterialTheme.colorScheme.outline)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for ((key, label) in options) DropdownMenuItem(
                text = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                onClick = { open = false; onPick(key) },
            )
        }
    }
}
