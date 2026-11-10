package dev.yurlungr.app.projects

import java.io.File

/**
 * A tech stack found in a project, and how to install its dependencies on this phone.
 * [packages] are Termux packages the steps need; [steps] run in [dir], in order, stopping at the
 * first failure. [unsupported] explains why it can't be set up here (then nothing runs).
 */
data class DetectedStack(
    val id: String,
    val name: String,
    /** Where it was found (the folder its manifest is in). */
    val dir: File,
    /** The file that gave it away, relative to the project, e.g. "app/package.json". */
    val evidence: String,
    /** Frameworks and tools spotted on top ("Expo", "Vite", "Jetpack Compose"…). */
    val tags: List<String> = emptyList(),
    val packages: List<String> = emptyList(),
    val steps: List<String> = emptyList(),
    /** Already set up (e.g. node_modules exists); null when that can't be told from files. */
    val installed: Boolean? = null,
    val notes: List<String> = emptyList(),
    val unsupported: String? = null,
) {
    val canInstall: Boolean get() = unsupported == null && steps.isNotEmpty()
}

/**
 * Finds the stacks of a project by their manifests (package.json, pubspec.yaml, build.gradle…) in
 * the root and in common app folders, without walking dependency or build folders.
 */
object StackDetector {
    /** Where app code usually lives in a repository (plus the root). */
    private val SUBFOLDERS = listOf(
        "app", "apps", "android", "mobile", "frontend", "backend", "client", "server", "web", "api", "site", "packages",
    )
    private val SKIP = setOf("node_modules", ".git", "build", "dist", ".gradle", "target", ".dart_tool", ".venv", "vendor", "Pods")

    fun detect(root: File): List<DetectedStack> {
        val dirs = buildList {
            add(root)
            for (name in SUBFOLDERS) {
                val d = File(root, name)
                if (!d.isDirectory) continue
                add(d)
                // apps/web, packages/ui… one level further, for monorepos.
                if (name == "apps" || name == "packages") {
                    d.listFiles()?.filter { it.isDirectory && it.name !in SKIP && !it.name.startsWith(".") }?.sortedBy { it.name }?.take(12)?.let(::addAll)
                }
            }
        }.distinctBy { it.canonicalPath }
        val found = dirs.flatMap { detectIn(root, it) }
        // Node workspaces install once, at the root: drop members of a root that has workspaces.
        val rootNode = found.firstOrNull { it.id == "node" && it.dir.canonicalPath == root.canonicalPath && "workspaces" in it.tags }
        // A Flutter app's android/ folder is part of the Flutter build (it needs the Flutter SDK), not its own.
        val flutterDirs = found.filter { it.id == "flutter" }.map { it.dir.canonicalPath + File.separator }
        return found.filterNot { s ->
            (rootNode != null && s.id == "node" && s !== rootNode) ||
                (s.id == "android" && flutterDirs.any { (s.dir.canonicalPath + File.separator).startsWith(it) && s.dir.canonicalPath + File.separator != it })
        }
    }

    private fun rel(root: File, f: File) = f.relativeTo(root).invariantSeparatorsPath

    private fun read(f: File): String = runCatching { if (f.isFile && f.length() < 2_000_000) f.readText() else "" }.getOrDefault("")

    private fun detectIn(root: File, dir: File): List<DetectedStack> = buildList {
        node(root, dir)?.let(::add)
        dart(root, dir)?.let(::add)
        gradle(root, dir)?.let(::add)
        python(root, dir)?.let(::add)
        simple(root, dir, "Cargo.toml", "rust", "Rust (Cargo)", listOf("rust"), listOf("cargo fetch"), File(dir, "target").isDirectory)?.let(::add)
        simple(root, dir, "go.mod", "go", "Go", listOf("golang"), listOf("go mod download"), null)?.let(::add)
        simple(root, dir, "Gemfile", "ruby", "Ruby (Bundler)", listOf("ruby"), listOf("bundle config set --local path vendor/bundle", "bundle install"),
            File(dir, "vendor/bundle").isDirectory)?.let(::add)
        simple(root, dir, "composer.json", "php", "PHP (Composer)", listOf("php", "composer"), listOf("composer install --no-interaction"),
            File(dir, "vendor").isDirectory)?.let(::add)
        simple(root, dir, "pom.xml", "maven", "Java (Maven)", listOf("openjdk-17", "maven"), listOf("mvn -q -B dependency:go-offline"), null)?.let(::add)
        simple(root, dir, "Package.swift", "swift", "Swift (SwiftPM)", listOf("swift"), listOf("swift package resolve"),
            File(dir, ".build").isDirectory)?.let(::add)
    }

