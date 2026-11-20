package dev.yurlungr.app.projects

import android.content.Context
import androidx.core.content.edit
import java.io.File

/**
 * Whether the user trusts a project's own code to run without asking first. Untrusted projects don't:
 * - install and run after cloning (npm lifecycle scripts, the dev server);
 * - start language servers (rust-analyzer runs build.rs) or load the project's Prettier config and plugins;
 * - run deploy CLIs from the project's node_modules or with its .npmrc;
 * - put a committed .venv first on PATH;
 * - run git hooks on pull-request checkout.
 *
 * Projects are trusted unless marked untrusted, so folders made on this phone (templates, new projects)
 * and projects from before this existed keep working. Cloning someone else's repository marks it
 * untrusted until the user trusts it. A folder inside an untrusted project (apps/web in a monorepo) is
 * untrusted too.
 */
object ProjectTrust {
    private const val PREFS = "project-trust"
    private const val UNTRUSTED = "untrusted"

    private fun key(dir: File): String = runCatching { dir.canonicalPath }.getOrDefault(dir.absolutePath).trimEnd('/')

    private fun untrusted(context: Context): Set<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(UNTRUSTED, emptySet()).orEmpty()

    /** False when [dir], or a project folder containing it, was marked untrusted. */
    fun isTrusted(context: Context, dir: File): Boolean = untrustedRoot(context, dir) == null

    /** The untrusted project folder [dir] is in (itself or an ancestor), or null when it's trusted. */
    fun untrustedRoot(context: Context, dir: File): File? {
        val path = key(dir)
        return untrusted(context).firstOrNull { path == it || path.startsWith("$it/") }?.let(::File)
    }

    /** Marks [dir] (a project folder) untrusted: done when cloning a repository the user doesn't own. */
    fun markUntrusted(context: Context, dir: File) = update(context) { it + key(dir) }

    /** Trusts [dir]: clears the mark on it and on any project folder containing it. */
    fun trust(context: Context, dir: File) {
        val path = key(dir)
        update(context) { set -> set.filterNot { path == it || path.startsWith("$it/") }.toSet() }
    }

    /** Forgets [dir] (the project was deleted), so a new folder of the same name starts fresh. */
    fun forget(context: Context, dir: File) {
        val path = key(dir)
        update(context) { set -> set.filterNot { it == path || it.startsWith("$path/") }.toSet() }
    }

    @Synchronized
    private fun update(context: Context, change: (Set<String>) -> Set<String>) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val next = change(prefs.getStringSet(UNTRUSTED, emptySet()).orEmpty())
        // A copy: the set returned by getStringSet must not be modified or stored back.
        prefs.edit(commit = true) { putStringSet(UNTRUSTED, HashSet(next)) }
    }
}
