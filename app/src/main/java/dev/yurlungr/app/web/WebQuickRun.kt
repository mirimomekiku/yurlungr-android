package dev.yurlungr.app.web

import android.content.Context
import dev.yurlungr.app.runtime.RuntimePaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket

/**
 * One tap on Run: the last settings for this project (or sensible defaults), dependencies
 * installed if they're missing, a free port, and the browser opened once the server answers.
 */
internal object WebQuickRun {
    /** Started: null. Otherwise why the options sheet has to ask first (no Node.js, no command…). */
    suspend fun start(context: Context, root: File, projects: List<WebProject>): String? {
        val paths = RuntimePaths(context)
        val saved = WebRunPreferences.read(context, root)
        val savedCwd = saved?.get("cwd")?.jsonPrimitive?.contentOrNull
        val project = projects.firstOrNull { it.directory.canonicalPath == savedCwd }
            ?: projects.firstOrNull { it.supported } ?: return "Choose how to start this app."
        val stored = saved?.takeIf { it["cwd"]?.jsonPrimitive?.contentOrNull == project.directory.canonicalPath }
        val custom = stored?.get("command")?.jsonPrimitive?.contentOrNull.orEmpty()
        if (!project.supported && custom.isBlank()) return "Enter the command that starts this app."
        val manager = stored?.get("manager")?.jsonPrimitive?.contentOrNull
            ?.let { value -> WebPackageManager.entries.firstOrNull { it.executable == value } }
            ?: project.manager ?: return "Choose a package manager."
        val ready = withContext(Dispatchers.IO) {
            File(paths.bin, "node").canExecute() &&
                (project.kind == WebKind.Static || File(paths.bin, manager.executable).canExecute())
        }
        if (!ready) return "Install the tools this app needs."
        val script = stored?.get("script")?.jsonPrimitive?.contentOrNull?.takeIf { it in project.scripts } ?: project.scripts.firstOrNull().orEmpty()
        val preferred = stored?.get("port")?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: defaultPort(project.kind)
        val config = WebRunConfig(
            project, manager, script,
            port = withContext(Dispatchers.IO) { freePort(preferred) },
            install = !project.dependenciesPresent,
            customCommand = custom,
            openBrowser = stored?.get("openBrowser")?.jsonPrimitive?.booleanOrNull ?: true,
        )
        withContext(Dispatchers.IO) { runCatching { config.validate() } }.exceptionOrNull()?.let { return it.message ?: "Check the run settings." }
        withContext(Dispatchers.Main) { WebRunSessions.start(context, root, config) }
        return null
    }

    fun defaultPort(kind: WebKind) = if (kind == WebKind.Static) 8080 else 5173

    /** [preferred] if nothing listens there, else the next free port after it. */
    internal fun freePort(preferred: Int): Int = (preferred until (preferred + 50).coerceAtMost(65536)).firstOrNull { port ->
        runCatching { ServerSocket(port, 1, InetAddress.getByName("127.0.0.1")).close(); true }.getOrDefault(false)
    } ?: preferred
}
