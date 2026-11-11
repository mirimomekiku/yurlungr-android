package dev.yurlungr.app.web

import kotlinx.serialization.json.*
import java.io.File

/**
 * Vite: a `vite` dev script. Framework: started with a known command ([WebProject.launch]).
 * Static: served as files. Candidate: a web app we can't start without a custom command.
 */
internal enum class WebKind { Vite, Framework, Static, Candidate }
internal enum class WebPackageManager(val executable: String) { Npm("npm"), Pnpm("pnpm"), Yarn("yarn") }
internal data class WebProject(
    val directory: File,
    val installDirectory: File,
    val kind: WebKind,
    val framework: String,
    val scripts: List<String> = emptyList(),
    val manager: WebPackageManager? = WebPackageManager.Npm,
    val managerHint: String? = null,
    /** For [WebKind.Framework]: the dev-server command; `$PORT` is the port to listen on (127.0.0.1). */
    val launch: String? = null,
    /** Things that may not work on Android, shown before running. */
    val notes: List<String> = emptyList(),
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
            // Specific frameworks first: several of them run on Vite without saying so.
            val framework = when {
                "next" in dependencies -> "Next.js"
                "astro" in dependencies -> "Astro"
                "nuxt" in dependencies -> "Nuxt"
                "@sveltejs/kit" in dependencies -> "SvelteKit"
                "@angular/core" in dependencies -> "Angular"
                "react-scripts" in dependencies -> "Create React App"
                "expo" in dependencies -> "Expo (web)"
                "vite" in dependencies || scripts.isNotEmpty() -> "Vite"
                else -> null
            }
            val kind = when {
                // The project's own `vite` script, when it has one; otherwise Vite is run directly.
                (framework == "Vite" || framework == "SvelteKit") && scripts.isNotEmpty() -> WebKind.Vite
                framework != null && launchCommand(framework) != null -> WebKind.Framework
                framework != null -> WebKind.Candidate
                File(dir, "index.html").isFile -> WebKind.Static
                else -> return@mapNotNull null
            }
            val notes = buildList {
                if (framework == "Next.js") add("Next.js's compiler has no native Android build: it falls back to WebAssembly, so the first start is slow and some versions may not start at all.")
                if (framework == "Expo (web)" && "react-native-web" !in dependencies) add("Expo's web support needs react-native-web, react-dom and @expo/metro-runtime: run `npx expo install react-native-web react-dom @expo/metro-runtime` first.")
                if (framework == "Angular" && "@angular/cli" !in dependencies) add("The Angular CLI (@angular/cli) isn't a dependency: `ng` may be missing.")
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
            WebProject(dir, installDir, kind, framework ?: "Static site", scripts, manager, hint,
                launch = if (kind == WebKind.Framework) framework?.let(::launchCommand) else null, notes = notes)
        })
    }

    /**
     * How each framework's dev server is started on 127.0.0.1 and `$PORT`. The binaries come from
     * the project's node_modules/.bin (the runner puts it on PATH).
     */
    internal fun launchCommand(framework: String): String? = when (framework) {
        "Vite", "SvelteKit" -> "vite --host 127.0.0.1 --port \"\$PORT\" --strictPort"
        "Next.js" -> "next dev -H 127.0.0.1 -p \"\$PORT\""
        "Astro" -> "astro dev --host 127.0.0.1 --port \"\$PORT\""
        "Nuxt" -> "nuxt dev --host 127.0.0.1 --port \"\$PORT\""
        "Angular" -> "ng serve --host 127.0.0.1 --port \"\$PORT\""
        // Reads PORT, HOST and BROWSER from the environment.
        "Create React App" -> "react-scripts start"
        // CI=1: no interactive prompts.
        "Expo (web)" -> "CI=1 expo start --web --localhost --port \"\$PORT\""
        else -> null
    }

    internal fun isViteDevScript(command: String): Boolean =
        !Regex("[;&|`$<>\n\r]").containsMatchIn(command) &&
        Regex("^vite(?:\\s|$)").containsMatchIn(command.trim()) && !Regex("^vite\\s+(build|preview)(?:\\s|$)").containsMatchIn(command.trim())

    private fun manifest(dir: File): JsonObject? = runCatching {
        File(dir, "package.json").takeIf { it.isFile && it.length() <= 512 * 1024 }?.readText()
            ?.let { Json.parseToJsonElement(it) as? JsonObject }
    }.getOrNull()
}
