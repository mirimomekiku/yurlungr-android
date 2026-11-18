package dev.yurlungr.app.projects

import dev.yurlungr.app.agents.AgentExtensions
import java.io.File
import java.util.Locale

/**
 * A kind of folder that tools regenerate. [markers]: files one of which must sit beside it, for names that
 * are also ordinary source folders elsewhere ("build", "dist", "vendor"…); null when the name alone is enough.
 */
enum class JunkKind(val folder: String, val what: String, val ifDeleted: String, val markers: List<String>? = null) {
    NodeModules("node_modules", "Installed npm packages", "Reinstalled by npm, pnpm, yarn or bun install."),
    Gradle(".gradle", "Gradle's cache for this project", "Rebuilt by the next Gradle build."),
    Build("build", "Build output", "Made again by the next build.",
        listOf("build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts", "package.json", "pubspec.yaml", "CMakeLists.txt", "setup.py", "pyproject.toml")),
    Target("target", "Rust or Maven build output", "Made again by the next cargo or mvn build.", listOf("Cargo.toml", "pom.xml")),
    Venv(".venv", "Python virtual environment", "Recreate it and reinstall the packages (python -m venv .venv, then pip install)."),
    Dist("dist", "Build output", "Made again by the next build.", listOf("package.json", "pyproject.toml", "setup.py")),
    Next(".next", "Next.js build and cache", "Made again by the next dev run or build."),
    Nuxt(".nuxt", "Nuxt build files", "Made again by the next dev run or build."),
    SvelteKit(".svelte-kit", "SvelteKit generated files", "Made again by the next dev run or build."),
    Turbo(".turbo", "Turborepo cache", "Tasks run in full once, then cache again."),
    Parcel(".parcel-cache", "Parcel cache", "The next build takes longer once."),
    PyCache("__pycache__", "Compiled Python files", "Python compiles them again as needed."),
    DartTool(".dart_tool", "Dart and Flutter tool files", "Made again by dart pub get or flutter pub get."),
    Vendor("vendor", "PHP packages", "Reinstallable with composer install.", listOf("composer.json"));

    companion object {
        private val byName = entries.associateBy { it.folder }
        fun of(name: String) = byName[name]
    }
}

/** A shared cache under home: where it is, what it holds, what deleting it costs. */
data class SharedCache(val relative: String, val title: String, val what: String, val ifDeleted: String)

/** Something that can be deleted to free space: one folder, or several (all __pycache__ in a project). */
data class SpaceItem(
    val id: String,
    /** The project's name, or null for a shared cache. */
    val project: String?,
    val title: String,
    val what: String,
    val ifDeleted: String,
    val dirs: List<File>,
    val bytes: Long = 0,
    /** False when the walk stopped early: [bytes] is then a lower bound. */
    val complete: Boolean = true,
    /** Why to look twice: an ambiguous name that .gitignore doesn't mention. */
    val caution: String? = null,
)

/** A regenerable folder found in a project. [ignored]: null when there's no .gitignore to ask. */
data class JunkFound(val kind: JunkKind, val dir: File, val ignored: Boolean?)

/**
 * Finding and deleting regenerable folders (node_modules, build output, virtualenvs…) in ~/projects, and
 * the shared package caches. Never follows links; deletes only what [isDeletable] allows.
 */
object SpaceCleaner {
    val SHARED = listOf(
        SharedCache(".npm/_cacache", "npm cache", "Packages npm downloaded before", "npm downloads packages again when it needs them."),
        SharedCache(".cache/pip", "pip cache", "Packages pip downloaded before", "pip downloads packages again when it needs them."),
        SharedCache(".cache/uv", "uv cache", "Packages uv downloaded or built", "uv downloads packages again when it needs them."),
        SharedCache(".gradle/caches", "Gradle caches", "Dependencies and build caches for every Gradle project", "The next Gradle build downloads dependencies again and takes longer."),
        SharedCache(".cargo/registry", "Cargo registry", "Crates downloaded for Rust projects", "cargo downloads crates again on the next build."),
        SharedCache("go/pkg/mod", "Go modules", "Modules downloaded for Go projects", "go downloads modules again on the next build."),
        SharedCache(".bun/install/cache", "Bun cache", "Packages bun downloaded before", "bun downloads packages again when it needs them."),
        SharedCache(".pub-cache", "Dart pub cache", "Packages for Dart and Flutter projects", "pub get downloads them again; tools added with dart pub global activate need activating again."),
    )

