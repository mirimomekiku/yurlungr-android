package dev.yurlungr.app.web

import kotlinx.serialization.json.*
import java.io.File

internal enum class WebKind { Vite, Next, Astro, Nuxt, Angular, Cra, Parcel, Webpack, Expo, Script, Static }
internal enum class WebLaunchMode(val label: String) { Framework("Framework CLI"), Script("Project script"), Custom("Custom command") }
internal enum class WebProfile(val label: String) { Default("Default bundler"), Compatibility("Compatibility (Webpack)") }
internal enum class WebPackageManager(val executable: String) { Npm("npm"), Pnpm("pnpm"), Yarn("yarn"), Bun("bun") }
internal data class WebProject(
    val directory: File,
    val installDirectory: File,
    val kind: WebKind,
    val framework: String,
    val scripts: List<String> = emptyList(),
    val manager: WebPackageManager? = WebPackageManager.Npm,
    val managerHint: String? = null,
    val scriptCommands: Map<String, String> = emptyMap(),
    val nodeRequirement: String? = null,
    val versionHint: String? = null,
    val notes: List<String> = emptyList(),
) {
    val hasAdapter get() = kind !in setOf(WebKind.Script, WebKind.Static)
    val defaultMode get() = when {
        kind in setOf(WebKind.Next, WebKind.Expo) || (kind == WebKind.Vite && scriptCommands.values.none(ProjectDetector::isViteDevScript)) -> WebLaunchMode.Framework
        scripts.isNotEmpty() -> WebLaunchMode.Script
        hasAdapter -> WebLaunchMode.Framework
        else -> WebLaunchMode.Custom
    }
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
            val scriptCommands = (pkg?.get("scripts") as? JsonObject).orEmpty().mapNotNull { (name, value) ->
                (value as? JsonPrimitive)?.contentOrNull?.let { name to it }
            }.toMap()
            val dependencies = listOf("dependencies", "devDependencies").flatMap { (pkg?.get(it) as? JsonObject).orEmpty().keys }.toSet()
            // Frameworks built on Vite take precedence over their underlying bundler.
            val definition = when {
                "next" in dependencies -> Triple(WebKind.Next,"Next.js","next")
                "astro" in dependencies -> Triple(WebKind.Astro,"Astro","astro")
                "nuxt" in dependencies -> Triple(WebKind.Nuxt,"Nuxt","nuxt")
                "@angular/core" in dependencies || "@angular/cli" in dependencies -> Triple(WebKind.Angular,"Angular","@angular/cli")
                "react-scripts" in dependencies -> Triple(WebKind.Cra,"Create React App","react-scripts")
                "expo" in dependencies -> Triple(WebKind.Expo,"Expo (web)","expo")
                "@sveltejs/kit" in dependencies || "vite" in dependencies || scriptCommands.values.any(::isViteDevScript) -> Triple(WebKind.Vite,if ("@sveltejs/kit" in dependencies) "SvelteKit" else "Vite","vite")
                "parcel" in dependencies -> Triple(WebKind.Parcel,"Parcel","parcel")
                "webpack-dev-server" in dependencies -> Triple(WebKind.Webpack,"Webpack Dev Server","webpack-cli")
                else -> null
            }
            val scripts = scriptCommands.filter { (name, command) ->
                name in setOf("dev","start","serve","web") || Regex("^(dev|start|serve):").containsMatchIn(name) ||
                    isViteDevScript(command)
            }.keys.sortedWith(compareBy({ if (it == "dev") 0 else if (it == "start") 1 else 2 }, { it }))
            val kind = definition?.first ?: when {
                scripts.isNotEmpty() -> WebKind.Script
                File(dir,"index.html").isFile -> WebKind.Static
                else -> return@mapNotNull null
            }
            val framework = definition?.second ?: if (kind == WebKind.Static) "Static site" else "Node / custom server"
            val installDir = if (dir != canonical && rootPackage?.containsKey("workspaces") == true) canonical else dir
            val hint = (pkg?.get("packageManager") as? JsonPrimitive)?.contentOrNull
                ?: (rootPackage?.get("packageManager") as? JsonPrimitive)?.contentOrNull
            val lockManagers = WebPackageManager.entries.filter { manager -> when (manager) {
                WebPackageManager.Npm -> File(installDir, "package-lock.json").isFile || File(installDir, "npm-shrinkwrap.json").isFile
                WebPackageManager.Pnpm -> File(installDir, "pnpm-lock.yaml").isFile
                WebPackageManager.Yarn -> File(installDir, "yarn.lock").isFile
                WebPackageManager.Bun -> File(installDir, "bun.lock").isFile || File(installDir, "bun.lockb").isFile
            } }
            val manager = if (hint != null) WebPackageManager.entries.firstOrNull { it.executable == hint.substringBefore('@') }
                else if (lockManagers.size == 1) lockManagers.single() else if (lockManagers.isEmpty()) WebPackageManager.Npm else null
            val packageKey = definition?.third
            val version = packageKey?.let { key ->
                val installed = runCatching { Json.parseToJsonElement(File(dir,"node_modules/$key/package.json").readText()).jsonObject }.getOrNull()
                installed?.get("version")?.jsonPrimitive?.contentOrNull
                    ?: listOf("dependencies","devDependencies").firstNotNullOfOrNull { section ->
                        (pkg?.get(section) as? JsonObject)?.get(key)?.jsonPrimitive?.contentOrNull
                    }
            }
            WebProject(dir, installDir, kind, framework, scripts, manager, hint, scriptCommands,
                ((pkg?.get("engines") as? JsonObject)?.get("node") as? JsonPrimitive)?.contentOrNull, version, notes = buildList {
                    if (kind == WebKind.Next) add("Next.js uses Webpack compatibility by default on Android. The first WASM compilation can take longer.")
                    if (kind == WebKind.Expo && "react-native-web" !in dependencies) add("Expo web needs react-native-web, react-dom and @expo/metro-runtime. Install them with this project's Expo CLI first.")
                    if (kind == WebKind.Angular && "@angular/cli" !in dependencies) add("Install this project's Angular CLI (@angular/cli) before using Framework CLI.")
                })
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
