package dev.yurlungr.app.runtime

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlin.concurrent.thread

data class CommandResult(val exitCode: Int, val output: String, val timedOut: Boolean = false) {
    val ok: Boolean get() = exitCode == 0 && !timedOut
    val firstLine: String get() = output.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
}

/** Runs non-interactive commands inside the runtime (no PTY). Interactive work goes through terminal sessions. */
object CommandRunner {
    private const val MAX_OUTPUT_CHARS = 64 * 1024

    suspend fun run(
        paths: RuntimePaths,
        command: List<String>,
        cwd: File = paths.home,
        timeoutMs: Long = 60_000,
        extraEnv: Map<String, String> = emptyMap(),
        onLine: ((String) -> Unit)? = null,
    ): CommandResult = withContext(Dispatchers.IO) {
        val process = ProcessBuilder(command)
            .directory(cwd.takeIf { it.isDirectory } ?: paths.filesDir)
            .redirectErrorStream(true)
            .apply {
                environment().run {
                    clear()
                    putAll(RuntimeEnv.map(paths, cwd))
                    putAll(extraEnv)
                }
            }
            .start()
        process.outputStream.close()

        val output = StringBuilder()
        // A plain thread rather than a child coroutine: blocking reads aren't cancellable, and a
        // grandchild process holding the pipe open must not keep this call from returning.
        val reader = thread(isDaemon = true, name = "cmd-reader") {
            runCatching {
                process.inputStream.bufferedReader().forEachLine { line ->
                    synchronized(output) { if (output.length < MAX_OUTPUT_CHARS) output.appendLine(line) }
                    onLine?.invoke(line)
                }
            }
        }

        val exited = withTimeoutOrNull(timeoutMs) { runInterruptible { process.waitFor() } } != null
        // On Android, Process.destroy() sends SIGKILL (waitFor(timeout)/destroyForcibly need API 26).
        if (!exited) process.destroy()
        reader.join(2_000)
        CommandResult(
            exitCode = if (exited) process.exitValue() else -1,
            output = synchronized(output) { output.toString() },
            timedOut = !exited,
        )
    }

    suspend fun shell(paths: RuntimePaths, script: String, timeoutMs: Long = 60_000): CommandResult =
        run(paths, listOf(paths.bash.path, "-c", script), timeoutMs = timeoutMs)
}
