package dev.yurlungr.app.runtime

import java.io.File

/** A cache the user can clear: where it lives and how to clear it safely. */
data class CacheDir(val id: String, val title: String, val paths: List<File>, val clean: String)

/**
 * Disk use in the runtime: what each optional pack's packages take (from dpkg's Installed-Size),
 * package caches and how to clear them, and pending package updates.
 */
object Storage {
    fun caches(paths: RuntimePaths): List<CacheDir> {
        val h = paths.home
        fun q(f: File) = "'" + f.path.replace("'", "'\\''") + "'"
        fun rm(vararg f: File) = "rm -rf " + f.joinToString(" ") { q(it) }
        fun prefer(tool: String, command: String, fallback: String) =
            "if command -v $tool >/dev/null; then $command; else $fallback; fi"
        val gradle = File(h, ".gradle/caches")
        val npm = File(h, ".npm/_cacache")
        val pnpm = File(h, "${RuntimeEnv.PNPM_HOME}/store")
        val yarn = File(h, ".cache/yarn")
        val bun = File(h, ".bun/install/cache")
        val pip = File(h, ".cache/pip")
        val uv = File(h, ".cache/uv")
        val cargo = File(h, ".cargo/registry")
        val gomod = File(h, "go/pkg/mod")
        val maven = File(h, ".m2/repository")
        val apt = File(paths.prefix, "var/cache/apt/archives")
        return listOf(
            CacheDir("gradle", "Gradle caches", listOf(gradle), rm(gradle)),
            CacheDir("npm", "npm cache", listOf(npm), prefer("npm", "npm cache clean --force", rm(npm))),
            CacheDir("pnpm", "pnpm store", listOf(pnpm), prefer("pnpm", "pnpm store prune", rm(pnpm))),
            CacheDir("yarn", "Yarn cache", listOf(yarn), rm(yarn)),
            CacheDir("bun", "Bun cache", listOf(bun), rm(bun)),
            CacheDir("pip", "pip cache", listOf(pip), rm(pip)),
            CacheDir("uv", "uv cache", listOf(uv), prefer("uv", "uv cache clean", rm(uv))),
            CacheDir("cargo", "Cargo registry", listOf(cargo), rm(cargo)),
            // Go marks module files read-only.
            CacheDir("go", "Go modules", listOf(gomod), prefer("go", "go clean -modcache", "chmod -R u+w ${q(gomod)} && ${rm(gomod)}")),
            CacheDir("maven", "Maven repository", listOf(maven), rm(maven)),
            CacheDir("apt", "Downloaded packages", listOf(apt), "apt-get clean"),
        )
    }

    /** `du -sk` for every path at once; missing ones are left out. */
    fun duScript(dirs: List<File>): String =
        "du -sk " + dirs.joinToString(" ") { "'" + it.path.replace("'", "'\\''") + "'" } + " 2>/dev/null; true"

    /** Parses `du -sk` output into path → bytes. */
    fun parseDu(output: String): Map<String, Long> = output.lines().mapNotNull { line ->
        val parts = line.split('\t', limit = 2)
        val kb = parts.getOrNull(0)?.trim()?.toLongOrNull() ?: return@mapNotNull null
        parts.getOrNull(1)?.trim()?.let { it to kb * 1024 }
    }.toMap()

    /** Installed packages and their Installed-Size in bytes, from dpkg's status file. */
    fun installedSizes(prefix: File): Map<String, Long> = parseDpkgStatus(
        runCatching { File(prefix, "var/lib/dpkg/status").readText() }.getOrDefault(""),
    )

    internal fun parseDpkgStatus(text: String): Map<String, Long> = text.split("\n\n").mapNotNull { block ->
        var name: String? = null
        var size = 0L
        var installed = false
        for (line in block.lines()) when {
            line.startsWith("Package: ") -> name = line.removePrefix("Package: ").trim()
            line.startsWith("Installed-Size: ") -> size = line.removePrefix("Installed-Size: ").trim().toLongOrNull()?.times(1024) ?: 0
            line.startsWith("Status: ") -> installed = line.endsWith(" installed")
        }
        name?.takeIf { installed }?.let { it to size }
    }.toMap()

    /** What a pack's own packages take (shared dependencies aren't counted). */
    fun packSize(pack: ToolSetup, sizes: Map<String, Long>): Long = pack.packages.sumOf { sizes[it] ?: 0L }

    /** Packages with an update, from the package lists already on the phone (no download). */
    const val UPGRADABLE_SCRIPT = "apt list --upgradable 2>/dev/null | grep -c / ; true"

    /** Refreshes the package lists, then upgrades everything, keeping changed config files. */
    const val UPGRADE_COMMAND = "apt-get update && apt-get -y -o Dpkg::Options::=--force-confdef -o Dpkg::Options::=--force-confold full-upgrade"

    fun human(bytes: Long): String = when {
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        bytes < 1024L * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
        else -> String.format(java.util.Locale.US, "%.1f GB", bytes / (1024.0 * 1024 * 1024))
    }
}
