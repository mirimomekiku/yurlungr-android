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

    /** The Termux packages this stack needs that aren't installed under [prefix] yet. */
    fun missingPackages(prefix: File): List<String> = packages.filterNot { Runtimes.installed(prefix, it) }

    /** Runtimes missing, or dependencies not installed: the project isn't ready to run. */
    fun needsSetup(prefix: File): Boolean = canInstall && (installed == false || missingPackages(prefix).isNotEmpty())
}

/** Termux packages as people know them, and whether one is installed. */
object Runtimes {
    private val NAMES = mapOf(
        "nodejs-lts" to "Node.js", "nodejs" to "Node.js", "pnpm" to "pnpm", "yarn" to "Yarn", "bun" to "Bun", "deno" to "Deno",
        "python" to "Python", "uv" to "uv", "rust" to "Rust", "golang" to "Go", "ruby" to "Ruby", "php" to "PHP", "composer" to "Composer",
        "openjdk-17" to "Java 17", "openjdk-21" to "Java 21", "openjdk-25" to "Java 25", "maven" to "Maven", "gradle" to "Gradle", "kotlin" to "Kotlin", "swift" to "Swift", "dart" to "Dart",
        "erlang" to "Erlang", "elixir" to "Elixir", "zig" to "Zig", "dotnet8.0" to ".NET 8", "crystal" to "Crystal", "nim" to "Nim",
        "lua54" to "Lua", "luarocks" to "LuaRocks", "clang" to "Clang", "make" to "make", "pkg-config" to "pkg-config",
        "libvips" to "libvips", "libcairo" to "cairo", "pango" to "Pango", "libjpeg-turbo" to "libjpeg", "libpng" to "libpng", "libsqlite" to "SQLite library",
    )
    fun name(pkg: String) = NAMES[pkg] ?: pkg

