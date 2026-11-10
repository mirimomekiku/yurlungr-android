package dev.yurlungr.app.editor

import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * What a file looked like on disk when the editor loaded (or last saved) it. If it differs at
 * save time, something else (an agent, `git pull`, a stash pop) changed the file meanwhile.
 */
data class DiskStamp(val exists: Boolean, val length: Long, val modified: Long) {
    companion object {
        fun of(file: File): DiskStamp =
            if (file.exists()) DiskStamp(true, file.length(), file.lastModified()) else DiskStamp(false, 0, 0)
    }
}

object FileSave {
    /**
     * Writes [text] to [file] without ever leaving it empty or half-written: the bytes go to a
     * temporary file in the same folder, are synced, then renamed over the target. Symlinks are
     * followed (the link stays a link) and the original permission bits are kept.
     */
    @Throws(IOException::class)
    fun writeAtomically(file: File, text: String) {
        val target = if (file.exists()) file.canonicalFile else file.absoluteFile
        val dir = target.parentFile ?: throw IOException("No parent folder for ${file.path}")
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Can't create ${dir.path}")
        val tmp = File.createTempFile(".${target.name}.", ".yurlungr-save", dir)
        try {
            FileOutputStream(tmp).use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
                out.flush()
                out.fd.sync()
            }
            if (target.exists()) copyMode(target, tmp)
            replace(tmp, target)
        } catch (e: Throwable) {
            tmp.delete()
            throw e
        }
    }

    /** rename(2): atomically replaces [target] on Android's file systems. */
    private fun replace(tmp: File, target: File) {
        try {
            android.system.Os.rename(tmp.path, target.path)
            return
        } catch (e: android.system.ErrnoException) {
            throw IOException("Can't replace ${target.path}: ${e.message}", e)
        } catch (_: RuntimeException) {
            // Not on Android (JVM unit tests): fall through to java.io.
        }
        if (tmp.renameTo(target)) return
        // Windows can't rename over an existing file; off-device only, so non-atomic is fine.
        if (!target.delete() || !tmp.renameTo(target)) throw IOException("Can't replace ${target.path}")
    }

    /** Best effort: keeps e.g. a script's executable bit (temp files are created 0600). */
    private fun copyMode(from: File, to: File) {
        runCatching {
            val mode = android.system.Os.stat(from.path).st_mode and 0x0FFF
            android.system.Os.chmod(to.path, mode)
        }.onFailure {
            // Not on Android (unit tests) or not permitted: fall back to the executable bit.
            if (from.canExecute()) to.setExecutable(true, true)
        }
    }
}
