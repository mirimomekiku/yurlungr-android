package dev.yurlungr.app.editor

import android.util.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.security.MessageDigest

/**
 * Unsaved editor text on disk (`filesDir/unsaved/<sha1 of the file's canonical path>`), so edits
 * survive the app's process being killed in the background. One JSON document per file, holding
 * its path too (the name is only a hash). Gone once the file is saved, discarded or closed.
 */
object UnsavedBackups {
    private const val VERSION = 1

    fun encode(path: String, kept: OpenFiles.Kept): String = buildJsonObject {
        put("v", VERSION)
        put("path", path)
        put("text", kept.text)
        put("saved", kept.saved)
        put("exists", kept.stamp.exists)
        put("length", kept.stamp.length)
        put("modified", kept.stamp.modified)
    }.toString()

    /** The path and unsaved text in [encoded], or null if it isn't a backup this version wrote. */
    fun decode(encoded: String): Pair<String, OpenFiles.Kept>? = runCatching {
        val o = Json.parseToJsonElement(encoded) as JsonObject
        fun prim(k: String) = o[k] as? JsonPrimitive
        if (prim("v")?.longOrNull != VERSION.toLong()) return null
        val path = prim("path")?.takeIf { it.isString }?.contentOrNull ?: return null
        val text = prim("text")?.takeIf { it.isString }?.contentOrNull ?: return null
        val saved = prim("saved")?.takeIf { it.isString }?.contentOrNull ?: return null
        val stamp = DiskStamp(
            exists = prim("exists")?.booleanOrNull ?: return null,
            length = prim("length")?.longOrNull ?: return null,
            modified = prim("modified")?.longOrNull ?: return null,
        )
        path to OpenFiles.Kept(text, saved, stamp)
    }.getOrNull()

    /** The backup's name for [path]: the same for every spelling of the path (symlinks, ".."). */
    fun nameFor(path: String): String {
        val canonical = runCatching { File(path).canonicalPath }.getOrElse { File(path).absolutePath }
        return MessageDigest.getInstance("SHA-1").digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    fun write(dir: File, path: String, kept: OpenFiles.Kept) {
        runCatching { FileSave.writeAtomically(File(dir, nameFor(path)), encode(path, kept)) }
            .onFailure { logFailure("Couldn't back up unsaved edits", it) }
    }

    fun delete(dir: File, path: String) {
        File(dir, nameFor(path)).delete()
    }

    /**
     * The unsaved edits backed up for [path], when they differ from what the file holds now
     * ([diskText]); a backup that matches the file (it was saved meanwhile) or isn't readable is removed.
     */
    fun restore(dir: File, path: String, diskText: String): OpenFiles.Kept? {
        val file = File(dir, nameFor(path))
        if (!file.isFile) return null
        val decoded = runCatching { decode(file.readText()) }.getOrNull()
        val kept = decoded?.second?.takeIf { decoded.first == path || nameFor(decoded.first) == file.name }
        if (kept == null || kept.text == diskText) {
            file.delete()
            return null
        }
        return kept
    }

    private fun logFailure(what: String, e: Throwable) {
        // android.util.Log isn't there in JVM unit tests.
        runCatching { Log.w("UnsavedBackups", "$what: ${e.message}") }
    }
}
