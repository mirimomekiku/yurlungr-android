package dev.yurlungr.app.runtime

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlin.concurrent.thread

data class CommandResult(
    val exitCode: Int,
    val output: String,
    val timedOut: Boolean = false,
    /** Output went past the limit and was cut off. */
    val truncated: Boolean = false,
    /** Stderr, when it was kept separate from [output] (see [CommandRunner.run]'s `forParsing`). */
    val errorOutput: String = "",
) {
    val ok: Boolean get() = exitCode == 0 && !timedOut
    val firstLine: String get() = output.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
}

/** Runs non-interactive commands inside the runtime (no PTY). Interactive work goes through terminal sessions. */
object CommandRunner {
    private const val MAX_OUTPUT_CHARS = 64 * 1024
    /** Machine-readable output (e.g. `git status -z` of a big repo) is allowed to be much larger. */
    private const val MAX_PARSED_OUTPUT_CHARS = 16 * 1024 * 1024
    private const val MAX_ERROR_CHARS = 16 * 1024

    /**
     * With [forParsing], stdout is captured exactly as written (NULs, CRs, no added newlines) and
     * stderr is kept apart in [CommandResult.errorOutput], so a warning can't corrupt what's parsed.
     * Otherwise both streams are merged line by line, for showing to the user.
     */
    suspend fun run(
        paths: RuntimePaths,
        command: List<String>,
        cwd: File = paths.home,
        timeoutMs: Long = 60_000,
        extraEnv: Map<String, String> = emptyMap(),
        onLine: ((String) -> Unit)? = null,
        forParsing: Boolean = false,
    ): CommandResult = withContext(Dispatchers.IO) {
        val process = ProcessBuilder(command)
            .directory(cwd.takeIf { it.isDirectory } ?: paths.filesDir)
            .redirectErrorStream(!forParsing)
            .apply {
                environment().run {
                    clear()
                    putAll(RuntimeEnv.map(paths, cwd))
                    putAll(extraEnv)
                }
            }
            .start()
        process.outputStream.close()

        val output = Capture(if (forParsing) MAX_PARSED_OUTPUT_CHARS else MAX_OUTPUT_CHARS)
        val errors = Capture(MAX_ERROR_CHARS)
        // Plain threads rather than child coroutines: blocking reads aren't cancellable, and a
        // grandchild process holding a pipe open must not keep this call from returning.
        val reader = thread(isDaemon = true, name = "cmd-reader") {
            runCatching {
                if (forParsing) {
                    output.readRaw(process.inputStream.bufferedReader())
                } else {
                    process.inputStream.bufferedReader().forEachLine { line ->
                        output.appendLine(line)
                        onLine?.invoke(line)
                    }
                }
            }
        }
        val errorReader = if (forParsing) {
            thread(isDaemon = true, name = "cmd-stderr") { runCatching { errors.readRaw(process.errorStream.bufferedReader()) } }
        } else null

        val exited = withTimeoutOrNull(timeoutMs) { runInterruptible { process.waitFor() } } != null
        // On Android, Process.destroy() sends SIGKILL (waitFor(timeout)/destroyForcibly need API 26).
        if (!exited) process.destroy()
        reader.join(2_000)
        errorReader?.join(500)
        CommandResult(
            exitCode = if (exited) process.exitValue() else -1,
            output = output.text(),
            timedOut = !exited,
            truncated = output.truncated,
            errorOutput = errors.text(),
        )
    }

    /** Output collected up to a limit; the rest is drained (so the process doesn't block) and dropped. */
    private class Capture(private val limit: Int) {
        private val text = StringBuilder()
        @Volatile var truncated = false
            private set

        fun appendLine(line: String) = synchronized(text) {
            if (text.length + line.length < limit) text.appendLine(line) else truncated = true
        }

        fun readRaw(reader: java.io.Reader) {
            val buf = CharArray(8 * 1024)
            while (true) {
                val n = reader.read(buf)
                if (n < 0) break
                synchronized(text) {
                    val room = limit - text.length
                    if (n > room) truncated = true
                    if (room > 0) text.appendRange(buf, 0, minOf(n, room))
                }
            }
        }

        fun text(): String = synchronized(text) { text.toString() }
    }

    suspend fun shell(paths: RuntimePaths, script: String, timeoutMs: Long = 60_000): CommandResult =
        run(paths, listOf(paths.bash.path, "-c", script), timeoutMs = timeoutMs)
}
