package dev.yurlungr.app.runtime

import android.content.Context
import android.system.Os
import java.io.File
import java.io.FileOutputStream

/**
 * Files the app keeps inside the runtime. Rewritten on every launch so they track the
 * installed APK: bundled scripts, shims on PATH, and agent instructions.
 */
object RuntimeAssets {
    private val EXEC = "700".toInt(8)

    private fun scripts(paths: RuntimePaths) = mapOf(
        "spike-test.sh" to File(paths.home, "spike-test.sh"),
        "agents-setup.sh" to File(paths.libexec, "agents-setup.sh"),
        "android-sdk-setup.sh" to File(paths.libexec, "android-sdk-setup.sh"),
    )

    /** Commands tools use to open a browser. All route to `yurlungr-open`. */
    private val OPEN_ALIASES = listOf("xdg-open", "termux-open-url", "termux-open", "sensible-browser")

    fun install(context: Context, paths: RuntimePaths) {
        if (!paths.isBootstrapInstalled) return
        paths.home.mkdirs()
        paths.shimBin.mkdirs()
        paths.inbox.mkdirs()

        for ((asset, dest) in scripts(paths)) {
            dest.parentFile?.mkdirs()
            context.assets.open(asset).use { input -> FileOutputStream(dest).use { input.copyTo(it) } }
            Os.chmod(dest.path, EXEC)
        }

        writeShim(paths, "yurlungr-open", openShim(paths))
        writeShim(paths, "yurlungr-install", installShim(paths))
        for (alias in OPEN_ALIASES) {
            val link = File(paths.shimBin, alias)
            link.delete()
            Os.symlink("yurlungr-open", link.path)
        }

        // Global instruction files each CLI reads at startup.
        for (file in listOf(".claude/CLAUDE.md", ".codex/AGENTS.md", ".gemini/GEMINI.md")) {
            upsertManagedBlock(File(paths.home, file), agentGuide())
        }
    }

    private fun writeShim(paths: RuntimePaths, name: String, body: String) {
        val file = File(paths.shimBin, name)
        file.writeText(body)
        Os.chmod(file.path, EXEC)
    }

    // Android's `am` refuses calls from app UIDs, so shims hand requests to the app through
    // a watched directory (write a temp file, then rename: the app only sees complete files).
    private fun openShim(paths: RuntimePaths) = """
        |#!${paths.bin}/sh
        |# Opens an http(s) URL in the Android browser via the Yurlungr app.
        |if [ $# -lt 1 ]; then echo "usage: ${'$'}{0##*/} <url>" >&2; exit 64; fi
        |${submit(paths, "\"\$1\"", "open")}
        |echo "Opening in browser: ${'$'}1" >&2
        |""".trimMargin()

    private fun installShim(paths: RuntimePaths) = """
        |#!${paths.bin}/sh
        |# Shows Android's install prompt for an APK built in the runtime.
        |if [ $# -ne 1 ]; then echo "usage: yurlungr-install <file.apk>" >&2; exit 64; fi
        |apk=${'$'}(realpath "${'$'}1") || exit 66
        |case "${'$'}apk" in *.apk) ;; *) echo "not an .apk: ${'$'}apk" >&2; exit 65 ;; esac
        |[ -f "${'$'}apk" ] || { echo "no such file: ${'$'}apk" >&2; exit 66; }
        |${submit(paths, "\"\$apk\"", "install")}
        |echo "Install prompt shown for ${'$'}apk — confirm it on screen." >&2
        |""".trimMargin()

    private fun submit(paths: RuntimePaths, valueExpr: String, kind: String) = """
        |inbox='${paths.inbox}'
        |mkdir -p "${'$'}inbox"
        |tmp="${'$'}inbox/.${'$'}${'$'}.tmp"
        |printf '%s' $valueExpr > "${'$'}tmp" && mv "${'$'}tmp" "${'$'}inbox/${'$'}${'$'}-${'$'}(date +%s%N).$kind"
        """.trimMargin()

    private const val BEGIN = "<!-- yurlungr:begin (managed by the Yurlungr app; edits inside are overwritten) -->"
    private const val END = "<!-- yurlungr:end -->"

    private fun agentGuide() = """
        |$BEGIN
        |## Environment: Android phone (Yurlungr)
        |- You run in a Termux-compatible Linux userland on the user's Android phone (aarch64, Bionic libc).
        |  No root, sudo, systemd or Docker. Install tools with `pkg install <name>`; global npm packages work.
        |  Prebuilt x86-64 or glibc-only binaries usually don't run.
        |- Projects live in ~/projects.
        |- Android apps build on-device: `./gradlew assembleDebug`. The SDK is at ${'$'}ANDROID_HOME and
        |  ~/.gradle/gradle.properties already points AGP at a native aapt2. Use AGP 8.x with a Gradle 8.13–8.14
        |  wrapper (./gradlew) and compileSdk 36. Don't run Termux's `gradle` (9.x) in AGP 8 projects: it can't
        |  load AGP 8. To add a wrapper, run `gradle wrapper --gradle-version 8.14` in an empty dir containing
        |  only an empty settings.gradle, then copy gradlew + gradle/ in. NDK/CMake native code is not supported.
        |- Install an APK on this phone with `yurlungr-install path/to/app.apk` (the user confirms on screen).
        |- Open a URL in the phone's browser with `xdg-open <url>`.
        |- GitHub: use `git` and `gh`. The user signs in from the app's Projects screen (gh auth + git credential
        |  helper), so pushes work once they have. Don't ask for tokens or passwords.
        |- The user reads your replies on a phone screen: keep them short.
        |$END
        |""".trimMargin()

    /** Replaces the managed block in [file] (or appends it), leaving the user's own text alone. */
    private fun upsertManagedBlock(file: File, block: String) {
        val current = if (file.isFile) file.readText() else ""
        val start = current.indexOf(BEGIN)
        val end = current.indexOf(END)
        val updated = if (start >= 0 && end > start) {
            current.substring(0, start) + block.trimEnd('\n') + current.substring(end + END.length)
        } else {
            (if (current.isBlank()) "" else current.trimEnd('\n') + "\n\n") + block
        }
        if (updated != current) {
            file.parentFile?.mkdirs()
            file.writeText(updated)
        }
    }
}
