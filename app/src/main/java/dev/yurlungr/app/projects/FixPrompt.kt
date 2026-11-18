package dev.yurlungr.app.projects

import java.io.File

/** Something that failed in a project: what ran, and what it printed. */
data class FailureReport(
    /** What it was, in a few words: "Dependency setup", "Web app run", "Checks". */
    val what: String,
    /** The command that ran, when known. */
    val command: String?,
    /** The project's stacks as people know them ("Node.js (pnpm)", "Next.js"). */
    val stacks: List<String>,
    val log: String,
    /** The app's own one-line summary (a web run's status), if any. */
    val message: String? = null,
    /** More context for the agent, as is (what a dependency update changed). */
    val details: String? = null,
)

/** The important part of a long log: the error lines with some context, and the end. */
object LogExcerpt {
    private val ANSI = Regex("\u001B\\[[0-9;?]*[A-Za-z]|\u001B[()][A-Za-z0-9]|\u001B\\][^\u0007]*\u0007")
    private val ERROR = Regex(
        "\\berror\\b|\\berrors?:|ERR!|\\bfailed\\b|\\bfailure\\b|\\bfatal\\b|exception\\b|Traceback \\(most recent call last\\)|" +
            "\\bpanicked\\b|cannot find|can't find|could not|couldn't|not found|undefined variable|\\bE[A-Z]{3,}\\b:|✗",
        RegexOption.IGNORE_CASE,
    )
    private val NOT_ERROR = Regex("\\b0 errors?\\b|\\bno errors?\\b|\\b0 failed\\b|errors?: 0\\b", RegexOption.IGNORE_CASE)

    /** Terminal colors and carriage-return redraws removed: what a person would read. */
    // Split on \n only: lineSequence() would also split on the \r of a progress redraw.
    fun clean(log: String): String = log.split('\n').asSequence().map { ANSI.replace(it, "").substringAfterLast('\r').trimEnd() }.joinToString("\n")

    fun isError(line: String): Boolean = ERROR.containsMatchIn(line) && !NOT_ERROR.containsMatchIn(line)

    fun hasErrors(log: String): Boolean = clean(log).lineSequence().any(::isError)

    /**
     * Error lines with [context] lines around them, plus the last [tail] lines, in order, with "…"
     * where lines were left out; at most [cap] characters (the first error and the end win).
     */
    fun excerpt(log: String, cap: Int = 5000, context: Int = 3, tail: Int = 15): String {
        val lines = clean(log).lines().dropLastWhile { it.isBlank() }.map { if (it.length > 400) it.take(400) + "…" else it }
        if (lines.isEmpty()) return ""
        val keep = BooleanArray(lines.size)
        lines.forEachIndexed { i, l -> if (isError(l)) for (j in (i - context).coerceAtLeast(0)..(i + context).coerceAtMost(lines.lastIndex)) keep[j] = true }
        for (j in (lines.size - tail).coerceAtLeast(0)..lines.lastIndex) keep[j] = true
        // Runs of kept lines, as chunks (with where each starts).
        val chunks = ArrayList<Pair<Int, List<String>>>()
        var i = 0
        while (i < lines.size) {
            if (!keep[i]) { i++; continue }
            val start = i
            while (i < lines.size && keep[i]) i++
            chunks += start to lines.subList(start, i)
        }
        fun size(c: Pair<Int, List<String>>) = c.second.sumOf { it.length + 1 } + 2
        // Too long: drop chunks after the first, oldest first, keeping the last.
        while (chunks.size > 2 && chunks.sumOf(::size) > cap) chunks.removeAt(1)
        val text = chunks.joinToString("\n…\n") { it.second.joinToString("\n") }
        val out = (if (chunks.first().first > 0) "…\n" else "") + text
        return if (out.length <= cap) out else "…" + out.takeLast(cap - 1)
    }
}

/** The last dependency setup (project-setup.sh) of a project, when it failed. */
object SetupFailure {
    /** Exits that aren't a failure to fix: success, still running, no space, busy, or stopped by the user. */
    private val NOT_FAILURES = setOf(dev.yurlungr.app.runtime.SetupStatus.RUNNING, 0, 74, 75, 129, 130, 143)