    private fun simple(root: File, dir: File, manifest: String, id: String, name: String, packages: List<String>, steps: List<String>, installed: Boolean?): DetectedStack? {
        val f = File(dir, manifest)
        if (!f.isFile) return null
        return DetectedStack(id, name, dir, rel(root, f), packages = packages, steps = steps, installed = installed)
    }

    private val NODE_FRAMEWORKS = listOf(
        "expo" to "Expo", "react-native" to "React Native", "next" to "Next.js", "nuxt" to "Nuxt", "@sveltejs/kit" to "SvelteKit",
        "astro" to "Astro", "@angular/core" to "Angular", "vite" to "Vite", "electron" to "Electron", "@nestjs/core" to "NestJS",
        "express" to "Express", "react" to "React", "vue" to "Vue", "svelte" to "Svelte", "typescript" to "TypeScript",
    )

    private fun node(root: File, dir: File): DetectedStack? {
        val manifest = File(dir, "package.json")
        if (!manifest.isFile) return null
        val text = read(manifest)
        val deps = Regex("\"([@a-zA-Z0-9_./-]+)\"\\s*:").findAll(text).map { it.groupValues[1] }.toSet()
        val tags = NODE_FRAMEWORKS.filter { it.first in deps }.map { it.second }.toMutableList()
        val workspaces = text.contains("\"workspaces\"") || File(dir, "pnpm-workspace.yaml").isFile
        if (workspaces) tags += "workspaces"
        val declared = Regex("\"packageManager\"\\s*:\\s*\"([a-z]+)@").find(text)?.groupValues?.get(1)
        val manager = when {
            declared != null -> declared
            File(dir, "pnpm-lock.yaml").isFile -> "pnpm"
            File(dir, "yarn.lock").isFile -> "yarn"
            File(dir, "bun.lockb").isFile || File(dir, "bun.lock").isFile -> "bun"
            else -> "npm"
        }
        val notes = mutableListOf<String>()
        val (packages, steps) = when (manager) {
            "pnpm" -> listOf("nodejs-lts") to listOf("command -v pnpm >/dev/null || npm install -g pnpm", "pnpm install")
            "yarn" -> listOf("nodejs-lts", "yarn") to listOf("yarn install")
            "bun" -> {
                notes += "Bun isn't available on Android: installing with npm instead (the bun lockfile is ignored)."
                listOf("nodejs-lts") to listOf("npm install")
            }
            // npm ci needs a lockfile that matches package.json; install otherwise.
            else -> listOf("nodejs-lts") to listOf(if (File(dir, "package-lock.json").isFile) "npm ci || npm install" else "npm install")
        }
        if ("Expo" in tags) notes += "Start the dev server with `npx expo start` and open it in Expo Go. Native Android builds (expo run:android, EAS local builds) don't work on the phone."
        else if ("React Native" in tags) notes += "Metro (`npx react-native start`) runs here; native Android builds need the Android SDK setup and may still fail on the phone."
        if ("Electron" in tags) notes += "Electron apps can't run on Android; installing lets agents work on the code."
        if (deps.any { it in NATIVE_ADDONS }) notes += "Has native add-ons (${deps.filter { it in NATIVE_ADDONS }.joinToString()}): compiling them on the phone may fail. If so, the agent can usually swap in a pure-JS alternative."
        return DetectedStack(
            "node", "Node.js ($manager)", dir, rel(root, manifest), tags, packages, steps,
            installed = File(dir, "node_modules").isDirectory,
            notes = notes,
        )
    }

