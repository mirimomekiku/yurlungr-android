package dev.yurlungr.app.web

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
    val mode: WebLaunchMode = if (customCommand.isNotBlank()) WebLaunchMode.Custom else project.defaultMode,
    val profile: WebProfile = if (project.kind == WebKind.Next) WebProfile.Compatibility else WebProfile.Default,
    val healthPath: String = "/",
    val startupTimeoutSeconds: Int = 180,
    val repairNextCompiler: Boolean = false,
) {
    fun validate() {
        require(port in 1024..65535) { "Choose a port between 1024 and 65535." }
        require(project.directory.isDirectory) { "App folder no longer exists." }
        if (project.kind != WebKind.Static) when (mode) {
            WebLaunchMode.Script -> require(script in project.scripts) { "Choose an available project script." }
            WebLaunchMode.Custom -> require(customCommand.isNotBlank()) { "Enter a custom command." }
            WebLaunchMode.Framework -> require(project.hasAdapter) { "Choose Project script or Custom command for this project." }
        }
        require(healthPath.startsWith('/') && !healthPath.startsWith("//") && '\n' !in healthPath && '\r' !in healthPath) { "Readiness path must be a local path starting with /." }
        require(startupTimeoutSeconds in 30..900) { "Startup timeout must be between 30 and 900 seconds." }
    }

    fun toJson(id: String, node: File, bash: File) = buildJsonObject {
        put("id", id); put("cwd", project.directory.canonicalPath); put("installCwd", project.installDirectory.canonicalPath)
        put("kind", project.kind.name); put("manager", manager.executable); put("script", script); put("port", port)
        put("install", install && project.kind != WebKind.Static); put("command", if (mode == WebLaunchMode.Custom) customCommand else "")
        put("mode", mode.name); put("profile", profile.name); put("healthPath", healthPath)
        put("startupTimeoutSeconds", startupTimeoutSeconds); put("repairNextCompiler", repairNextCompiler)
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
        context.getSharedPreferences("web_runs", Context.MODE_PRIVATE).edit().putString(key(root), JsonObject(json).toString()).apply()
    }
}
