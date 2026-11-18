package dev.yurlungr.app.web

import android.system.Os
import dev.yurlungr.app.runtime.ProcessTree
import java.io.File

/** One row of /proc/net/tcp or /proc/net/tcp6. */
internal data class ProcSocket(val address: String, val port: Int, val state: Int, val uid: Int, val inode: Long) {
    val listening get() = state == PortOwners.TCP_LISTEN
}

/** What holds a local port, as far as this app can tell. */
internal sealed interface PortHolder {
    /** One of this app's own runtime processes (only those are visible, and only those can be stopped). */
    data class Process(val pid: Int, val command: String, val startTime: Long) : PortHolder
    /** The app's own process (the remote server, say): never offered for stopping. */
    data object ThisApp : PortHolder
    /** Another app on the phone: its processes are hidden from us. */
    data object OtherApp : PortHolder
    /** /proc/net isn't readable here, or the socket vanished in between. */
    data object Unknown : PortHolder
}

/**
 * Finds which process listens on a port: the LISTEN socket's inode from /proc/net/tcp(6), then
 * the pid whose /proc/<pid>/fd links to that socket. Android only shows the app's own processes,
 * which is all that may be stopped anyway.
 */
internal object PortOwners {
    const val TCP_LISTEN = 0x0A

    /** The socket rows of a /proc/net/tcp or tcp6 [text]; the header and malformed lines are skipped. */
    fun parseProcNet(text: String): List<ProcSocket> = text.lineSequence().mapNotNull { line ->
        // "sl local_address rem_address st tx:rx tr:when retrnsmt uid timeout inode …"
        val fields = line.trim().split(Regex("\\s+"))
        if (fields.size < 10 || !fields[0].endsWith(':')) return@mapNotNull null
        val local = fields[1].split(':')
        if (local.size != 2) return@mapNotNull null
        ProcSocket(
            address = decodeAddress(local[0]) ?: return@mapNotNull null,
            port = local[1].toIntOrNull(16) ?: return@mapNotNull null,
            state = fields[3].toIntOrNull(16) ?: return@mapNotNull null,
            uid = fields[7].toIntOrNull() ?: return@mapNotNull null,
            inode = fields[9].toLongOrNull() ?: return@mapNotNull null,
        )
    }.toList()

    /**
     * A /proc/net address in readable form. The kernel prints each 32-bit word in host byte
     * order, which is little-endian on Android's CPUs: "0100007F" is 127.0.0.1.
     */
    fun decodeAddress(hex: String): String? {
        if (hex.length != 8 && hex.length != 32 || hex.any { Character.digit(it, 16) < 0 }) return null
        val bytes = IntArray(hex.length / 2)
        for (word in 0 until hex.length / 8) for (b in 0 until 4) {
            val at = word * 8 + (3 - b) * 2
            bytes[word * 4 + b] = hex.substring(at, at + 2).toInt(16)
        }
        if (bytes.size == 4) return bytes.joinToString(".")
        if ((0 until 10).all { bytes[it] == 0 } && bytes[10] == 0xFF && bytes[11] == 0xFF) {
            return "::ffff:" + (12 until 16).joinToString(".") { bytes[it].toString() }
        }
        val groups = (0 until 8).map { bytes[it * 2] shl 8 or bytes[it * 2 + 1] }
        // The longest run of two or more zero groups becomes "::".
        var bestStart = -1; var bestLength = 0; var i = 0
        while (i < 8) {
            if (groups[i] != 0) { i++; continue }
            val start = i
            while (i < 8 && groups[i] == 0) i++
            if (i - start > bestLength && i - start >= 2) { bestStart = start; bestLength = i - start }
        }
        if (bestStart < 0) return groups.joinToString(":") { it.toString(16) }
        val head = groups.subList(0, bestStart).joinToString(":") { it.toString(16) }
        val tail = groups.subList(bestStart + bestLength, 8).joinToString(":") { it.toString(16) }
        return "$head::$tail"
    }

    /** The listening sockets on [port] in /proc/net [tables] (tcp and tcp6 text). */
    fun listeners(tables: List<String>, port: Int): List<ProcSocket> =
        tables.flatMap(::parseProcNet).filter { it.listening && it.port == port }

    /** The inode in a /proc/<pid>/fd link like "socket:[12345]". */
    fun socketInode(link: String): Long? = Regex("^socket:\\[(\\d+)]$").find(link)?.groupValues?.get(1)?.toLongOrNull()

    /** A /proc/<pid>/cmdline (NUL-separated arguments) as one readable line. */
    fun commandLine(raw: ByteArray, max: Int = 240): String =
        String(raw, Charsets.UTF_8).split('\u0000').filter { it.isNotEmpty() }.joinToString(" ").take(max)

    /** Who listens on [port]. Reads /proc: call off the main thread. */
    fun find(port: Int): PortHolder {
        val tables = listOf("/proc/net/tcp", "/proc/net/tcp6").mapNotNull { runCatching { File(it).readText() }.getOrNull() }
        val sockets = listeners(tables, port)
        if (sockets.isEmpty()) return PortHolder.Unknown
        val myUid = android.os.Process.myUid()
        val inodes = sockets.filter { it.uid == myUid && it.inode != 0L }.map { it.inode }.toSet()
        if (inodes.isEmpty()) return PortHolder.OtherApp
        val me = android.os.Process.myPid()
        for (dir in File("/proc").listFiles().orEmpty()) {
            val pid = dir.name.toIntOrNull() ?: continue
            val fds = runCatching { File(dir, "fd").listFiles() }.getOrNull() ?: continue
            val owns = fds.any { fd -> runCatching { socketInode(Os.readlink(fd.path)) }.getOrNull() in inodes }
            if (!owns) continue
            if (pid == me) return PortHolder.ThisApp
            val start = runCatching { ProcessTree.parseStartTime(File(dir, "stat").readText()) }.getOrNull() ?: continue
            val command = runCatching { commandLine(File(dir, "cmdline").readBytes()) }.getOrNull().orEmpty()
                .ifBlank { runCatching { File(dir, "comm").readText().trim() }.getOrDefault("pid $pid") }
            return PortHolder.Process(pid, command, start)
        }
        return PortHolder.Unknown
    }

    /**
     * Stops [holder] alone (not its children), only while it is still the same process of this
     * app's uid: a pid reused since [find] is never signalled. Blocking: call off the main thread.
     */
    fun stop(holder: PortHolder.Process): Boolean {
        val sameUid = runCatching { Os.stat("/proc/${holder.pid}").st_uid == Os.getuid() }.getOrDefault(false)
        return sameUid && ProcessTree.stopExact(holder.pid, holder.startTime)
    }
}
