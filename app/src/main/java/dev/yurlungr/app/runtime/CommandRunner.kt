package dev.yurlungr.app.runtime

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlin.concurrent.thread

/** Why a command didn't succeed. */
enum class CommandFailure {
    /** The program doesn't exist (spawn error ENOENT, or the shell's exit 127). */
    NotFound,
    /** It exists but can't be executed (EACCES, a broken binary or library; the shell's exit 126). */
    NotExecutable,
    TimedOut,
    /** It ran and exited non-zero. */
    Exited,
}

data class CommandResult(
    val exitCode: Int,
    val output: String,
    val timedOut: Boolean = false,
    /** Output went past the limit and was cut off. */
    val truncated: Boolean = false,
    /** Stderr, when it was kept separate from [output] (see [CommandRunner.run]'s `forParsing`). */
    val errorOutput: String = "",
    /** Set when the process couldn't be started at all (the OS error, e.g. "error=2, No such file"). */
    val launchError: String? = null,
    /** The limit that [timedOut] refers to. */
    val timeoutMs: Long = 0,
) {
    val ok: Boolean get() = exitCode == 0 && !timedOut && launchError == null
    val firstLine: String get() = output.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()

    val failure: CommandFailure?
        get() = when {
            ok -> null
            timedOut -> CommandFailure.TimedOut
            exitCode == EXIT_NOT_FOUND -> CommandFailure.NotFound
            exitCode == EXIT_NOT_EXECUTABLE -> CommandFailure.NotExecutable
            else -> CommandFailure.Exited
        }

    /** Whether reinstalling the tool is the likely fix (it's missing or can't run). */
    val toolMissing: Boolean get() = failure == CommandFailure.NotFound || failure == CommandFailure.NotExecutable

    /**
     * One message for the user: what went wrong with [what] (e.g. "git push"), in plain words,
     * followed by the command's last lines of error output when there are any.
     */
    fun describe(what: String): String {
        val detail = errorOutput.ifBlank { output }.trim().lines().filter { it.isNotBlank() }.takeLast(4).joinToString("\n")
        val headline = when (failure) {
            null -> "$what: done"
            CommandFailure.TimedOut -> "$what timed out after ${timeoutMs / 1000} s"
            CommandFailure.NotFound -> "$what: the program isn't installed" + (launchError?.let { " ($it)" } ?: "")
            CommandFailure.NotExecutable -> "$what: the program can't run" + (launchError?.let { " ($it)" } ?: "")
            CommandFailure.Exited -> "$what failed (exit $exitCode)"
        }
        return if (detail.isEmpty() || launchError != null) headline else "$headline\n$detail"
    }

    companion object {
        const val EXIT_NOT_FOUND = 127
        const val EXIT_NOT_EXECUTABLE = 126

        /** A result for a program that couldn't be started; never throws to the caller. */
        fun launchFailed(e: java.io.IOException): CommandResult {
            val message = e.cause?.message ?: e.message ?: e.toString()
            // Java reports the errno as "error=N": 2 = ENOENT. Anything else (EACCES, ENOEXEC…) can't run.
            val code = if (Regex("""error=2\b""").containsMatchIn(e.message.orEmpty() + " " + message)) EXIT_NOT_FOUND else EXIT_NOT_EXECUTABLE
            return CommandResult(code, "", launchError = message.removePrefix("error=2, ").removePrefix("error=13, "))
        }
    }
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
        // A missing or unrunnable program is a result, not an exception: callers run in UI coroutines.
        val process = try {
            ProcessBuilder(command)
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
        } catch (e: java.io.IOException) {
            return@withContext CommandResult.launchFailed(e)
        }
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
            timeoutMs = timeoutMs,
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
