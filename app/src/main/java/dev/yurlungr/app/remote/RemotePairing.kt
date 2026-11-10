package dev.yurlungr.app.remote

import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Pairing and transport security for [RemoteProtocol] version 2. The pairing code is never sent:
 *
 * 1. host → client (text): `challenge{v, nonce}` (a fresh random host nonce)
 * 2. client → host (text): `hello{v, nonce, proof, device}`; proof = HMAC(key, "client" ‖ hostNonce ‖ clientNonce)
 * 3. host → client (text): `auth{proof}`; proof = HMAC(key, "host" ‖ hostNonce ‖ clientNonce)
 *
 * Each side checks the other's proof, so a device that doesn't know the code can neither pair
 * nor pose as the host. Everything after that is a binary frame sealed with AES-256-GCM under
 * per-direction keys derived from the code and both nonces, with a frame counter as the GCM
 * nonce: someone relaying the connection can't read, change, replay or reorder messages.
 *
 * The code has ~99 bits of entropy, so guessing it from a recorded handshake is infeasible.
 */
object RemotePairing {
    const val NONCE_BYTES = 32
    private const val HMAC = "HmacSHA256"
    private val random = SecureRandom()

    /** Upper case, letters and digits only: "abcd-efgh" and "ABCDEFGH" are the same code. */
    fun normalizeCode(code: String): String = code.uppercase().filter { it in 'A'..'Z' || it in '0'..'9' }

    fun newNonce(): ByteArray = ByteArray(NONCE_BYTES).also(random::nextBytes)

    fun clientProof(code: String, hostNonce: ByteArray, clientNonce: ByteArray): ByteArray =
        derive(code, "client", hostNonce, clientNonce)

    fun hostProof(code: String, hostNonce: ByteArray, clientNonce: ByteArray): ByteArray =
        derive(code, "host", hostNonce, clientNonce)

    /** Constant-time comparison. */
    fun matches(expected: ByteArray, given: ByteArray?): Boolean =
        given != null && MessageDigest.isEqual(expected, given)

    /** The sealed channel for one side of a paired connection. */
    fun channel(code: String, hostNonce: ByteArray, clientNonce: ByteArray, isHost: Boolean): SecureChannel {
        val toHost = derive(code, "key client->host", hostNonce, clientNonce)
        val toClient = derive(code, "key host->client", hostNonce, clientNonce)
        return if (isHost) SecureChannel(sendKey = toClient, receiveKey = toHost) else SecureChannel(sendKey = toHost, receiveKey = toClient)
    }

    private fun pairingKey(code: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest("yurlungr-pairing-v2:${normalizeCode(code)}".toByteArray(Charsets.UTF_8))

    private fun derive(code: String, label: String, hostNonce: ByteArray, clientNonce: ByteArray): ByteArray {
        require(hostNonce.size == NONCE_BYTES && clientNonce.size == NONCE_BYTES) { "bad nonce" }
        val mac = Mac.getInstance(HMAC).apply { init(SecretKeySpec(pairingKey(code), HMAC)) }
        mac.update(label.toByteArray(Charsets.UTF_8))
        mac.update(0)
        mac.update(hostNonce)
        mac.update(clientNonce)
        return mac.doFinal()
    }

    fun toHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }

    /** Null unless [hex] is exactly [size] bytes of hex. */
    fun fromHex(hex: String?, size: Int): ByteArray? {
        if (hex == null || hex.length != size * 2) return null
        return runCatching { ByteArray(size) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() } }.getOrNull()
    }
}

/**
 * AES-256-GCM in each direction; the GCM nonce is the frame counter, so frames must be opened
 * in the order they were sealed (a WebSocket delivers them in order). Any failure means the
 * connection was tampered with and must be closed.
 */
class SecureChannel(sendKey: ByteArray, receiveKey: ByteArray) {
    private val sendKey = SecretKeySpec(sendKey, "AES")
    private val receiveKey = SecretKeySpec(receiveKey, "AES")
    private var sent = 0L
    private var received = 0L
    private val sendLock = Any()
    private val receiveLock = Any()

    fun seal(text: String): ByteArray = synchronized(sendLock) {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, sendKey, GCMParameterSpec(TAG_BITS, nonce(sent++)))
        cipher.doFinal(text.toByteArray(Charsets.UTF_8))
    }

    @Throws(GeneralSecurityException::class)
    fun open(frame: ByteArray): String = synchronized(receiveLock) {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, receiveKey, GCMParameterSpec(TAG_BITS, nonce(received)))
        val plain = cipher.doFinal(frame) // throws AEADBadTagException if forged, replayed or reordered
        received++
        String(plain, Charsets.UTF_8)
    }

    private fun nonce(counter: Long): ByteArray = ByteBuffer.allocate(12).putInt(0).putLong(counter).array()

    private companion object {
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
    }
}
