package dev.yurlungr.app.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.yurlungr.app.acp.ProcessGroup
import dev.yurlungr.app.runtime.ProcessTree
import kotlinx.coroutines.CompletableDeferred
import kotlin.concurrent.thread

/**
 * A command's output kept within [limit] bytes. Past the limit the oldest bytes go, as ACP's
 * `terminal/output` asks ("truncated from the beginning"), and always at a character boundary so
 * the text stays valid UTF-8. Thread-safe: a reader thread appends while requests read.
 */
class TerminalOutput(val limit: Int) {
    init { require(limit > 0) }

    // Grows up to twice the limit, then compacts to the limit: amortized O(1) per byte appended.
    private var buf = ByteArray(minOf(limit, 64 * 1024))
    private var size = 0

    /** Bytes were dropped from the start. */
    @Volatile var truncated = false
        private set

    @Synchronized
    fun append(bytes: ByteArray, off: Int = 0, len: Int = bytes.size) {
        if (len <= 0) return
        if (len >= limit) {
            // This chunk alone fills the limit: keep its tail.
            System.arraycopy(bytes, off + len - limit, ensure(limit), 0, limit)
            size = limit
            truncated = true
            return
        }
        if (size + len > 2 * limit) compact(limit - len)
        val b = ensure(size + len)
        System.arraycopy(bytes, off, b, size, len)
        size += len
    }

    private fun ensure(capacity: Int): ByteArray {
        if (buf.size < capacity) buf = buf.copyOf(maxOf(capacity, minOf(buf.size * 2, 2 * limit)))
        return buf
    }

    /** Keeps the last [keep] bytes. */
    private fun compact(keep: Int) {
        val drop = size - keep
        if (drop <= 0) return
        System.arraycopy(buf, drop, buf, 0, keep)
        size = keep
        truncated = true
    }

    /** The output as text: at most [limit] bytes, starting at a character, ending before a split one. */
    @Synchronized
    fun text(): String {
        var start = maxOf(0, size - limit)
        if (start > 0) truncated = true
        // Not in the middle of a character: skip continuation bytes (10xxxxxx).
        while (start < size && (buf[start].toInt() and 0xC0) == 0x80) start++
        val end = completeEnd(buf, start, size)
        return String(buf, start, end - start, Charsets.UTF_8)
    }

    companion object {
        /**
         * Where the text in [b] from [start] to [end] stops being complete: before a multi-byte
         * character whose last bytes haven't arrived yet (they come with the next read).
         */
        internal fun completeEnd(b: ByteArray, start: Int, end: Int): Int {
            var i = end - 1
            var back = 0
            while (i >= start && back < 4) {
                val v = b[i].toInt() and 0xFF
                if (v and 0xC0 != 0x80) {
                    val need = when {
                        v and 0x80 == 0 -> 1
                        v and 0xE0 == 0xC0 -> 2
                        v and 0xF0 == 0xE0 -> 3
                        v and 0xF8 == 0xF0 -> 4
                        else -> 1 // not UTF-8: leave it to the decoder
                    }
                    return if (end - i >= need) end else i
                }
                i--
                back++
            }
            return end
        }
    }
}

/** How a terminal's command ended, as ACP reports it: an exit code, or the signal that stopped it. */
data class TerminalExit(val exitCode: Int?, val signal: String?) {
    val label: String get() = when {
        signal != null -> "Stopped ($signal)"
        exitCode == 0 -> "Exit 0"
        else -> "Exit $exitCode"
    }

    companion object {
        private val SIGNALS = mapOf(1 to "SIGHUP", 2 to "SIGINT", 6 to "SIGABRT", 9 to "SIGKILL", 13 to "SIGPIPE", 15 to "SIGTERM")

        /**
         * From a Java exit value: a process killed by a signal exits with 128 + its number. Only read
         * as a signal when we stopped it (a shell may exit 130 on its own), or for SIGKILL.
         */
        fun of(value: Int, killed: Boolean): TerminalExit {
            val signal = SIGNALS[value - 128]
            return if (signal != null && (killed || value == 137)) TerminalExit(null, signal) else TerminalExit(value, null)
        }
    }
}

/** What the chat screen shows for a terminal an agent embedded in a tool call. */
interface TerminalView {
    /** The last of the output (live while it runs). */
    val text: String
    val running: Boolean
    val exit: TerminalExit?
    fun kill()
}