    /** The project a setup log is about (its `project<TAB>dir` line, near the top). */
    fun projectOf(head: String): String? =
        head.lineSequence().take(20).firstOrNull { it.startsWith("project\t") }?.substringAfter('\t')?.trim()?.takeIf { it.isNotEmpty() }

    fun report(status: dev.yurlungr.app.runtime.SetupStatus?, project: String?, log: String, dir: File, stacks: List<String>): FailureReport? {
        if (status == null || status.exitCode in NOT_FAILURES || project == null) return null
        if (File(project).absoluteFile.normalize() != dir.absoluteFile.normalize()) return null
        return FailureReport(
            "Dependency setup",
            status.failed.joinToString("; ").ifBlank { null },
            stacks,
            log.lineSequence().filterNot { it.startsWith("project\t") }.joinToString("\n"),
            dev.yurlungr.app.runtime.SetupStatus.summary(status),
        )
    }
}

/** The first message of a chat that asks an agent to fix a failure on the phone. */
object FixPrompt {
    const val LOG_CAP = 5000

    /** A code fence longer than any run of backticks in [text], so the log can't close it. */
    internal fun fence(text: String): String {
        val longest = Regex("`+").findAll(text).maxOfOrNull { it.value.length } ?: 0
        return "`".repeat(maxOf(3, longest + 1))
    }

    fun build(report: FailureReport, fixes: List<KnownFix>): String = buildString {
        append("${report.what} failed in this project on my phone. It runs in an Android app with a Termux-based Linux environment ")
        append("(arm64, Android's libc, not glibc; Termux packages via `pkg`).\n\n")
        report.command?.takeIf { it.isNotBlank() }?.let { append("What ran: `").append(it.replace("`", "'").take(500)).append("`\n") }
        if (report.stacks.isNotEmpty()) append("Stack: ").append(report.stacks.joinToString(", ")).append('\n')
        report.message?.takeIf { it.isNotBlank() }?.let { append("Status: ").append(it.take(300)).append('\n') }
        report.details?.takeIf { it.isNotBlank() }?.let { append('\n').append(it.take(3000).trimEnd()).append('\n') }
        if (fixes.isNotEmpty()) {
            append("\nKnown phone issues this matches:\n")
            for (f in fixes) {
                append("- ").append(f.title).append(": ").append(f.agentHint)
                f.command?.let { append(" Suggested command: `").append(it).append("`.") }
                if (f.env.isNotEmpty()) append(" Env: ").append(f.env.entries.joinToString(" ") { "${it.key}=${it.value}" }).append('.')
                append('\n')
            }
        }
        val excerpt = LogExcerpt.excerpt(report.log, LOG_CAP)
        if (excerpt.isNotBlank()) {
            val f = fence(excerpt)
            append("\nThe important part of the output:\n").append(f).append('\n').append(excerpt).append('\n').append(f).append('\n')
        }
        append("\nMake this work on this phone. Prefer changes that keep working on a normal computer too, don't remove features to get there, ")
        append("and run the command again to confirm. Then explain what you changed and why.")
    }

    /** Asks the agent to check (and if needed adapt) a project that wants runtime versions the phone doesn't have. */
    fun versions(warnings: List<VersionWarning>): String = buildString {
        append("This project asks for runtime versions this phone doesn't have. It runs in an Android app with a Termux-based Linux ")
        append("environment, which usually ships one version per language, so switching versions isn't an option:\n")
        for (w in warnings) {
            append("- ${w.name}: the project wants ${w.requirement.wanted} (${w.requirement.source}); installed is ${w.installed}.\n")
        }
        append("\nCheck whether the project works with the installed versions (install, build, run its tests). If something breaks, ")
        append("adapt the project to them, keeping it working on the versions it supports where you can. Only change version files ")
        append("(.nvmrc, engines, requires-python…) if it really works with the installed version. Explain the risks and what you changed.")
    }
}
