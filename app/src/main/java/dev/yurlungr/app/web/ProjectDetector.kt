package dev.yurlungr.app.web

import kotlinx.serialization.json.*
import java.io.File

internal enum class WebKind { Vite, Static, Candidate }
internal enum class WebPackageManager(val executable: String) { Npm("npm"), Pnpm("pnpm"), Yarn("yarn") }
internal data class WebProject(
    val directory: File,
    val installDirectory: File,
    val kind: WebKind,
    val framework: String,
    val scripts: List<String> = emptyList(),
    val manager: WebPackageManager? = WebPackageManager.Npm,
    val managerHint: String? = null,
) {
    val supported get() = kind != WebKind.Candidate
    val dependenciesPresent get() = File(installDirectory, "node_modules").isDirectory || File(installDirectory, ".pnp.cjs").isFile
}
internal data class ProjectCapabilities(val android: Boolean, val web: List<WebProject>)

/** Bounded, read-only inspection: never evaluate configuration or execute project scripts. */
internal object ProjectDetector {
    fun detect(root: File): ProjectCapabilities {
        val canonical = root.canonicalFile
        val rootPackage = manifest(canonical)
        val dirs = linkedSetOf(canonical)
        val containers = setOf("apps", "packages", "web", "frontend", "client", "site", "website", "www")
        fun include(dir: File) {
            if (dirs.size < 64 && dir.isDirectory && dir.canonicalPath.startsWith(canonical.path + File.separator)) dirs += dir.canonicalFile
        }
        for (name in containers) {
            val child = File(canonical, name)
            include(child)
            if (child.isDirectory && child.canonicalPath.startsWith(canonical.path + File.separator)) {
                child.listFiles()?.filter { it.isDirectory && !it.name.startsWith('.') && it.name !in setOf("node_modules", "build", "dist") }
                    ?.sortedBy { it.name }?.take(32)?.forEach(::include)
            }
        }
        return ProjectCapabilities(File(root, "gradlew").isFile, dirs.mapNotNull { dir ->
            val pkg = if (dir == canonical) rootPackage else manifest(dir)
            val scripts = (pkg?.get("scripts") as? JsonObject).orEmpty().mapNotNull { (name, value) ->
                val command = (value as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                name.takeIf { isViteDevScript(command) }
            }.sortedWith(compareBy({ if (it == "dev") 0 else if (it == "start") 1 else 2 }, { it }))
            val dependencies = listOf("dependencies", "devDependencies").flatMap { (pkg?.get(it) as? JsonObject).orEmpty().keys }.toSet()
            val framework = when {
                "vite" in dependencies || scripts.isNotEmpty() -> "Vite"
                "next" in dependencies -> "Next.js"
                "astro" in dependencies -> "Astro"
                "nuxt" in dependencies -> "Nuxt"
                "react-scripts" in dependencies -> "Create React App"
                "@angular/core" in dependencies -> "Angular"
                else -> null
            }
            val kind = when {
                framework == "Vite" && scripts.isNotEmpty() -> WebKind.Vite
                framework != null -> WebKind.Candidate
                File(dir, "index.html").isFile -> WebKind.Static
                else -> return@mapNotNull null
            }
            val installDir = if (dir != canonical && rootPackage?.containsKey("workspaces") == true) canonical else dir
            val hint = (pkg?.get("packageManager") as? JsonPrimitive)?.contentOrNull
                ?: (rootPackage?.get("packageManager") as? JsonPrimitive)?.contentOrNull
            val lockManagers = WebPackageManager.entries.filter { manager -> when (manager) {
                WebPackageManager.Npm -> File(installDir, "package-lock.json").isFile || File(installDir, "npm-shrinkwrap.json").isFile
                WebPackageManager.Pnpm -> File(installDir, "pnpm-lock.yaml").isFile
                WebPackageManager.Yarn -> File(installDir, "yarn.lock").isFile
            } }
            val manager = if (hint != null) WebPackageManager.entries.firstOrNull { it.executable == hint.substringBefore('@') }
                else if (lockManagers.size == 1) lockManagers.single() else if (lockManagers.isEmpty()) WebPackageManager.Npm else null
            WebProject(dir, installDir, kind, framework ?: "Static site", scripts, manager, hint)
        })
    }

    internal fun isViteDevScript(command: String): Boolean =
        !Regex("[;&|`$<>\n\r]").containsMatchIn(command) &&
        Regex("^vite(?:\\s|$)").containsMatchIn(command.trim()) && !Regex("^vite\\s+(build|preview)(?:\\s|$)").containsMatchIn(command.trim())

    private fun manifest(dir: File): JsonObject? = runCatching {
        File(dir, "package.json").takeIf { it.isFile && it.length() <= 512 * 1024 }?.readText()
            ?.let { Json.parseToJsonElement(it) as? JsonObject }
    }.getOrNull()
}
