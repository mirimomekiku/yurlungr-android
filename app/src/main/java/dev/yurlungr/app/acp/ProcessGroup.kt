package dev.yurlungr.app.acp

import java.io.File

/**
 * Process-group helpers for processes this app starts with `setsid` (agents and ACP terminals),
 * so stopping one also stops what it left behind.
 */
internal object ProcessGroup {
    /**
     * The process group id from a `/proc/<pid>/stat` line, or null. Fields after the command name
     * (which is in parentheses and may itself contain spaces or ')') are: state, ppid, pgrp, …
     */
    fun parsePgrp(stat: String): Int? {
        val close = stat.lastIndexOf(')').takeIf { it >= 0 } ?: return null
        return stat.substring(close + 1).trim().split(' ').getOrNull(2)?.toIntOrNull()
    }

    /**
     * Whether [pid] leads its own process group. When its stat can't be read (it already exited)
     * the group may still hold its children, and a group's id is its leader's pid, so -pid can
     * only name that group (or none): true then.
     */
    fun leads(pid: Int): Boolean {
        if (pid <= 1) return false
        val stat = runCatching { File("/proc/$pid/stat").readText() }.getOrNull() ?: return true
        return parsePgrp(stat) == pid
    }

    /**
     * `setsid` from the runtime, if it's there: a command started through it leads a new session
     * and process group. A ProcessBuilder child isn't a group leader, so setsid execs in place and
     * the pid stays the same.
     */
    fun setsid(bin: File): File? = File(bin, "setsid").takeIf { it.canExecute() }

    /** [command] prefixed with [setsid] when there is one. */
    fun command(setsid: File?, command: List<String>): List<String> =
        if (setsid != null) listOf(setsid.path) + command else command
}