    /** Whether [name] in [parent] is a regenerable folder, and which kind. */
    fun classify(name: String, parent: File): JunkKind? {
        val kind = JunkKind.of(name) ?: return null
        val markers = kind.markers ?: return kind
        return kind.takeIf { markers.any { File(parent, it).isFile } }
    }

    /**
     * Whether a .gitignore in [parent] or [projectRoot] lists [name] (as `name`, `/name`, `name/`, or under a `**` prefix).
     * Null when neither has a .gitignore. A rough reading, only used to warn.
     */
    fun ignoredByGit(name: String, parent: File, projectRoot: File): Boolean? {
        val files = listOf(File(parent, ".gitignore"), File(projectRoot, ".gitignore")).distinctBy { it.path }.filter { it.isFile && it.length() < 256 * 1024 }
        if (files.isEmpty()) return null
        val rel = runCatching { parent.relativeTo(projectRoot).invariantSeparatorsPath }.getOrDefault("")
        return files.any { f ->
            runCatching { f.readLines() }.getOrDefault(emptyList()).any { raw ->
                val line = raw.trim().removeSuffix("/").removeSuffix("/**").removePrefix("**/")
                !line.startsWith("#") && !line.startsWith("!") &&
                    (line == name || line == "/$name" || (rel.isNotEmpty() && (line == "$rel/$name" || line == "/$rel/$name")) || line == "$name*")
            }
        }
    }

