package dev.yurlungr.app.web

import androidx.core.content.edit
import android.content.Context
import kotlinx.serialization.json.*
import java.io.File

internal data class WebRunConfig(
    val project: WebProject,
    val manager: WebPackageManager,
    val script: String,
    val port: Int,
    val install: Boolean,
    val customCommand: String = "",
    val openBrowser: Boolean = true,
) {
    fun validate() {
        require(project.supported || customCommand.isNotBlank()) { "Enter the command that starts this app's development server." }
        require(port in 1024..65535) { "Choose a port between 1024 and 65535." }
        require(project.directory.isDirectory) { "App folder no longer exists." }
        if (project.kind == WebKind.Vite && customCommand.isBlank()) require(script in project.scripts) { "Choose an available Vite development script." }
    }

    fun toJson(id: String, node: File, bash: File) = buildJsonObject {
        put("id", id); put("cwd", project.directory.canonicalPath); put("installCwd", project.installDirectory.canonicalPath)
        put("kind", project.kind.name); put("manager", manager.executable); put("script", script); put("port", port)
        put("install", install && project.kind != WebKind.Static); put("command", customCommand)
        // The framework's own start command, used when there's no custom one.
        project.launch?.let { put("launch", it) }
        put("node", node.path); put("bash", bash.path)
    }
}

internal object WebRunPreferences {
    private fun key(root: File) = root.canonicalPath
    fun read(context: Context, root: File): JsonObject? = runCatching {
        context.getSharedPreferences("web_runs", Context.MODE_PRIVATE).getString(key(root), null)?.let { Json.parseToJsonElement(it) as? JsonObject }
    }.getOrNull()
    fun save(context: Context, root: File, config: WebRunConfig) {
        val json = config.toJson("", File(""), File("")).toMutableMap().apply { put("openBrowser", JsonPrimitive(config.openBrowser)) }
        context.getSharedPreferences("web_runs", Context.MODE_PRIVATE).edit { putString(key(root), JsonObject(json).toString()) }
    }
}