    private val NATIVE_ADDONS = setOf("sharp", "bcrypt", "canvas", "node-sass", "sqlite3", "better-sqlite3", "node-pty", "@swc/core", "esbuild", "lightningcss")

    private fun dart(root: File, dir: File): DetectedStack? {
        val manifest = File(dir, "pubspec.yaml")
        if (!manifest.isFile) return null
        val text = read(manifest)
        val flutter = Regex("(?m)^\\s+sdk:\\s*flutter\\b").containsMatchIn(text) || Regex("(?m)^flutter:").containsMatchIn(text)
        val installed = File(dir, ".dart_tool/package_config.json").isFile
        return if (flutter) {
            DetectedStack(
                "flutter", "Flutter", dir, rel(root, manifest), listOf("Dart"), installed = installed,
                unsupported = "The Flutter SDK isn't packaged for Android's Linux environment, so `flutter pub get` and builds can't run on the phone. " +
                    "Agents can still edit the code; build and run it on a computer.",
            )
        } else {
            DetectedStack("dart", "Dart", dir, rel(root, manifest), packages = listOf("dart"), steps = listOf("dart pub get"), installed = installed)
        }
    }

    private fun gradle(root: File, dir: File): DetectedStack? {
        val build = listOf("build.gradle.kts", "build.gradle", "settings.gradle.kts", "settings.gradle").map { File(dir, it) }.firstOrNull { it.isFile } ?: return null
        // Only the Gradle root: skip module folders (app/) of a build detected at the root.
        if (dir != root && listOf("settings.gradle.kts", "settings.gradle").any { File(root, it).isFile }) return null
        val scripts = (listOf("build.gradle.kts", "build.gradle", "app/build.gradle.kts", "app/build.gradle").map { read(File(dir, it)) } +
            listOf(read(File(dir, "gradle/libs.versions.toml")))).joinToString("\n")
        val android = scripts.contains("com.android.application") || scripts.contains("com.android.library") || scripts.contains("android {")
        val tags = buildList {
            if (scripts.contains("kotlin")) add("Kotlin")
            if (scripts.contains("compose", ignoreCase = true)) add("Jetpack Compose")
            if (android) add("Android")
        }
        val wrapper = File(dir, "gradlew").isFile
        val gradle = if (wrapper) "./gradlew" else "gradle"
        val notes = buildList {
            if (android) add("Needs the Android SDK on the phone: set it up from Settings → System runtime if you haven't. The first run downloads Gradle and the plugins.")
            if (!wrapper) add("No Gradle wrapper (gradlew): Termux's own Gradle may be too new for this build.")
        }
        return DetectedStack(
            if (android) "android" else "gradle", if (android) "Android (Gradle)" else "Gradle", dir, rel(root, build), tags,
            packages = if (wrapper) listOf("openjdk-17") else listOf("openjdk-17", "gradle"),
            // Downloads the wrapper's Gradle, the plugins and the build's dependencies without building.
            steps = listOfNotNull(if (wrapper) "chmod +x gradlew" else null, "$gradle --no-daemon dependencies"),
            installed = File(dir, ".gradle").isDirectory,
            notes = notes,
        )
    }

    private fun python(root: File, dir: File): DetectedStack? {
        val requirements = File(dir, "requirements.txt")
        val pyproject = File(dir, "pyproject.toml")
        val manifest = listOf(requirements, pyproject, File(dir, "Pipfile")).firstOrNull { it.isFile } ?: return null
        val install = when {
            requirements.isFile -> ".venv/bin/pip install -r requirements.txt"
            pyproject.isFile -> ".venv/bin/pip install -e ."
            else -> ".venv/bin/pip install pipenv && .venv/bin/pipenv install"
        }
        return DetectedStack(
            "python", "Python", dir, rel(root, manifest), packages = listOf("python"),
            // A project venv: never installs into Termux's own Python.
            steps = listOf("python -m venv .venv", ".venv/bin/pip install --upgrade pip", install),
            installed = File(dir, ".venv").isDirectory,
            notes = listOf("Packages with native code (numpy, pandas, cryptography…) may need compiling on the phone, or a Termux package (pkg install python-numpy)."),
        )
    }
}
