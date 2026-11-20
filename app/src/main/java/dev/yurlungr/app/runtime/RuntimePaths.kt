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

    /**
     * The application context (never an activity, so keeping it can't leak one): what
     * [RuntimeEnv] needs to ask [dev.yurlungr.app.projects.ProjectTrust] whether a project's own
     * `.venv` may go first on PATH.
     */
    val appContext: Context = context.applicationContext ?: context

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

    /** Setup scripts write `<name>.status` here when they exit (see [SetupStatus]). */
    val setupStatusDir = File(prefix, "var/lib/yurlungr/setup")

    /**
     * Exists from the start of a bootstrap install until it fully completes (extract, second
     * stage, app scripts). Outside the prefix, which the install replaces. Its presence means an
     * install was interrupted, even though bash and apt may already be in place.
     */
    val bootstrapIncomplete = File(filesDir, ".yurlungr-bootstrap-incomplete")

    val isAndroidSdkInstalled: Boolean
        get() = File(androidSdk, "platforms/${Toolchain.SDK_PLATFORM}/android.jar").isFile &&
            File(androidSdk, "build-tools/${Toolchain.SDK_BUILD_TOOLS}/aapt2").exists()

    /** The base system's core files exist (it may still be mid-install; see [isBootstrapInstalled]). */
    val hasBaseSystem: Boolean
        get() = bash.isFile && File(bin, "apt").isFile

    /** Installed and the install finished: an interrupted one must be redone, not used. */
    val isBootstrapInstalled: Boolean
        get() = hasBaseSystem && !bootstrapIncomplete.exists()

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

        /**
         * Recursive delete that never follows symlinks (the prefix may link into $HOME or shared
         * storage). [makeWritable]: give each folder owner write access first, or the files in a
         * read-only one (some packages install those) can't be deleted.
         */
        fun deleteNoFollow(file: File, makeWritable: Boolean = false) {
            val isLink = runCatching { OsConstants.S_ISLNK(Os.lstat(file.path).st_mode) }.getOrDefault(false)
            if (!isLink && file.isDirectory) {
                // Only real folders reach chmod, which would follow a link.
                if (makeWritable) runCatching { Os.chmod(file.path, "700".toInt(8)) }
                file.listFiles()?.forEach { deleteNoFollow(it, makeWritable) }
            }
            file.delete()
        }
    }
}
