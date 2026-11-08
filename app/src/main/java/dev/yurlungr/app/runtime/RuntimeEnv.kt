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

    fun map(paths: RuntimePaths, cwd: File = paths.home): Map<String, String> = buildMap {
        put("TERM", "xterm-256color")
        put("COLORTERM", "truecolor")
        put("LANG", "en_US.UTF-8")
        put("HOME", paths.home.path)
        put("PREFIX", paths.prefix.path)
        put("TMPDIR", paths.tmp.path)
        put("PATH", "${paths.shimBin.path}:${paths.bin.path}")
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
        if (paths.androidSdk.isDirectory) {
            put("ANDROID_HOME", paths.androidSdk.path)
            put("ANDROID_SDK_ROOT", paths.androidSdk.path)
        }
        // termux-exec rewrites shebangs like #!/usr/bin/env to $PREFIX paths. `login` sets
        // this too; we set it so non-login commands (setup checks, scripts) get it as well.
        termuxExecLib(paths)?.let { put("LD_PRELOAD", it.path) }
    }

    fun array(paths: RuntimePaths, cwd: File = paths.home): Array<String> =
        map(paths, cwd).map { (k, v) -> "$k=$v" }.toTypedArray()

    /** termux-exec 2.x renamed the preload library; accept either. */
    fun termuxExecLib(paths: RuntimePaths): File? =
        listOf("libtermux-exec-ld-preload.so", "libtermux-exec.so")
            .map { File(paths.prefix, "lib/$it") }
            .firstOrNull { it.isFile }
}
