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

    private fun setOnly(context: Context, dir: File) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putString(KEY, dir.path) }
}
