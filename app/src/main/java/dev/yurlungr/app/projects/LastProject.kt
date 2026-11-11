package dev.yurlungr.app.projects

import android.content.Context
import androidx.core.content.edit
import java.io.File

/** The project last opened, so "Projects" in the sidebar returns straight to its workspace. */
object LastProject {
    private const val PREFS = "projects"
    private const val KEY = "last"

    fun get(context: Context): File? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            ?.let(::File)?.takeIf { it.isDirectory }

    fun set(context: Context, dir: File) = setOnly(context, dir).also { dev.yurlungr.app.widgets.OpenIn.refresh(context) }

    /**
     * Whether [dir] may be opened from outside the app (widget, shortcut, or any app sending the
     * open intent): inside ~/projects, or the last project itself.
     */
    fun isOpenable(context: Context, dir: File): Boolean {
        val target = runCatching { dir.canonicalFile }.getOrNull() ?: return false
        val projects = runCatching { dev.yurlungr.app.runtime.RuntimePaths(context).projects.canonicalFile }.getOrNull() ?: return false
        return isInside(target, projects) || runCatching { get(context)?.canonicalFile }.getOrNull() == target
    }

    /** Where files shared from other apps may be saved: not the runtime ($PREFIX) or app internals. */
    fun acceptsSharedFiles(context: Context, dir: File): Boolean {
        val target = runCatching { dir.canonicalFile }.getOrNull() ?: return false
        val paths = dev.yurlungr.app.runtime.RuntimePaths(context)
        val prefix = runCatching { paths.prefix.canonicalFile }.getOrNull() ?: return false
        val home = runCatching { paths.home.canonicalFile }.getOrNull() ?: return false
        if (isInside(target, prefix) || target == home) return false
        // Hidden folders in home hold shell and agent configuration (.ssh, .config, .claude...).
        if (isInside(target, home) && target.relativeTo(home).invariantSeparatorsPath.split('/').any { it.startsWith(".") }) return false
        return true
    }

    internal fun isInside(target: File, root: File) = target.path == root.path || target.path.startsWith(root.path + File.separator)

    private fun setOnly(context: Context, dir: File) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putString(KEY, dir.path) }
}
