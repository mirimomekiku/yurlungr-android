package dev.yurlungr.app.web

import android.content.Context
import androidx.core.content.edit
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * Dev servers the web detector can't find by itself (Python, Go, Rust, PHP, Flutter projects made
 * from a template): their run command is saved in the app's own run settings, never read from
 * project files, and the project is marked here so Run offers it.
 */
internal object CustomServers {
    private const val PREFS = "custom_servers"

    /** Saves [command] as the project's run settings and makes Run offer it as [framework]. */
    fun register(context: Context, root: File, framework: String, command: String, port: Int, startupTimeoutSeconds: Int) {
        val project = serverProject(root.canonicalFile, framework)
        val config = WebRunConfig(project, WebPackageManager.Npm, "", port, install = false, customCommand = command,
            mode = WebLaunchMode.Custom, startupTimeoutSeconds = startupTimeoutSeconds.coerceIn(30, 900))
        config.validate()
        WebRunPreferences.save(context, root, config)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putString(root.canonicalPath, framework) }
    }

    /** [detected] plus the project's registered server, if any. Reads preferences and files: call off the main thread. */
    fun withSaved(context: Context, root: File, detected: ProjectCapabilities): ProjectCapabilities {
        val framework = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(root.canonicalPath, null) ?: return detected
        return merge(root, detected, WebRunPreferences.read(context, root), framework)
    }

    internal fun serverProject(dir: File, framework: String) =
        WebProject(dir, dir, WebKind.Script, framework, manager = WebPackageManager.Npm)

    /**
     * Adds the registered server when the saved settings still run a custom command in a folder of
     * [root] that detection didn't already return (then its own entry, with the saved command, wins).
     */
    internal fun merge(root: File, detected: ProjectCapabilities, saved: JsonObject?, framework: String): ProjectCapabilities {
        saved ?: return detected
        val mode = saved["mode"]?.jsonPrimitive?.contentOrNull
        val command = saved["command"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val cwd = saved["cwd"]?.jsonPrimitive?.contentOrNull ?: return detected
        if (mode != WebLaunchMode.Custom.name || command.isBlank()) return detected
        val base = root.canonicalFile
        val dir = File(cwd).canonicalFile
        if (dir != base && !dir.path.startsWith(base.path + File.separator)) return detected
        if (!dir.isDirectory || detected.web.any { it.directory.canonicalPath == dir.path }) return detected
        return detected.copy(web = detected.web + serverProject(dir, framework))
    }
}
