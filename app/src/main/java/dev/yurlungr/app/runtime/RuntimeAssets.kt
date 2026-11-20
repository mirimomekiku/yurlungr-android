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

    /** (asset, destination) pairs; one asset can be installed under several names ([ToolSetup.asset]). */
    private fun scripts(paths: RuntimePaths) = ToolSetup.entries.filter { it.optional && it.script != null }
        .map { it.asset!! to File(paths.libexec, it.script!!) } + listOf(
        "setup-common.sh" to File(paths.libexec, "setup-common.sh"),
        "spike-test.sh" to File(paths.home, "spike-test.sh"),
        "agents-setup.sh" to File(paths.libexec, "agents-setup.sh"),
        "dev-tools-setup.sh" to File(paths.libexec, "dev-tools-setup.sh"),
        "essentials-setup.sh" to File(paths.libexec, "essentials-setup.sh"),
        "agent-install.sh" to File(paths.libexec, "agent-install.sh"),
        "agent-key.sh" to File(paths.libexec, "agent-key.sh"),
        "project-setup.sh" to File(paths.libexec, "project-setup.sh"),
        "pack-remove.sh" to File(paths.libexec, "pack-remove.sh"),
        "restore-packages.sh" to File(paths.libexec, "restore-packages.sh"),
        "self-test.sh" to File(paths.libexec, "self-test.sh"),
        "android-sdk-setup.sh" to File(paths.libexec, "android-sdk-setup.sh"),
        "motd.sh" to File(paths.libexec, "motd.sh"),
        "project-db.sh" to File(paths.libexec, "project-db.sh"),
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
        // Gradle can use every installed JDK (projects that pin a toolchain); never fatal.
        runCatching { Jdks.syncGradle(paths) }

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
        writeShim(paths, "yurlungr-notify", notifyShim(paths))
        // In $PREFIX/bin (not the shims folder): templates and tools call it by that path. An absolute
        // shebang, so the app can also start it directly, without termux-exec.
        val ubuntu = context.assets.open("yurlungr-ubuntu.sh").bufferedReader(Charsets.UTF_8).use { it.readText() }
        writeExecutable(File(paths.bin, "yurlungr-ubuntu"), ubuntu.replaceFirst("#!/usr/bin/env bash", "#!${paths.bash.path}"))
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
            runCatching { upsertManagedBlock(File(paths.home, file), agentGuide(paths)) }
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
    // Files go by path; the app checks it (inside home, not hidden, not too big) and hands an app a copy.
    private fun openShim(paths: RuntimePaths) = """
        |#!${paths.bin}/sh
        |# Opens an http(s) URL in the Android browser, or a file from your home folder in an Android
        |# app of your choice (it gets a copy), via the Yurlungr app. termux-open's options are ignored.
        |target=
        |while [ ${'$'}# -gt 0 ]; do
        |  case "${'$'}1" in
        |    --content-type|--chooser-title) [ ${'$'}# -gt 1 ] && shift ;;
        |    --*) ;;
        |    *) target=${'$'}1 ;;
        |  esac
        |  shift
        |done
        |if [ -z "${'$'}target" ]; then echo "usage: ${'$'}{0##*/} <url or file>" >&2; exit 64; fi
        |case "${'$'}target" in
        |  [Hh][Tt][Tt][Pp]://*|[Hh][Tt][Tt][Pp][Ss]://*)
        |${submit(paths, "\"\$target\"", "open")}
        |    echo "Opening in browser: ${'$'}target" >&2
        |    exit 0 ;;
        |  file://*) target=${'$'}{target#file://} ;;
        |  *://*|mailto:*|tel:*|intent:*) echo "Only http(s) links and files can be opened: ${'$'}target" >&2; exit 65 ;;
        |esac
        |f=${'$'}(realpath "${'$'}target" 2>/dev/null) && [ -f "${'$'}f" ] || { echo "no such file: ${'$'}target" >&2; exit 66; }
        |${submit(paths, "\"\$f\"", "file")}
        |echo "Opening ${'$'}f on the phone: choose an app on screen." >&2
        |""".trimMargin()

    private fun installShim(paths: RuntimePaths) = """
        |#!${paths.bin}/sh
        |# Shows Android's install prompt for an APK built in the runtime.
        |if [ $# -ne 1 ]; then echo "usage: yurlungr-install <file.apk>" >&2; exit 64; fi
        |apk=${'$'}(realpath "${'$'}1") || exit 66
        |case "${'$'}apk" in *.apk) ;; *) echo "not an .apk: ${'$'}apk" >&2; exit 65 ;; esac
        |[ -f "${'$'}apk" ] || { echo "no such file: ${'$'}apk" >&2; exit 66; }
        |${submit(paths, "\"\$apk\"", "install")}
        |echo "Install prompt sent for ${'$'}apk: confirm it on screen (a notification, if Yurlungr is in the background)." >&2
        |""".trimMargin()

    private fun notifyShim(paths: RuntimePaths) = """
        |#!${paths.bin}/sh
        |# Shows a notification on this phone (a toast while the app is open): yurlungr-notify "title" ["text"]
        |if [ $# -lt 1 ] || [ $# -gt 2 ]; then echo "usage: yurlungr-notify <title> [text]" >&2; exit 64; fi
        |msg=${'$'}(printf '%s\n%s' "${'$'}1" "${'$'}{2:-}")
        |${submit(paths, "\"\$msg\"", "notify")}
        |""".trimMargin()

    private fun submit(paths: RuntimePaths, valueExpr: String, kind: String) = """
        |inbox='${paths.inbox}'
        |mkdir -p "${'$'}inbox"
        |tmp="${'$'}inbox/.${'$'}${'$'}.tmp"
        |printf '%s' $valueExpr > "${'$'}tmp" && mv "${'$'}tmp" "${'$'}inbox/${'$'}${'$'}-${'$'}(date +%s%N).$kind"
        """.trimMargin()

    private const val BEGIN = "<!-- yurlungr:begin (managed by the Yurlungr app; edits inside are overwritten) -->"
    private const val END = "<!-- yurlungr:end -->"

    /** The optional packs already installed, so agents know what's on the phone without probing. */
    private fun installedPacks(paths: RuntimePaths): String {
        val have = ToolSetup.entries.filter { it.optional && it.marker != null && File(paths.prefix, it.marker).exists() }
        return if (have.isEmpty()) "none yet (the user can add Python, build tools, Go, Rust and more in System checks)"
        else have.joinToString("; ") { it.blurb }
    }

    private fun agentGuide(paths: RuntimePaths) = """
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
        |- Open a URL in the phone's browser with `xdg-open <url>`; `xdg-open <file>` shows a file from the home
        |  folder (a PDF, an image, a page) in an app the user picks.
        |- Tell the user something finished with `yurlungr-notify "Title" "details"` (a phone notification).
        |- GitHub: use `git` and `gh`. The user signs in from the app's Projects screen (gh auth + git credential
        |  helper), so pushes work once they have. Don't ask for tokens or passwords.
        |- Optional toolchains installed from System checks: ${installedPacks(paths)}. Anything else: `pkg install <name>`.
        |- Java: ${jdkNote(paths)} ${'$'}JAVA_HOME is set everywhere. Gradle toolchains (`jvmToolchain(N)`) find
        |  installed JDKs; there's no JDK auto-download on Android. The user switches the default in the app
        |  (Settings → Development environment); don't change it with update-alternatives yourself.
        |- Per-user tool folders are on PATH: ~/.local/bin, ~/.cargo/bin, ~/go/bin, ~/.bun/bin, ~/.deno/bin,
        |  ~/.dotnet/tools, ~/.pub-cache/bin and others, so `cargo install`, `go install`, `uv tool install`,
        |  `bun add -g`, `pnpm add -g` (PNPM_HOME is set) and `dotnet tool install -g` work.
        |- The user reads your replies on a phone screen: keep them short.
        |$END
        |""".trimMargin()

    /** "JDK 21 (default), also 17." for the agent notes. */
    private fun jdkNote(paths: RuntimePaths): String {
        val all = Jdks.installed(paths.prefix)
        val current = Jdks.current(paths.prefix) ?: return "no JDK installed yet (System checks → Java & Kotlin)."
        val others = all.filter { it != current }.map { it.version }
        return "JDK ${current.version} (default)" + (if (others.isEmpty()) "." else ", also ${others.joinToString()}.")
    }

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
