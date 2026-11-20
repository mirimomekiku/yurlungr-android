package dev.yurlungr.app.runtime

/**
 * Spots processes Android killed. Android 12+ kills an app's child processes beyond 32 (the
 * "phantom process killer") with SIGKILL, which shows up as a plain "exit 137" that looks like the
 * program crashed. Saying what happened points the user at the one-time fix in System checks.
 */
object PhantomHint {
    /** What System checks calls the fix, so the hint and the check read the same. */
    const val CHECK_TITLE = "Background process limit"

    const val MESSAGE = "Android killed this process (signal 9). See the background process limit fix in System checks."

    /** 128 + SIGKILL, as shells and `$?` report a process killed by signal 9. */
    const val EXIT_SIGKILL = 137

    /**
     * The hint for a process that ended with [code], or null when it wasn't killed by SIGKILL.
     * Accepts the shell's 137, and -9 (how some process APIs report "killed by signal 9").
     */
    fun forExit(code: Int): String? = if (code == EXIT_SIGKILL || code == -9) MESSAGE else null
}
