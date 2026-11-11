package dev.yurlungr.app.remote

import android.content.Context
import androidx.core.content.edit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.security.SecureRandom

/**
 * A device this phone shares with. It paired once with the pairing code and then got a secret of
 * its own, which it uses instead of the code from then on. So one device can be revoked (its
 * secret deleted) without re-pairing the others, and changing the code only affects new devices.
 */
data class TrustedDevice(val id: String, val name: String, val secret: String, val added: Long, val lastSeen: Long)

/** The host's trusted devices, in app-private preferences. */
class RemoteTrust(private val context: Context) {
    private val prefs get() = context.getSharedPreferences("remote_trust", Context.MODE_PRIVATE)

    fun all(): List<TrustedDevice> = decode(prefs.getString(KEY, null))

    fun find(id: String?): TrustedDevice? = id?.let { wanted -> all().firstOrNull { it.id == wanted } }

    /** A new device, with a fresh id and secret. */
    fun add(name: String): TrustedDevice {
        val now = System.currentTimeMillis()
        val device = TrustedDevice(randomHex(16), name.take(80).ifBlank { "Unknown device" }, randomHex(SECRET_BYTES), now, now)
        save(all() + device)
        return device
    }

    fun touch(id: String, name: String?) = save(all().map { if (it.id == id) it.copy(lastSeen = System.currentTimeMillis(), name = name?.take(80)?.ifBlank { null } ?: it.name) else it })

    fun revoke(id: String) = save(all().filterNot { it.id == id })

    private fun save(list: List<TrustedDevice>) = prefs.edit { putString(KEY, encode(list)) }

    companion object {
        private const val KEY = "devices"
        /** As strong as a pairing code or more: 32 random bytes, used where the code would be. */
        const val SECRET_BYTES = 32
        private val random = SecureRandom()

        fun randomHex(bytes: Int): String = RemotePairing.toHex(ByteArray(bytes).also(random::nextBytes))

        internal fun encode(list: List<TrustedDevice>): String = JsonArray(list.map { d ->
            buildJsonObject {
                put("id", d.id); put("name", d.name); put("secret", d.secret); put("added", d.added); put("lastSeen", d.lastSeen)
            }
        }).toString()

        internal fun decode(text: String?): List<TrustedDevice> = runCatching {
            Json.parseToJsonElement(text ?: return emptyList()).jsonArray.mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                fun s(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
                fun l(k: String) = (o[k] as? JsonPrimitive)?.longOrNull ?: 0L
                TrustedDevice(s("id") ?: return@mapNotNull null, s("name").orEmpty(), s("secret") ?: return@mapNotNull null, l("added"), l("lastSeen"))
            }
        }.getOrDefault(emptyList())
    }
}
