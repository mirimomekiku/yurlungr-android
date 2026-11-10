package dev.yurlungr.app.runtime

import java.io.File

/**
 * How the last run of a setup script ended, as written by `setup-common.sh` when it exits:
 * tab-separated `key<TAB>value` lines (`exit`, `log`, `finished`, and one `failed` per step).
 */
data class SetupStatus(val exitCode: Int, val log: String?, val finishedAt: Long, val failed: List<String>) {
    companion object {
        fun read(file: File): SetupStatus? = runCatching { if (file.isFile) parse(file.readText()) else null }.getOrNull()

        fun parse(text: String): SetupStatus? {
            var exit: Int? = null
            var log: String? = null
            var finished = 0L
            val failed = mutableListOf<String>()
            for (line in text.lines()) {
                val (key, value) = line.split('\t', limit = 2).takeIf { it.size == 2 } ?: continue
                when (key) {
                    "exit" -> exit = value.trim().toIntOrNull()
                    "log" -> log = value.trim().ifEmpty { null }
                    "finished" -> finished = (value.trim().toLongOrNull() ?: 0L) * 1000
                    "failed" -> value.trim().takeIf { it.isNotEmpty() }?.let(failed::add)
                }
            }
            return exit?.let { SetupStatus(it, log, finished, failed) }
        }

        /** What went wrong, in one or two lines. */
        fun summary(s: SetupStatus): String = when {
            s.exitCode == 0 -> "Completed"
            s.failed.isNotEmpty() -> "Failed: " + s.failed.take(5).joinToString(", ") +
                (if (s.failed.size > 5) " and ${s.failed.size - 5} more" else "") + ". Rerun it after fixing the cause; see the log."
            s.exitCode == 75 -> "Didn't run: another setup was already running."
            s.exitCode == 130 || s.exitCode == 143 -> "Stopped before it finished. Run it again to complete it."
            else -> "Stopped with exit ${s.exitCode}. See the log, then run it again."
        }
    }
}
