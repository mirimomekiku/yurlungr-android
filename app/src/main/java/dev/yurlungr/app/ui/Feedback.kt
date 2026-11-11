package dev.yurlungr.app.ui

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.RingtoneManager
import android.os.BatteryManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit

/**
 * Vibration and sound for the moments that matter (a task finished, an agent needs you), and the
 * battery/heat check before long jobs. Each can be turned off in Settings → Feedback.
 */
object Feedback {
    private const val PREFS = "feedback"
    var haptics by mutableStateOf(true)
        private set
    var sounds by mutableStateOf(true)
        private set
    var powerWarnings by mutableStateOf(true)
        private set

    fun load(context: Context) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        haptics = p.getBoolean("haptics", true)
        sounds = p.getBoolean("sounds", true)
        powerWarnings = p.getBoolean("power", true)
    }

    fun setHaptics(context: Context, on: Boolean) { haptics = on; context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean("haptics", on) } }
    fun setSounds(context: Context, on: Boolean) { sounds = on; context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean("sounds", on) } }
    fun setPowerWarnings(context: Context, on: Boolean) { powerWarnings = on; context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean("power", on) } }

    enum class Kind { Done, Failed, NeedsYou }

    /** While the app is open: a buzz (two for a failure or a question) and the notification sound. */
    fun signal(context: Context, kind: Kind) {
        if (haptics) runCatching {
            val vibrator = context.getSystemService(Vibrator::class.java) ?: return@runCatching
            val pattern = if (kind == Kind.Done) longArrayOf(0, 40) else longArrayOf(0, 40, 90, 40)
            if (Build.VERSION.SDK_INT >= 26) vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
            else @Suppress("DEPRECATION") vibrator.vibrate(pattern, -1)
        }
        if (sounds) runCatching {
            RingtoneManager.getRingtone(context, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))?.play()
        }
    }

    /** Battery level, charging, and temperature (°C), from the last battery broadcast. */
    data class Power(val percent: Int, val charging: Boolean, val celsius: Float)

    fun power(context: Context): Power? = runCatching {
        val i: Intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        Power(
            percent = if (level < 0) 100 else level * 100 / scale.coerceAtLeast(1),
            charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL,
            celsius = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f,
        )
    }.getOrNull()

    const val LOW_BATTERY = 20
    const val HOT_CELSIUS = 42f

    /** Why a long job should wait (low battery and not charging, or hot), or null. */
    fun powerConcern(power: Power?): String? = when {
        power == null -> null
        power.celsius >= HOT_CELSIUS -> "The phone is hot (${power.celsius.toInt()} °C). A long build heats it further and may be slowed down by the system."
        power.percent <= LOW_BATTERY && !power.charging -> "The battery is at ${power.percent}% and not charging. A long build uses a lot of it."
        else -> null
    }

    /** Commands worth a warning: builds, installs, test runs and setups (not a quick `ls`). */
    fun isHeavy(command: String): Boolean = Regex(
        "gradlew|\\bgradle\\b|cargo (build|test|check)|npm (install|ci|run build)|pnpm install|yarn install|bun install|" +
            "pip install|uv sync|go (build|test)|dotnet (build|test)|-setup\\.sh|self-test\\.sh|apt-get (update|upgrade|full-upgrade|install)|project-setup",
    ).containsMatchIn(command)
}
