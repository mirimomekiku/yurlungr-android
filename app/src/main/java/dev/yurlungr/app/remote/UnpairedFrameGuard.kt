package dev.yurlungr.app.remote

import java.nio.ByteBuffer

/**
 * Reads WebSocket frame headers (RFC 6455 §5.2) from raw socket bytes and refuses any frame
 * longer than [maxPayload]. The WebSocket library allocates a frame's whole buffer as soon as its
 * header arrives, up to the server's 64 MB limit; before pairing, a peer could make it do that
 * on several connections at once. Headers may be split across reads, so state carries over.
 */
class UnpairedFrameGuard(private val maxPayload: Long) {
    /** Payload bytes of the current frame still to skip. */
    private var remaining = 0L
    /** A header split across reads, so far. */
    private val header = ByteArray(14)
    private var headerLength = 0

    /** False when [bytes] start or announce a frame over the limit. Reads [bytes] to its end. */
    fun accept(bytes: ByteBuffer): Boolean {
        while (bytes.hasRemaining()) {
            if (remaining > 0) {
                val skip = minOf(remaining, bytes.remaining().toLong()).toInt()
                bytes.position(bytes.position() + skip)
                remaining -= skip
                continue
            }
            header[headerLength++] = bytes.get()
            val needed = neededHeaderLength() ?: continue
            if (headerLength < needed) continue
            val length = payloadLength()
            if (length < 0 || length > maxPayload) return false
            remaining = length
            headerLength = 0
        }
        return true
    }

    /** The full header length, once the first two bytes are known; null before that. */
    private fun neededHeaderLength(): Int? {
        if (headerLength < 2) return null
        val masked = header[1].toInt() and 0x80 != 0
        val base = when (header[1].toInt() and 0x7F) {
            126 -> 4
            127 -> 10
            else -> 2
        }
        return base + if (masked) 4 else 0
    }

    private fun payloadLength(): Long = when (val len7 = header[1].toInt() and 0x7F) {
        126 -> ((header[2].toLong() and 0xFF) shl 8) or (header[3].toLong() and 0xFF)
        127 -> (0 until 8).fold(0L) { acc, i -> (acc shl 8) or (header[2 + i].toLong() and 0xFF) }
        else -> len7.toLong()
    }
}

/**
 * Finds the end of the HTTP handshake (its blank line) in a connection's reads, so every byte
 * after it can go through [UnpairedFrameGuard]. The blank line may be split across reads.
 */
class HandshakeEnd {
    /** Bytes of "\r\n\r\n" matched so far, carried across reads. */
    private var matched = 0
    var done = false
        private set

    /** The bytes of [bytes] after the handshake (a duplicate; [bytes] isn't moved), or null if it hasn't ended yet. */
    fun after(bytes: ByteBuffer): ByteBuffer? {
        if (done) return bytes.duplicate()
        for (i in bytes.position() until bytes.limit()) {
            val b = bytes.get(i)
            matched = when {
                b == MARKER[matched] -> matched + 1
                b == MARKER[0] -> 1
                else -> 0
            }
            if (matched == MARKER.size) {
                done = true
                return bytes.duplicate().also { it.position(i + 1) }
            }
        }
        return null
    }

    private companion object {
        val MARKER = "\r\n\r\n".toByteArray()
    }
}
