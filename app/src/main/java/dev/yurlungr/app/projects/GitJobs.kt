package dev.yurlungr.app.projects

import android.content.Context
import androidx.compose.runtime.mutableStateMapOf
import androidx.core.content.edit
import dev.yurlungr.app.runtime.CommandResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/** A git write that finished, kept until the project screen shows it. */
internal data class GitJobResult(
    val label: String,
    val result: CommandResult,
    /** Say nothing when it worked (stage, discard…); failures are always shown. */
    val quiet: Boolean,
    /** A commit that worked: the screen clears its commit message box. */
    val committed: Boolean,
)

/**
 * Git writes (commit, stage, stash, merge…) run here rather than in a screen's scope. Leaving
 * the project mid-commit cancelled the screen's coroutine, which killed git and its hooks half
 * way (and left index.lock behind). Here they finish; the project screen shows what's running
 * per project and picks up the result when it's back.
 */
internal object GitJobs {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Project path → label of the write running there. */
    val running = mutableStateMapOf<String, String>()

    /** Project path → the last write's outcome, until [take]n. */
    val results = mutableStateMapOf<String, GitJobResult>()

    fun key(dir: File): String = dir.absolutePath

    /** Starts [block] for [dir] unless a write is already running there; false when it didn't start. */
    fun start(dir: File, label: String, quiet: Boolean = false, committing: Boolean = false, block: suspend () -> CommandResult): Boolean {
        val k = key(dir)
        if (running.containsKey(k)) return false
        running[k] = label
        results.remove(k)
        scope.launch {
            val r = try {
                block()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                CommandResult(exitCode = 1, output = e.message ?: e.toString())
            } finally {
                running.remove(k)
            }
            results[k] = GitJobResult(label, r, quiet, committed = committing && r.ok)
        }
        return true
    }

    /** Runs a multi-step routine (Get latest, a stash-and-switch) where leaving the screen can't cut it short. */
    fun launch(block: suspend CoroutineScope.() -> Unit): Job = scope.launch(block = block)

    fun take(dir: File): GitJobResult? = results.remove(key(dir))
}

/** A stash a routine made with the user's work (before Get latest, or a branch switch). */
data class TrackedStash(
    val sha: String,
    val reason: String,
    /** It was re-applied, with conflicts: the changes are in the files, the entry is only a backup now. */
    val applied: Boolean = false,
)

/**
 * The stash a routine made per project, remembered by its commit, so the Changes tab can say
 * "your changes are still stashed" until it's restored or dropped, and restore exactly that
 * entry (not whatever happens to be stash@{0} by then).
 */
object RoutineStashes {
    private const val PREFS = "routine-stash"

    private fun key(dir: File) = runCatching { dir.canonicalPath }.getOrDefault(dir.absolutePath)

    fun get(context: Context, dir: File): TrackedStash? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(key(dir), null)?.let(::decode)

    fun set(context: Context, dir: File, stash: TrackedStash) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putString(key(dir), encode(stash)) }

    fun clear(context: Context, dir: File) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { remove(key(dir)) }

    fun encode(s: TrackedStash): String = listOf(s.sha, if (s.applied) "1" else "0", s.reason).joinToString("\u001f")

    fun decode(text: String): TrackedStash? {
        val f = text.split('\u001f')
        if (f.size != 3 || f[0].length < 40 || f[0].any { it !in '0'..'9' && it !in 'a'..'f' }) return null
        return TrackedStash(f[0], f[2], applied = f[1] == "1")
    }
}
