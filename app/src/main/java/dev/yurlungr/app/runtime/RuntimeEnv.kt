package dev.yurlungr.app.runtime

import android.os.Build
import java.io.File

/** Environment for processes in the embedded runtime, modelled on Termux's `TermuxShellUtils.buildEnvironment`. */
object RuntimeEnv {
    /** Termux version reported to package scripts that check for it. */
    private const val TERMUX_COMPAT_VERSION = "0.118.3"

    private val ANDROID_PASSTHROUGH = listOf(
        "BOOTCLASSPATH", "ANDROID_ROOT", "ANDROID_DATA", "EXTERNAL_STORAGE",
        // Needed by /system/bin/am and friends on Android 10+.
        "ANDROID_ART_ROOT", "DEX2OATBOOTCLASSPATH", "ANDROID_I18N_ROOT",
        "ANDROID_RUNTIME_ROOT", "ANDROID_TZDATA_ROOT",
    )

    fun map(paths: RuntimePaths, cwd: File = paths.home): Map<String, String> {
        val env = base(paths, cwd)
        // In a Python project with a .venv, everything runs as if it were activated: only in a
        // project the user trusts (a cloned repository's committed .venv/bin would otherwise
        // shadow git, node and python for everything run there).
        val venv = runCatching {
            ProjectVenv.find(cwd, paths.projects, paths.bin) { dir -> dev.yurlungr.app.projects.ProjectTrust.isTrusted(paths.appContext, dir) }
        }.getOrNull() ?: return env
        return ProjectVenv.activate(env, venv)
    }

    private fun base(paths: RuntimePaths, cwd: File): Map<String, String> = buildMap {
        put("TERM", "xterm-256color")
        put("COLORTERM", "truecolor")
        put("LANG", "en_US.UTF-8")
        put("HOME", paths.home.path)
        put("PREFIX", paths.prefix.path)
        put("TMPDIR", paths.tmp.path)
        // Tools installed per user (cargo install, go install, uv tool, bun add -g…) come after
        // the runtime's own bin, so they can never shadow the git and node the app relies on.
        put("PATH", (listOf(paths.shimBin.path, paths.bin.path) + userBins(paths.home).map { it.path }).joinToString(":"))
        put("PNPM_HOME", File(paths.home, PNPM_HOME).path)
        put("GOPATH", File(paths.home, "go").path)
        put("CARGO_HOME", File(paths.home, ".cargo").path)
        // The same JDK `java` runs, as login shells get from profile.d/java.sh: agents, scripts
        // and command terminals (none of them login shells) agree with the terminal.
        Jdks.current(paths.prefix)?.let { put("JAVA_HOME", it.home.path) }
        // .NET global tools look for the runtime here; Termux doesn't install it where they expect.
        File(paths.prefix, "bin/dotnet").takeIf { it.exists() }
            ?.let { runCatching { it.canonicalFile.parentFile }.getOrNull() }
            ?.let { put("DOTNET_ROOT", it.path) }
        put("BROWSER", File(paths.shimBin, "yurlungr-open").path)
        put("PWD", cwd.path)
        put("TERMUX_VERSION", TERMUX_COMPAT_VERSION)
        put("TERMUX__ROOTFS_DIR", paths.filesDir.path)
        put("TERMUX__HOME", paths.home.path)
        put("TERMUX__PREFIX", paths.prefix.path)
        put("TERMUX_APP__DATA_DIR", paths.dataDir.path)
        put("TERMUX_APP__LEGACY_DATA_DIR", paths.dataDir.path)
        put("ANDROID__BUILD_VERSION_SDK", Build.VERSION.SDK_INT.toString())
        put("YURLUNGR", "1")
        for (key in ANDROID_PASSTHROUGH) System.getenv(key)?.let { put(key, it) }
        // Always, not only once the SDK exists: the shell left open after the SDK setup (started
        // before the folder existed) must have it too. Without an SDK, builds fail the same way either way.
        put("ANDROID_HOME", paths.androidSdk.path)
        put("ANDROID_SDK_ROOT", paths.androidSdk.path)
        // termux-exec rewrites shebangs like #!/usr/bin/env to $PREFIX paths. `login` sets
        // this too; we set it so non-login commands (setup checks, scripts) get it as well.
        termuxExecLib(paths)?.let { put("LD_PRELOAD", it.path) }
    }

    const val PNPM_HOME = ".local/share/pnpm"

    /** Where language tools put the commands they install for the user, relative to home. */
    val USER_BINS = listOf(
        ".local/bin", ".cargo/bin", "go/bin", ".bun/bin", ".deno/bin", ".dotnet/tools", ".pub-cache/bin",
        ".mix/escripts", ".luarocks/bin", ".config/composer/vendor/bin", ".yarn/bin", PNPM_HOME, ".nimble/bin", ".cabal/bin",
    )

    fun userBins(home: File): List<File> = USER_BINS.map { File(home, it) }

    /**
     * Shell code that loads the scripts in `$PREFIX/etc/profile.d` (what packages add for login shells, such
     * as JAVA_HOME) into a non-login bash, silently: for agents' stdout is their protocol.
     */
    const val PROFILE_SNIPPET = "{ for _f in \"\$PREFIX\"/etc/profile.d/*.sh; do [ -r \"\$_f\" ] && . \"\$_f\"; done; } >/dev/null 2>&1 </dev/null; unset _f"

    fun array(paths: RuntimePaths, cwd: File = paths.home): Array<String> =
        map(paths, cwd).map { (k, v) -> "$k=$v" }.toTypedArray()

    /** termux-exec 2.x renamed the preload library; accept either. */
    fun termuxExecLib(paths: RuntimePaths): File? =
        listOf("libtermux-exec-ld-preload.so", "libtermux-exec.so")
            .map { File(paths.prefix, "lib/$it") }
            .firstOrNull { it.isFile }
}
