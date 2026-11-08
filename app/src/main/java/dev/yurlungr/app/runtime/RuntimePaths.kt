package dev.yurlungr.app.runtime

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.system.Os
import android.system.OsConstants
import java.io.File

/**
 * Filesystem layout of the embedded runtime, mirroring Termux:
 * `$FILES/usr` is `$PREFIX`, `$FILES/home` is `$HOME`.
 *
 * Paths use the hardcoded `/data/data/<pkg>` form rather than [Context.getFilesDir]
 * (`/data/user/0/...`) because the packages are compiled against that exact string.
 */
class RuntimePaths(context: Context) {
    val packageName: String = context.packageName

    @SuppressLint("SdCardPath") // deliberate, see class doc
    val dataDir = File("/data/data/$packageName")
    val filesDir = File(dataDir, "files")
    val prefix = File(filesDir, "usr")
    val staging = File(filesDir, "usr-staging")
    val home = File(filesDir, "home")
    val tmp = File(prefix, "tmp")
    val bin = File(prefix, "bin")
    val bash = File(bin, "bash")
    val login = File(bin, "login")
    val spikeReport = File(home, "spike-report.txt")

    /** App-owned helpers inside the runtime (agent wrappers, setup scripts). */
    val libexec = File(prefix, "libexec/yurlungr")
    /** Shims prepended to PATH: xdg-open & friends route URLs to the app. */
    val shimBin = File(libexec, "bin")
    /** Shims drop requests here; [RuntimeBridge] watches it. */
    val inbox = File(prefix, "var/run/yurlungr/inbox")
    val projects = File(home, "projects")
    val androidSdk = File(home, "android-sdk")

    val isAndroidSdkInstalled: Boolean
        get() = File(androidSdk, "platforms/android-36/android.jar").isFile &&
            File(androidSdk, "build-tools/36.0.0/aapt2").exists()

    val isBootstrapInstalled: Boolean
        get() = bash.isFile && File(bin, "apt").isFile

    /**
     * False when the hardcoded path isn't this app's real data dir (e.g. a work profile or secondary user).
     * Compares inodes: `/data/user/0` is a bind mount (not a symlink) on newer Android, so paths differ
     * even when both names reach the same directory.
     */
    fun dataDirMatches(context: Context): Boolean = runCatching {
        val actual = Os.stat(context.filesDir.path)
        val expected = Os.stat(filesDir.path)
        actual.st_dev == expected.st_dev && actual.st_ino == expected.st_ino
    }.getOrDefault(false)

    companion object {
        /** Termux architecture name for this device's primary ABI, or null if unsupported. */
        fun termuxArch(): String? = when (Build.SUPPORTED_ABIS.firstOrNull()) {
            "arm64-v8a" -> "aarch64"
            "armeabi-v7a" -> "arm"
            "x86_64" -> "x86_64"
            "x86" -> "i686"
            else -> null
        }

        /** Recursive delete that never follows symlinks (the prefix may link into $HOME or shared storage). */
        fun deleteNoFollow(file: File) {
            val isLink = runCatching { OsConstants.S_ISLNK(Os.lstat(file.path).st_mode) }.getOrDefault(false)
            if (!isLink && file.isDirectory) file.listFiles()?.forEach(::deleteNoFollow)
            file.delete()
        }
    }
}