    /** dpkg keeps a file list for every installed package. */
    fun installed(prefix: File, pkg: String) = File(prefix, "var/lib/dpkg/info/$pkg.list").isFile
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
        simple(root, dir, "pom.xml", "maven", "Java (Maven)", listOf("openjdk-21", "maven"), listOf("mvn -q -B dependency:go-offline"), null)?.let(::add)
        simple(root, dir, "Package.swift", "swift", "Swift (SwiftPM)", listOf("swift"), listOf("swift package resolve"),
            File(dir, ".build").isDirectory)?.let(::add)
        deno(root, dir)?.let(::add)
        simple(root, dir, "mix.exs", "elixir", "Elixir (mix)", listOf("erlang", "elixir"),
            listOf("mix local.hex --force --if-missing", "mix local.rebar --force --if-missing", "mix deps.get"), File(dir, "deps").isDirectory)?.let(::add)
        simple(root, dir, "build.zig", "zig", "Zig", listOf("zig"), listOf("zig build --fetch"), null)?.let(::add)
        simple(root, dir, "shard.yml", "crystal", "Crystal (shards)", listOf("crystal"), listOf("shards install"), File(dir, "lib").isDirectory)?.let(::add)
        byExtension(root, dir, setOf("csproj", "fsproj", "sln"), "dotnet", ".NET", listOf("dotnet8.0"), { "dotnet restore" }, File(dir, "obj").isDirectory)?.let(::add)
        byExtension(root, dir, setOf("nimble"), "nim", "Nim (nimble)", listOf("nim"), { "nimble install -d -y" }, null)?.let(::add)
        byExtension(root, dir, setOf("rockspec"), "lua", "Lua (LuaRocks)", listOf("lua54", "luarocks"),
            { "luarocks install --local --only-deps " + dev.yurlungr.app.projects.Git.shellQuote(it.name) }, null)?.let(::add)
    }

    /** A stack whose manifest is named after the project (app.csproj, tool.nimble…): the first one found. */
    private fun byExtension(root: File, dir: File, extensions: Set<String>, id: String, name: String, packages: List<String>,
        step: (File) -> String, installed: Boolean?): DetectedStack? {
        val f = dir.listFiles { x -> x.isFile && x.extension.lowercase() in extensions }?.minByOrNull { it.name } ?: return null
        return DetectedStack(id, name, dir, rel(root, f), packages = packages, steps = listOf(step(f)), installed = installed)
    }

    private fun deno(root: File, dir: File): DetectedStack? {
        val f = listOf("deno.json", "deno.jsonc").map { File(dir, it) }.firstOrNull { it.isFile } ?: return null
        // A Deno project that also has package.json installs those npm packages too.
        return DetectedStack("deno", "Deno", dir, rel(root, f), packages = listOf("deno"), steps = listOf("deno install"),
            installed = File(dir, "node_modules").isDirectory || File(dir, "deno.lock").isFile)
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
        val declaredMatch = Regex("\"packageManager\"\\s*:\\s*\"([a-z]+)@([0-9]+)").find(text)
        val declared = declaredMatch?.groupValues?.get(1)
        val declaredMajor = declaredMatch?.groupValues?.get(2)?.toIntOrNull()
        val manager = when {
            declared != null -> declared
            File(dir, "pnpm-lock.yaml").isFile -> "pnpm"
            File(dir, "yarn.lock").isFile -> "yarn"
            File(dir, "bun.lockb").isFile || File(dir, "bun.lock").isFile -> "bun"
            else -> "npm"
        }
        val notes = mutableListOf<String>()
        val (packages, steps) = when (manager) {
            "pnpm" -> listOf("nodejs-lts", "pnpm") to listOf("pnpm install")
            // Yarn 2+ (Berry) comes from corepack, which follows the version in packageManager.
            "yarn" -> if ((declaredMajor ?: 1) >= 2) listOf("nodejs-lts") to listOf("COREPACK_ENABLE_DOWNLOAD_PROMPT=0 corepack yarn install")
                else listOf("nodejs-lts", "yarn") to listOf("yarn install")
            "bun" -> listOf("nodejs-lts", "bun") to listOf("bun install")
            // npm ci needs a lockfile that matches package.json; install otherwise.
            else -> listOf("nodejs-lts") to listOf(if (File(dir, "package-lock.json").isFile) "npm ci || npm install" else "npm install")
        }
        if ("Expo" in tags) notes += "Start the dev server with `npx expo start` and open it in Expo Go. Native Android builds (expo run:android, EAS local builds) don't work on the phone."
        else if ("React Native" in tags) notes += "Metro (`npx react-native start`) runs here; native Android builds need the Android SDK setup and may still fail on the phone."
        if ("Electron" in tags) notes += "Electron apps can't run on Android; installing lets agents work on the code."
        val addons = deps.filter { it in NATIVE_ADDONS }
        // Compiled on install (node-gyp): a compiler, make and Python, plus the libraries they link.
        val nativePackages = if (addons.any { it in COMPILED_ADDONS }) BUILD_PACKAGES + addons.flatMap { ADDON_LIBRARIES[it].orEmpty() } else emptyList()
        if (addons.isNotEmpty()) notes += "Has native add-ons (${addons.joinToString()})" +
            (if (nativePackages.isNotEmpty()) ": installing also sets up what compiling them needs. " else ". ") +
            "If one still fails, the agent can usually swap in a pure-JS alternative."
        return DetectedStack(
            "node", "Node.js ($manager)", dir, rel(root, manifest), tags, (packages + nativePackages).distinct(), steps,
            installed = File(dir, "node_modules").isDirectory,
            notes = notes,
        )
    }

    private val NATIVE_ADDONS = setOf("sharp", "bcrypt", "canvas", "node-sass", "sqlite3", "better-sqlite3", "node-pty", "@swc/core", "esbuild", "lightningcss")
    /** Built from source on Android (no prebuilt binary for it): these need node-gyp's toolchain. */
    private val COMPILED_ADDONS = setOf("sharp", "bcrypt", "canvas", "node-sass", "sqlite3", "better-sqlite3", "node-pty")
    internal val BUILD_PACKAGES = listOf("python", "clang", "make", "pkg-config")
    private val ADDON_LIBRARIES = mapOf(
        "sharp" to listOf("libvips"),
        "canvas" to listOf("libcairo", "pango", "libjpeg-turbo", "libpng"),
        "sqlite3" to listOf("libsqlite"), "better-sqlite3" to listOf("libsqlite"),
    )

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
            packages = if (wrapper) listOf("openjdk-21") else listOf("openjdk-21", "gradle"),
            // Downloads the wrapper's Gradle, the plugins and the build's dependencies without building.
            steps = listOfNotNull(if (wrapper) "chmod +x gradlew" else null, "$gradle --no-daemon dependencies"),
            installed = File(dir, ".gradle").isDirectory,
            notes = notes,
        )
    }

    /**
     * The project's venv, made once. With system site-packages, so the prebuilt numpy, pandas…
     * from the Python data & science pack are used instead of compiled; pip still installs into it.
     */
    internal const val VENV = "[ -d .venv ] || python -m venv --system-site-packages .venv"

    private fun python(root: File, dir: File): DetectedStack? {
        val requirements = File(dir, "requirements.txt")
        val pyproject = File(dir, "pyproject.toml")
        val manifest = listOf(requirements, pyproject, File(dir, "Pipfile")).firstOrNull { it.isFile } ?: return null
        val pyText = read(pyproject)
        val tool = when {
            File(dir, "uv.lock").isFile -> "uv"
            File(dir, "poetry.lock").isFile || pyText.contains("[tool.poetry]") -> "poetry"
            else -> "pip"
        }
        val install = when {
            requirements.isFile -> ".venv/bin/pip install -r requirements.txt"
            pyproject.isFile -> ".venv/bin/pip install -e ."
            else -> ".venv/bin/pip install pipenv && .venv/bin/pipenv install"
        }
        // Always a project .venv: never installs into Termux's own Python.
        val steps = when (tool) {
            "uv" -> listOf("[ -d .venv ] || uv venv --system-site-packages .venv", "uv sync")
            "poetry" -> listOf(VENV, ".venv/bin/pip install --upgrade pip poetry",
                "POETRY_VIRTUALENVS_IN_PROJECT=true .venv/bin/poetry install --no-root")
            else -> listOf(VENV, ".venv/bin/pip install --upgrade pip", install)
        }
        return DetectedStack(
            "python", if (tool == "pip") "Python" else "Python ($tool)", dir, rel(root, manifest),
            packages = if (tool == "uv") listOf("python", "uv") else listOf("python"),
            steps = steps,
            installed = File(dir, ".venv").isDirectory,
            notes = listOf("Packages with native code (numpy, pandas, cryptography…) may need compiling on the phone, or a Termux package (pkg install python-numpy)."),
        )
    }
}
