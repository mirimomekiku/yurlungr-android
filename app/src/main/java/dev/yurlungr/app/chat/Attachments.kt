package dev.yurlungr.app.chat

import androidx.core.graphics.scale
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.ByteArrayOutputStream
import java.io.File

/** Turning picked files/images into ACP prompt content. */
object Attachments {
    const val MAX_BYTES = 10 * 1024 * 1024
    /** Photos may be bigger, as they're downscaled, but are still read into memory first. */
    const val MAX_IMAGE_READ_BYTES = 40 * 1024 * 1024
    private const val MAX_EMBEDDED_TEXT = 256 * 1024
    /** Longest image side sent to models; larger photos are downscaled (and re-encoded as JPEG). */
    private const val MAX_IMAGE_SIDE = 2000
    private const val MAX_IMAGE_BYTES = 3_500_000

    private val TEXT_EXTENSIONS = setOf(
        "txt", "md", "kt", "kts", "java", "xml", "json", "gradle", "js", "mjs", "ts", "tsx", "jsx", "py", "sh",
        "yml", "yaml", "toml", "html", "css", "c", "h", "cc", "cpp", "hpp", "rs", "go", "log", "csv", "properties",
        "sql", "swift", "dart", "rb", "php", "ini", "conf", "env", "gitignore", "pro",
    )

    /**
     * Saves each attachment into `<cwd>/.yurlungr/attachments` (so the agent can also open it by
     * path) and returns prompt blocks: images inline when the agent takes images, small text files
     * embedded when it takes embedded context, everything else as a resource link.
     */
    fun toPromptBlocks(cwd: File, attachments: List<Attachment>, caps: PromptCaps): List<JsonObject> {
        if (attachments.isEmpty()) return emptyList()
        val dir = File(cwd, ".yurlungr/attachments").apply { mkdirs() }
        // A cloned repo could ship .yurlungr (or attachments/) as a symlink to somewhere else.
        if (dir.canonicalFile != File(cwd.canonicalFile, ".yurlungr/attachments")) {
            throw java.io.IOException("${dir.path} is a link to another folder; not saving attachments there")
        }
        // Keep attachments out of the user's repo without touching their .gitignore.
        File(cwd, ".yurlungr/.gitignore").takeIf { !it.exists() }?.writeText("*\n")
        val stamp = System.currentTimeMillis()
        return attachments.mapIndexed { i, a ->
            // The index keeps two files with the same name in one message apart.
            val file = File(dir, "$stamp-$i-${safeName(a.name)}")
            file.writeBytes(a.bytes)
            val uri = "file://${file.path}"
            when {
                a.isImage && caps.image -> buildJsonObject {
                    put("type", "image")
                    put("mimeType", a.mimeType)
                    put("data", Base64.encodeToString(a.bytes, Base64.NO_WRAP))
                    put("uri", uri)
                }
                isText(a) && caps.embeddedContext && a.bytes.size <= MAX_EMBEDDED_TEXT -> buildJsonObject {
                    put("type", "resource")
                    putJsonObject("resource") {
                        put("uri", uri)
                        put("mimeType", a.mimeType)
                        put("text", a.bytes.toString(Charsets.UTF_8))
                    }
                }
                else -> buildJsonObject {
                    put("type", "resource_link")
                    put("uri", uri)
                    put("name", a.name)
                    put("mimeType", a.mimeType)
                    put("size", a.bytes.size)
                }
            }
        }
    }

    fun isText(a: Attachment): Boolean {
        val ext = a.name.substringAfterLast('.', "").lowercase()
        val textual = a.mimeType.startsWith("text/") || ext in TEXT_EXTENSIONS ||
            a.mimeType in setOf("application/json", "application/xml", "application/javascript", "application/x-sh")
        return textual && a.bytes.take(8000).none { it == 0.toByte() }
    }

    private fun safeName(name: String) = name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80).ifEmpty { "file" }

    /** Reads a picked file. Returns null with a reason if it can't be attached. */
    fun read(context: Context, uri: Uri): Pair<Attachment?, String?> {
        val resolver = context.contentResolver
        var name = "attachment"
        var size = -1L
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.getString(0)?.let { name = it }
                if (!c.isNull(1)) size = c.getLong(1)
            }
        }
        val mime = resolver.getType(uri) ?: guessMime(name)
        // Photos can be big but get downscaled; other files must fit as-is.
        val image = mime.startsWith("image/")
        val cap = if (image) MAX_IMAGE_READ_BYTES else MAX_BYTES
        val tooLarge = "$name is larger than ${cap / (1024 * 1024)} MB"
        if (size > cap) return null to tooLarge
        // SIZE can be missing (or wrong), so the cap holds while reading too.
        val bytes = resolver.openInputStream(uri)?.use { readCapped(it, cap) ?: return null to tooLarge }
            ?: return null to "Couldn't read $name"
        if (!image) return Attachment(name, mime, bytes) to null
        val (data, type) = prepareImage(bytes, mime)
        val finalName = if (type == "image/jpeg" && mime != "image/jpeg") name.substringBeforeLast('.') + ".jpg" else name
        return Attachment(finalName, type, data) to null
    }

    /** All of [input], or null as soon as it passes [max] bytes (the rest isn't read). */
    internal fun readCapped(input: java.io.InputStream, max: Int): ByteArray? {
        val buf = ByteArrayOutputStream()
        val chunk = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(chunk)
            if (n < 0) return buf.toByteArray()
            if (buf.size() + n > max) return null
            buf.write(chunk, 0, n)
        }
    }

    /** Downscales/re-encodes images that exceed model limits; small ones pass through unchanged. */
    private fun prepareImage(bytes: ByteArray, mime: String): Pair<ByteArray, String> {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        if (longest <= 0) return bytes to mime // not decodable; send as-is
        if (longest <= MAX_IMAGE_SIDE && bytes.size <= MAX_IMAGE_BYTES) return bytes to mime

        var sample = 1
        while (longest / (sample * 2) >= MAX_IMAGE_SIDE) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return bytes to mime
        val scale = MAX_IMAGE_SIDE.toFloat() / maxOf(decoded.width, decoded.height)
        val bitmap = if (scale < 1f) {
            decoded.scale((decoded.width * scale).toInt(), (decoded.height * scale).toInt())
        } else {
            decoded
        }
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
        return out.toByteArray() to "image/jpeg"
    }

    private fun guessMime(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "json" -> "application/json"
        "pdf" -> "application/pdf"
        else -> if (name.substringAfterLast('.', "").lowercase() in TEXT_EXTENSIONS) "text/plain" else "application/octet-stream"
    }
}