    /**
     * Regenerable folders in [project], not descending into them, into links or into .git. Bounded by
     * [maxDepth] and [maxDirs] so a huge tree can't stall the scan.
     */
    fun find(project: File, maxDepth: Int = 8, maxDirs: Int = 20_000): List<JunkFound> {
        val found = ArrayList<JunkFound>()
        val stack = ArrayDeque<Pair<File, Int>>()
        stack.addLast(project to 0)
        var visited = 0
        while (stack.isNotEmpty() && visited < maxDirs) {
            val (dir, depth) = stack.removeLast()
            visited++
            val children = dir.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name } ?: continue
            for (child in children) {
                if (child.name == ".git" || AgentExtensions.isLink(child)) continue
                val kind = classify(child.name, dir)
                if (kind != null) found += JunkFound(kind, child, if (kind.markers != null) ignoredByGit(child.name, dir, project) else true)
                else if (depth + 1 < maxDepth) stack.addLast(child to depth + 1)
            }
        }
        return found.sortedBy { it.dir.path }
    }

    /** Bytes in [dir] (files only; links counted as nothing and never followed); false when it stopped at [maxEntries]. */
    fun sizeOf(dir: File, maxEntries: Int = 1_000_000, onProgress: (Long) -> Unit = {}): Pair<Long, Boolean> {
        if (AgentExtensions.isLink(dir)) return 0L to true
        var bytes = 0L
        var entries = 0
        val stack = ArrayDeque<File>()
        stack.addLast(dir)
        while (stack.isNotEmpty()) {
            val d = stack.removeLast()
            for (f in d.listFiles().orEmpty()) {
                if (++entries > maxEntries) return bytes to false
                if (AgentExtensions.isLink(f)) continue
                if (f.isDirectory) stack.addLast(f) else bytes += f.length()
            }
            if (entries % 2000 == 0) onProgress(bytes)
        }
        return bytes to true
    }

    /** The items for one project: one per folder, all `__pycache__` folders as one. Sizes not yet measured. */
    fun items(project: File, found: List<JunkFound>): List<SpaceItem> {
        val (py, rest) = found.partition { it.kind == JunkKind.PyCache }
        fun rel(f: File) = runCatching { f.relativeTo(project).invariantSeparatorsPath }.getOrDefault(f.name)
        return rest.map { j ->
            SpaceItem(
                id = j.dir.path, project = project.name, title = rel(j.dir), what = j.kind.what, ifDeleted = j.kind.ifDeleted, dirs = listOf(j.dir),
                caution = if (j.kind.markers != null && j.ignored != true) "Not in .gitignore: check it's made by a build before deleting." else null,
            )
        } + listOfNotNull(py.takeIf { it.isNotEmpty() }?.let { list ->
            val k = JunkKind.PyCache
            SpaceItem("${project.path}#__pycache__", project.name, "__pycache__ (${list.size} folder${if (list.size == 1) "" else "s"})", k.what, k.ifDeleted, list.map { it.dir })
        })
    }

    /** Shared caches that exist under [home]. */
    fun sharedItems(home: File): List<SpaceItem> = SHARED.mapNotNull { c ->
        File(home, c.relative).takeIf { it.isDirectory && !AgentExtensions.isLink(it) }?.let { SpaceItem(it.path, null, c.title, c.what, c.ifDeleted, listOf(it)) }
    }

    /**
     * Where [f] really is, every link on the way resolved, even when [f] itself doesn't exist yet. java.nio where it
     * exists (API 26+; also right on Windows, whose canonical paths keep links), else the canonical path.
     */
    @android.annotation.SuppressLint("NewApi")
    fun realFile(f: File): File {
        val abs = f.absoluteFile
        var existing: File? = abs
        while (existing != null && !existing.exists()) existing = existing.parentFile
        val base = existing ?: return abs.canonicalFile
        val real = runCatching { base.toPath().toRealPath().toFile() }.getOrElse { base.canonicalFile }
        val rest = abs.path.removePrefix(base.path).trimStart(File.separatorChar)
        return if (rest.isEmpty()) real else File(real, rest).canonicalFile
    }

    /** [f] as where its entry really is: its folder resolved, its own name kept (so a link is judged as a link). */
    private fun entryOf(f: File): File? = runCatching { File(realFile(f.absoluteFile.parentFile), f.name) }.getOrNull()

    /**
     * True only for a regenerable folder strictly inside a project in [projectsRoot] (never a project itself,
     * nothing in .git, a name from [JunkKind]) or exactly one of the [SHARED] caches under [home], reached
     * without passing through a link.
     */
    fun isDeletable(target: File, projectsRoot: File, home: File): Boolean {
        val entry = entryOf(target) ?: return false
        if (entry.name.isEmpty() || entry.name == "." || entry.name == "..") return false
        val root = runCatching { realFile(projectsRoot) }.getOrNull() ?: return false
        if (entry.path.startsWith(root.path + File.separator)) {
            val segments = entry.relativeTo(root).invariantSeparatorsPath.split('/')
            return segments.size >= 2 && segments.none { it == ".git" || it == ".." || it == "." || it.isEmpty() } &&
                // The same test the scan used: "build" only beside a build file, "vendor" only beside composer.json…
                classify(entry.name, entry.parentFile) != null
        }
        val h = runCatching { realFile(home) }.getOrNull() ?: return false
        return SHARED.any { File(h, it.relative).path == entry.path }
    }

    /** Deletes [target] after [isDeletable] agrees; links inside are removed, never followed. */
    fun delete(target: File, projectsRoot: File, home: File): Result<Unit> = runCatching {
        check(isDeletable(target, projectsRoot, home)) { "Refusing to delete ${target.path}" }
        val entry = entryOf(target) ?: error("Refusing to delete ${target.path}")
        deleteTree(entry)
        check(!entry.exists() && !AgentExtensions.isLink(entry)) { "Couldn't delete everything in ${target.name}" }
    }

    /** Like deleteRecursively, but links are unlinked, and read-only folders (Go's module cache) made writable first. */
    internal fun deleteTree(f: File): Boolean {
        if (!AgentExtensions.isLink(f) && f.isDirectory) {
            f.setWritable(true, true)
            f.listFiles()?.forEach { deleteTree(it) }
        }
        return f.delete() || !f.exists() && !AgentExtensions.isLink(f)
    }

    fun formatBytes(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024L * 1024 -> "${bytes / 1024} KB"
        bytes < 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024))
        else -> String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024 * 1024))
    }
}
