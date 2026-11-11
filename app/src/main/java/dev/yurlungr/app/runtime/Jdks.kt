package dev.yurlungr.app.runtime

import java.io.File

/** One JDK under `$PREFIX/lib/jvm`. */
data class Jdk(val version: Int, val home: File) {
    val pkg: String get() = Jdks.pkg(version)
}

/**
 * The installed JDKs and which one is the default. Termux's openjdk packages register Debian
 * "alternatives": `java`, `javac`… and `profile.d/java.sh` (JAVA_HOME) all follow one choice,
 * which `update-alternatives --set java` changes and `--auto` hands back to the newest.
 */
object Jdks {
    /** The versions Termux packages. Gradle, Kotlin, Maven, d8 and apksigner depend on 21. */
    val AVAILABLE = listOf(17, 21, 25)
    const val REQUIRED = 21

    fun pkg(version: Int) = "openjdk-$version"

    private val DIR = Regex("^java-(\\d+)-openjdk$")

    fun installed(prefix: File): List<Jdk> =
        File(prefix, "lib/jvm").listFiles().orEmpty().mapNotNull { dir ->
            val version = DIR.find(dir.name)?.groupValues?.get(1)?.toIntOrNull() ?: return@mapNotNull null
            Jdk(version, dir).takeIf { File(dir, "bin/java").isFile }
        }.sortedBy { it.version }

    /** The JDK `java` runs: where the alternatives link points, else the newest installed. */
    fun current(prefix: File): Jdk? {
        val all = installed(prefix)
        val target = runCatching { File(prefix, "etc/alternatives/java").canonicalFile }.getOrNull()
        return all.firstOrNull { jdk -> target != null && target.path.startsWith(jdk.home.canonicalPath + File.separator) }
            ?: all.lastOrNull()
    }

    /** Whether the user picked one (manual) or the newest is used (auto). */
    fun isManual(prefix: File): Boolean =
        runCatching { File(prefix, "var/lib/dpkg/alternatives/java").readLines().firstOrNull()?.trim() == "manual" }.getOrDefault(false)

    /** Makes [jdk] the default for `java`, `javac`, the other JDK tools and JAVA_HOME. */
    fun useCommand(prefix: File, jdk: Jdk): List<String> =
        listOf(File(prefix, "bin/update-alternatives").path, "--set", "java", File(jdk.home, "bin/java").path)

    /** Back to automatic: the newest installed JDK. */
    fun autoCommand(prefix: File): List<String> = listOf(File(prefix, "bin/update-alternatives").path, "--auto", "java")

    // ---- Gradle toolchains ----------------------------------------------------------------

    const val GRADLE_PATHS = "org.gradle.java.installations.paths"
    const val GRADLE_AUTO_DOWNLOAD = "org.gradle.java.installations.auto-download"

    /**
     * [text] (a gradle.properties) with Gradle pointed at every installed JDK, so projects that
     * ask for a toolchain (`jvmToolchain(17)`) find it. Gradle doesn't look in `$PREFIX/lib/jvm`
     * on its own, and its automatic JDK download has no Android builds. Other lines are kept.
     */
    fun withGradleToolchains(text: String, jdks: List<Jdk>): String {
        val wanted = linkedMapOf(GRADLE_PATHS to jdks.joinToString(",") { it.home.path }, GRADLE_AUTO_DOWNLOAD to "false")
        val lines = text.lines().dropLastWhile { it.isEmpty() }.toMutableList()
        for ((key, value) in wanted) {
            val i = lines.indexOfFirst { it.substringBefore('=').trim() == key }
            if (i >= 0) lines[i] = "$key=$value" else lines += "$key=$value"
        }
        return lines.joinToString("\n") + "\n"
    }

    /** Updates ~/.gradle/gradle.properties; true when it changed. Nothing to do without a JDK. */
    fun syncGradle(paths: RuntimePaths): Boolean {
        val jdks = installed(paths.prefix)
        if (jdks.isEmpty()) return false
        val file = File(paths.home, ".gradle/gradle.properties")
        val before = if (file.isFile) file.readText() else ""
        val after = withGradleToolchains(before, jdks)
        if (after == before) return false
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(after)
        if (!tmp.renameTo(file)) { file.writeText(after); tmp.delete() }
        return true
    }
}