/**
 * One ACP terminal: a command the agent asked the client to run (`terminal/create`), in its own
 * process group when `setsid` is available so [kill] stops everything it started.
 *
 * [post] runs UI-state updates (the main thread in the app; directly in tests).
 */
class AcpTerminal(
    val id: String,
    val command: String,
    private val process: Process,
    limit: Int,
    private val ownGroup: Boolean,
    private val post: (Runnable) -> Unit,
    /** Called once (through [post]) when the command has exited. */
    private val onExit: (AcpTerminal) -> Unit = {},
) : TerminalView {
    val output = TerminalOutput(limit)
    private val pid = ProcessTree.pidOf(process)
    @Volatile private var killed = false
    /** Completes with the exit status once the command has ended and its output is read. */
    val exited = CompletableDeferred<TerminalExit>()
    /** The same status, readable at once from any thread (null while it runs). */
    @Volatile var exitStatus: TerminalExit? = null
        private set

    override var text by mutableStateOf("")
        private set
    override var running by mutableStateOf(true)
        private set
    override var exit by mutableStateOf<TerminalExit?>(null)
        private set

    @Volatile private var lastShown = 0L
    @Volatile private var showPending = false

    init {
        runCatching { process.outputStream.close() } // no input: commands that wait for it see EOF
        thread(isDaemon = true, name = "acp-terminal") {
            val buffer = ByteArray(8 * 1024)
            runCatching {
                process.inputStream.use { input ->
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.append(buffer, 0, n)
                        show()
                    }
                }
            }
            val status = TerminalExit.of(runCatching { process.waitFor() }.getOrDefault(-1), killed)
            exitStatus = status
            exited.complete(status)
            post(Runnable {
                text = display()
                exit = status
                running = false
                onExit(this)
            })
        }
    }

    /** At most ~5 updates a second reach the UI, however chatty the command. */
    private fun show() {
        val now = System.currentTimeMillis()
        if (showPending || now - lastShown < SHOW_EVERY_MS) {
            if (!showPending) {
                showPending = true
                thread(isDaemon = true, name = "acp-terminal-show") {
                    Thread.sleep(SHOW_EVERY_MS)
                    showPending = false
                    lastShown = System.currentTimeMillis()
                    val t = display()
                    post(Runnable { text = t })
                }
            }
            return
        }
        lastShown = now
        val t = display()
        post(Runnable { text = t })
    }

    /** The output's tail, for the screen and for saving with the chat. */
    fun display(maxChars: Int = DISPLAY_CHARS): String {
        val all = output.text()
        return if (all.length <= maxChars) all else "…\n" + all.takeLast(maxChars)
    }

    /** Stops the command and everything it started (its process tree, and its group when it leads one). */
    override fun kill() {
        if (exited.isCompleted) return
        killed = true
        thread(isDaemon = true, name = "acp-terminal-kill") {
            if (pid > 0) {
                val group = ownGroup && ProcessGroup.leads(pid)
                runCatching { ProcessTree.terminate(pid, graceMs = 1_000, alsoGroup = group) }
            }
            runCatching { process.destroy() }
        }
    }

    companion object {
        private const val SHOW_EVERY_MS = 200L
        const val DISPLAY_CHARS = 32 * 1024
    }
}

/** Limits for agents' terminals: robust when an agent never calls `terminal/release`. */
object TerminalLimits {
    /** Output kept when the agent doesn't name a limit. */
    const val DEFAULT_BYTES = 1024 * 1024
    /** Never more than this per terminal, whatever the agent asks for. */
    const val MAX_BYTES = 4 * 1024 * 1024
    /** Commands running at once per chat. */
    const val MAX_RUNNING = 16
    /** Finished terminals kept for the screen (their output is also saved with the tool call). */
    const val MAX_KEPT = 32

    fun outputLimit(requested: Long?): Int =
        (requested ?: DEFAULT_BYTES.toLong()).coerceIn(1, MAX_BYTES.toLong()).toInt()

    /**
     * The `bash -c` script and arguments for a terminal command. Without [args] the command is a
     * shell line (what agents send for "run this"); with them it's a program and its arguments,
     * passed through untouched. [profile] (the runtime's profile snippet) runs first either way.
     */
    fun shellArgs(profile: String, command: String, args: List<String>): List<String> =
        if (args.isEmpty()) listOf("-c", "$profile; $command")
        else listOf("-c", "$profile; exec \"\$0\" \"\$@\"", command) + args
}
