package dev.yurlungr.app.runtime

import java.io.File

/**
 * How the last run of a setup script ended, as written by `setup-common.sh`: tab-separated
 * `key<TAB>value` lines (`exit`, `pid`, `log`, `finished`, and one `failed` per step). While the
 * run is going `exit` is -1; if it stays -1 after the process is gone, the run was killed outright.
 */
data class SetupStatus(
    val exitCode: Int,
    val log: String?,
    val finishedAt: Long,
    val failed: List<String>,
    val pid: Int = 0,
) {
    /** Written at the start of the run and never updated: still going, or killed without a trace. */
    val unfinished: Boolean get() = exitCode == RUNNING

    companion object {
        const val RUNNING = -1

        fun read(file: File): SetupStatus? = runCatching { if (file.isFile) parse(file.readText()) else null }.getOrNull()

        fun parse(text: String): SetupStatus? {
            var exit: Int? = null
            var log: String? = null
            var finished = 0L
            var pid = 0
            val failed = mutableListOf<String>()
            for (line in text.lines()) {
                val (key, value) = line.split('\t', limit = 2).takeIf { it.size == 2 } ?: continue
                when (key) {
                    "exit" -> exit = value.trim().toIntOrNull()
                    "pid" -> pid = value.trim().toIntOrNull() ?: 0
                    "log" -> log = value.trim().ifEmpty { null }
                    "finished" -> finished = (value.trim().toLongOrNull() ?: 0L) * 1000
                    "failed" -> value.trim().takeIf { it.isNotEmpty() }?.let(failed::add)
                }
            }
            return exit?.let { SetupStatus(it, log, finished, failed, pid) }
        }

        /** What went wrong, in one or two lines. [running]: whether an [unfinished] run's process still exists. */
        fun summary(s: SetupStatus, running: Boolean = false): String = when {
            s.exitCode == 0 -> "Completed"
            s.unfinished && running -> "Running now."
            s.unfinished -> "Didn't finish: it was stopped without a chance to clean up (the app or phone was closed, or Android killed it). Run it again to complete it."
            // Killed by signal 9 is the cause, whatever steps it took down with it.
            PhantomHint.forExit(s.exitCode) != null -> PhantomHint.MESSAGE + " Then run it again to complete it."
            s.failed.isNotEmpty() -> "Failed: " + s.failed.take(5).joinToString(", ") +
                (if (s.failed.size > 5) " and ${s.failed.size - 5} more" else "") + ". Rerun it after fixing the cause; see the log."
            s.exitCode == 129 -> "Stopped: its terminal was closed before it finished. Run it again to complete it."
            s.exitCode == 130 || s.exitCode == 143 -> "Stopped before it finished. Run it again to complete it."
            s.exitCode == 74 -> "Didn't run: not enough free storage."
            else -> "Stopped with exit ${s.exitCode}. See the log, then run it again."
        }

        /** Whether [pid] is a live process (setups run as this app's user, so /proc shows them). */
        fun isAlive(pid: Int): Boolean = pid > 0 && File("/proc/$pid").exists()

        private val ANSI = Regex("\u001B\\[[0-9;?]*[A-Za-z]|\u001B[()][A-Za-z0-9]")

        /**
         * The last [maxBytes] of a log, readable: colour codes stripped and progress redraws (\r)
         * reduced to their final state. Setup logs (npm especially) easily exceed what an editor opens.
         */
        fun readLogTail(file: File, maxBytes: Int = 64 * 1024): String {
            if (!file.isFile) return "No log yet: the setup hasn't written one."
            val bytes = java.io.RandomAccessFile(file, "r").use { f ->
                val start = (f.length() - maxBytes).coerceAtLeast(0)
                f.seek(start)
                ByteArray((f.length() - start).toInt()).also { f.readFully(it) }
            }
            val skipped = file.length() > maxBytes
            val text = String(bytes, Charsets.UTF_8).replace(ANSI, "")
                // split('\n'), not lines(): lines() also breaks at a lone \r, which is what's being collapsed.
                .split('\n').joinToString("\n") { it.substringAfterLast('\r') }
            // A cut-off first line is noise; say what was left out instead.
            return if (skipped) "… (earlier output omitted)\n" + text.substringAfter('\n') else text
        }
    }
}
