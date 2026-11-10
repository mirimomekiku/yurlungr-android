package dev.yurlungr.app.runtime

import android.content.Context
import android.system.Os
import java.io.File

/**
 * Files the app keeps inside the runtime. Rewritten on every launch so they track the
 * installed APK: bundled scripts, shims on PATH, and agent instructions.
 */
object RuntimeAssets {
    private val EXEC = "700".toInt(8)

    private fun scripts(paths: RuntimePaths) = mapOf(
        "setup-common.sh" to File(paths.libexec, "setup-common.sh"),
        "spike-test.sh" to File(paths.home, "spike-test.sh"),
        "agents-setup.sh" to File(paths.libexec, "agents-setup.sh"),
        "dev-tools-setup.sh" to File(paths.libexec, "dev-tools-setup.sh"),
        "android-sdk-setup.sh" to File(paths.libexec, "android-sdk-setup.sh"),
        "motd.sh" to File(paths.libexec, "motd.sh"),
    )

    /** Commands tools use to open a browser. All route to `yurlungr-open`. */
    private val OPEN_ALIASES = listOf("xdg-open", "termux-open-url", "termux-open", "sensible-browser")

    /** Why the last refresh failed (shown in System checks), or null after a successful one. */
    @Volatile var lastError: String? = null
        private set

    /**
     * Parts of the last refresh that live in the user's home (agent notes, the MOTD hook) and
     * couldn't be written. Never fatal: a read-only ~/.claude/CLAUDE.md must not block the runtime.
     */
    @Volatile var lastWarning: String? = null
        private set

    /** Refreshes everything; records (and rethrows) a failure so System checks can offer a repair. */
    @Synchronized
    fun install(context: Context, paths: RuntimePaths) {
        // Also during a bootstrap install (before it's marked complete): it runs this as its last step.
        if (!paths.hasBaseSystem) return
        try {
            installAll(context, paths)
            lastError = null
        } catch (e: Exception) {
            lastError = e.message ?: e.toString()
            throw e
        }
    }

    private fun installAll(context: Context, paths: RuntimePaths) {
        val warnings = mutableListOf<String>()
        paths.home.mkdirs()
        paths.shimBin.mkdirs()
        paths.inbox.mkdirs()
        // Probes, setups and many tools need it; nothing else recreates it if it's deleted.
        paths.tmp.mkdirs()

        for ((asset, dest) in scripts(paths)) {
            val text = context.assets.open(asset).bufferedReader(Charsets.UTF_8).use { it.readText() }
            writeExecutable(dest, text)
        }
        // Package lists and version checks for the setup scripts, from the same source as System checks.
        writeExecutable(File(paths.libexec, "toolchain.sh"), Toolchain.shellManifest())

        val motdLauncher = "#!${paths.bin}/sh\n# Yurlungr dynamic MOTD\nexec '${paths.bash}' '${paths.libexec}/motd.sh'\n"
        writeShim(paths, "yurlungr-status", motdLauncher)
        // Termux's supported dynamic MOTD hook. Never overwrite a user's own hook or static MOTD.
        val hook = File(paths.home, ".termux/motd.sh")
        if (!hook.exists() && runCatching { Os.lstat(hook.path); false }.getOrDefault(true)) {
            runCatching {
                hook.parentFile?.mkdirs()
                writeExecutable(hook, motdLauncher)
            }.onFailure { warnings += "terminal welcome hook (${it.message})" }
        }

        writeShim(paths, "yurlungr-open", openShim(paths))
        writeShim(paths, "yurlungr-install", installShim(paths))
        for (alias in OPEN_ALIASES) {
            // An alias the user replaced with something else (e.g. a folder) is left alone.
            runCatching {
                val link = File(paths.shimBin, alias)
                link.delete()
                Os.symlink("yurlungr-open", link.path)
            }.onFailure { warnings += "$alias shim (${it.message})" }
        }

        // Global instruction files each CLI reads at startup. Best effort: they're the user's files.
        for (file in listOf(".claude/CLAUDE.md", ".codex/AGENTS.md", ".gemini/GEMINI.md")) {
            runCatching { upsertManagedBlock(File(paths.home, file), agentGuide()) }
                .onFailure { warnings += "~/$file (${it.message})" }
        }
        lastWarning = warnings.takeIf { it.isNotEmpty() }?.joinToString("; ")
    }

    private fun writeShim(paths: RuntimePaths, name: String, body: String) {
        writeExecutable(File(paths.shimBin, name), body)
    }

    /** Publish complete LF-only files; an interrupted refresh must not truncate a running script. */
    private fun writeExecutable(file: File, source: String) {
        val text = normalizeRuntimeScript(source)
        if (file.isFile && file.readText() == text && file.canExecute()) return
        val parent = requireNotNull(file.parentFile)
        check(parent.isDirectory || parent.mkdirs()) { "Cannot create runtime directory: $parent" }
        val tmp = File.createTempFile(".${file.name}.", ".tmp", parent)
        try {
            tmp.writeText(text, Charsets.UTF_8)
            Os.chmod(tmp.path, EXEC)
            Os.rename(tmp.path, file.path)
        } finally {
            tmp.delete()
        }
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
        val updated = mergeManagedBlock(current, block)
        if (updated != current) {
            file.parentFile?.mkdirs()
            // Through the editor's atomic save: an interrupted write must not truncate the user's notes.
            dev.yurlungr.app.editor.FileSave.writeAtomically(file, updated)
        }
    }

    /** [current] with the managed [block] replaced, or appended after the user's text. */
    internal fun mergeManagedBlock(current: String, block: String): String {
        val start = current.indexOf(BEGIN)
        val end = current.indexOf(END)
        return if (start >= 0 && end > start) {
            current.substring(0, start) + block.trimEnd('\n') + current.substring(end + END.length)
        } else {
            (if (current.isBlank()) "" else current.trimEnd('\n') + "\n\n") + block
        }
    }
}
