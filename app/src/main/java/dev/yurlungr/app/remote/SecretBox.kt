package dev.yurlungr.app.remote

import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import androidx.core.content.edit
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.io.encoding.Base64

/**
 * Seals small secrets (pairing code, device secrets) before they go into SharedPreferences, so
 * `cat` on the shared_prefs XML files from a runtime process shows ciphertext, not the secret.
 *
 * Stored form: `k1:` + base64(iv || ciphertext+tag), AES-256-GCM under a key from [keyProvider]
 * (in the app, a non-exportable Android Keystore key). A value without the prefix is legacy
 * plaintext: [read] returns it and a sealed form to write back (migration).
 *
 * Fails safe: if the key is unavailable, [seal] keeps the plaintext (logged) so pairing still
 * works; the next [read] with a working key migrates it.
 */
class SecretBox(
    private val keyProvider: () -> SecretKey,
    private val log: (String, Throwable?) -> Unit = { _, _ -> },
) {
    @Volatile private var key: SecretKey? = null

    private fun key(): SecretKey = key ?: keyProvider().also { key = it }

    /** The sealed form of [plain], or [plain] itself if sealing failed. */
    fun seal(plain: String): String = try {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        // The provider picks a fresh random IV (Android Keystore insists on it).
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        require(iv != null && iv.size == IV_BYTES) { "Unexpected IV" }
        PREFIX + Base64.encode(iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8)))
    } catch (e: Exception) {
        log("Couldn't seal a secret; storing it unsealed", e)
        plain
    }

    fun isSealed(stored: String): Boolean = stored.startsWith(PREFIX)

    /** The secret in [stored], or null if it's sealed and can't be opened (key gone, or corrupt). */
    fun open(stored: String): String? {
        if (!isSealed(stored)) return stored
        return try {
            val blob = Base64.decode(stored.substring(PREFIX.length))
            require(blob.size >= IV_BYTES + TAG_BYTES) { "Too short" }
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BYTES * 8, blob, 0, IV_BYTES))
            String(cipher.doFinal(blob, IV_BYTES, blob.size - IV_BYTES), Charsets.UTF_8)
        } catch (e: Exception) {
            log("Couldn't open a sealed secret", e)
            null
        }
    }

    /** [value] is the secret (null if unreadable); [rewrite], when set, is what to store instead. */
    data class Read(val value: String?, val rewrite: String?)

    fun read(stored: String?): Read {
        if (stored == null) return Read(null, null)
        if (isSealed(stored)) return Read(open(stored), null)
        val sealed = seal(stored)
        return Read(stored, sealed.takeIf { isSealed(it) })
    }

    /** Reads [key] from [prefs], migrating a plaintext value to its sealed form. */
    fun readPref(prefs: SharedPreferences, key: String): String? {
        val r = read(prefs.getString(key, null))
        r.rewrite?.let { prefs.edit { putString(key, it) } }
        return r.value
    }

    fun writePref(prefs: SharedPreferences, key: String, value: String) = prefs.edit { putString(key, seal(value)) }

    companion object {
        const val PREFIX = "k1:"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12
        private const val TAG_BYTES = 16
        private const val ALIAS = "yurlungr-remote-secrets"

        /** The app's box: its key lives in Android Keystore and never leaves it. */
        val app: SecretBox by lazy { SecretBox(::keystoreKey) { msg, e -> Log.w("SecretBox", msg, e) } }

        private fun keystoreKey(): SecretKey {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
            return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(
                    KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .build(),
                )
            }.generateKey()
        }
    }
}
