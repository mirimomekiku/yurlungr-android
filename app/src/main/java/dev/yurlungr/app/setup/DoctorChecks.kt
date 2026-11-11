package dev.yurlungr.app.setup

import androidx.core.content.edit
import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.StatFs
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import dev.yurlungr.app.agents.agentStatuses
import dev.yurlungr.app.runtime.CommandRunner
import dev.yurlungr.app.runtime.RuntimeAssets
import dev.yurlungr.app.runtime.RuntimeEnv
import dev.yurlungr.app.runtime.RuntimePaths
import dev.yurlungr.app.runtime.SetupStatus
import dev.yurlungr.app.runtime.ToolSetup
import dev.yurlungr.app.runtime.ToolState
import dev.yurlungr.app.runtime.Toolchain
import java.io.File
import java.util.Locale

enum class CheckStatus { Pass, Warn, Fail, Info }

/** Fixes the UI knows how to offer; kept as data so checks stay free of UI/Activity code. */
enum class Fix(val setup: ToolSetup? = null) {
    InstallPython(ToolSetup.Python), InstallBuildTools(ToolSetup.BuildTools), InstallUtils(ToolSetup.Utils),
    InstallNativeLibs(ToolSetup.NativeLibs), InstallPyScience(ToolSetup.PyScience), InstallDebugging(ToolSetup.Debugging), InstallLanguageServers(ToolSetup.LanguageServers), InstallNetworking(ToolSetup.Networking), InstallSecurity(ToolSetup.Security), InstallDataTools(ToolSetup.DataTools), InstallTaskRunners(ToolSetup.TaskRunners), InstallDocs(ToolSetup.Docs), InstallArchives(ToolSetup.Archives), InstallOcr(ToolSetup.Ocr), InstallBrowser(ToolSetup.Browser),
    InstallCrystal(ToolSetup.Crystal), InstallNim(ToolSetup.Nim), InstallHaskell(ToolSetup.Haskell), InstallScala(ToolSetup.Scala), InstallDlang(ToolSetup.Dlang), InstallPerl(ToolSetup.Perl), InstallShell(ToolSetup.Shell), InstallCliTools(ToolSetup.CliTools), InstallServers(ToolSetup.Servers), InstallDevOps(ToolSetup.DevOps), InstallMedia(ToolSetup.Media), InstallLocalAi(ToolSetup.LocalAi), InstallDistros(ToolSetup.Distros),
    InstallEditors(ToolSetup.Editors), InstallGo(ToolSetup.Go), InstallRust(ToolSetup.Rust), InstallRuby(ToolSetup.Ruby),
    InstallJsRuntimes(ToolSetup.JsRuntimes), InstallJvm(ToolSetup.Jvm), InstallPhp(ToolSetup.Php), InstallDart(ToolSetup.Dart), InstallDotnet(ToolSetup.Dotnet), InstallDatabases(ToolSetup.Databases), InstallSwift(ToolSetup.Swift), InstallZig(ToolSetup.Zig), InstallElixir(ToolSetup.Elixir), InstallLua(ToolSetup.Lua),
    InstallBootstrap, Notifications, BatteryOptimization, PhantomProcesses, OpenTerminal, InstallAgents, InstallAndroidSdk,
    /** Git, gh, Node.js, termux-exec… (dev-tools-setup.sh). */
    InstallDevTools,
    /** Wipe and reinstall the base system (the shell or package manager is broken). Home is kept. */
    ReinstallBootstrap,
    /** Rewrite the app's scripts and shims in the runtime. */
    RepairRuntime,
    /** Pick the default JDK (Settings → Development environment). */
    ChooseJdk,
    /** Upgrade the installed packages (apt full-upgrade in a terminal). */
    UpdatePackages,
}

data class Check(
    val id: String,
    val title: String,
    val status: CheckStatus,
    val detail: String,
    val fix: Fix? = null,
    val required: Boolean = true,
    /** A log file worth opening next to this check (e.g. the last failed setup run). */
    val log: String? = null,
)

data class ReportLine(val status: String, val name: String, val detail: String)

/** Environment checks for the embedded runtime. Each is cheap and safe to re-run. */
object DoctorChecks {
    private const val GB = 1024L * 1024 * 1024
    private const val PREFS = "doctor"
    private const val KEY_PHANTOM_DONE = "phantom_fix_done"

    suspend fun runAll(context: Context): List<Check> {
        val paths = RuntimePaths(context)
        return buildList {
            add(device())
            add(storage(paths))
            add(appIdentity(context, paths))
            add(execAllowed(context))
            add(bootstrap(paths))
            if (paths.isBootstrapInstalled) {
                add(shell(paths))
                add(apt(paths))
                add(termuxExec(paths))
                RuntimeAssets.lastError?.let { add(runtimeScripts(it)) }
                RuntimeAssets.lastWarning?.let {
                    add(Check("runtime-notes", "App notes in your home folder", CheckStatus.Warn, "Couldn't update: $it. Agents still work.", required = false))
                }
                add(agents(paths))
                add(androidSdk(paths))
                jdk(paths)?.let(::add)
                updates(paths)?.let(::add)
                addAll(setupRuns(paths))
            }
            add(notifications(context))
            add(battery(context))
            add(phantomProcesses(context))
            add(cliReport(paths))
        }
    }

    fun markPhantomFixDone(context: Context, done: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean(KEY_PHANTOM_DONE, done) }
    }

    fun readReport(context: Context): List<ReportLine> {
        val file = RuntimePaths(context).spikeReport
        if (!file.isFile) return emptyList()
        return file.readLines().mapNotNull { line ->
            val parts = line.split('\t', limit = 3)
            if (parts.size == 3) ReportLine(parts[0], parts[1], parts[2]) else null
        }
    }

    private fun device(): Check {
        val arch = RuntimePaths.termuxArch()
        val detail = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · " +
            "${Build.MANUFACTURER} ${Build.MODEL} · ${Build.SUPPORTED_ABIS.firstOrNull()}"
        return Check(
            "device", "Device", if (arch != null) CheckStatus.Pass else CheckStatus.Fail,
            if (arch != null) detail else "$detail — no Termux packages for this ABI",
        )
    }

    private fun storage(paths: RuntimePaths): Check {
        val free = runCatching { StatFs(paths.filesDir.parentFile!!.path).availableBytes }.getOrDefault(0L)
        val freeGb = String.format(Locale.US, "%.1f", free.toDouble() / GB)
        return if (free >= 4 * GB) {
            Check("storage", "Free storage", CheckStatus.Pass, "$freeGb GB free")
        } else {
            Check(
                "storage", "Free storage", CheckStatus.Warn,
                "$freeGb GB free — agent CLIs, JDK and Gradle need roughly 3–4 GB",
                required = false,
            )
        }
    }

    private fun appIdentity(context: Context, paths: RuntimePaths): Check {
        if (!paths.dataDirMatches(context)) {
            return Check(
                "identity", "Data directory", CheckStatus.Fail,
                "App data is at ${context.filesDir}, not ${paths.filesDir}. Packages only work for the " +
                    "primary user — install outside work profiles / secondary users.",
            )
        }
        return if (paths.packageName == "com.termux") {
            Check("identity", "App ID", CheckStatus.Pass, "com.termux — official Termux bootstrap and repos are compatible")
        } else {
            Check(
                "identity", "App ID", CheckStatus.Fail,
                "${paths.packageName}: official packages are built for /data/data/com.termux. " +
                    "Use a bootstrap rebuilt with TERMUX_APP_PACKAGE=${paths.packageName}.",
            )
        }
    }

    private fun execAllowed(context: Context): Check {
        val target = context.applicationInfo.targetSdkVersion
        return if (target <= 28) {
            Check("exec", "Can run binaries", CheckStatus.Pass, "targetSdk $target allows exec() from app data")
        } else {
            Check("exec", "Can run binaries", CheckStatus.Fail, "targetSdk $target blocks exec() from app data (needs ≤ 28)")
        }
    }

    private fun bootstrap(paths: RuntimePaths): Check =
        if (paths.isBootstrapInstalled) {
            Check("bootstrap", "Base system", CheckStatus.Pass, "Installed at ${paths.prefix}")
        } else if (paths.hasBaseSystem && !dev.yurlungr.app.setup.BootstrapJob.isRunning) {
            // Files are there but the install never finished: it must be redone, not used.
            Check(
                "bootstrap", "Base system", CheckStatus.Fail,
                "The last install didn't finish, so the base system may be incomplete. Reinstall it (your home folder is kept).",
                fix = Fix.ReinstallBootstrap,
            )
        } else {
            Check(
                "bootstrap", "Base system", CheckStatus.Fail,
                "Not installed. Downloads the latest Termux bootstrap (~30 MB) from GitHub.",
                fix = Fix.InstallBootstrap,
            )
        }

    private suspend fun shell(paths: RuntimePaths): Check {
        val r = CommandRunner.shell(paths, "echo \"\$(uname -m) · bash \$BASH_VERSION\"", timeoutMs = 15_000)
        return Check(
            "shell", "Shell runs", if (r.ok) CheckStatus.Pass else CheckStatus.Fail,
            if (r.ok) r.firstLine else r.describe("bash") + "\n" + REINSTALL_NOTE,
            fix = if (r.ok) null else Fix.ReinstallBootstrap,
        )
    }

    private suspend fun apt(paths: RuntimePaths): Check {
        val r = CommandRunner.shell(paths, "apt --version", timeoutMs = 15_000)
        return Check(
            "apt", "Package manager", if (r.ok) CheckStatus.Pass else CheckStatus.Fail,
            if (r.ok) r.firstLine else r.describe("apt") + "\n" + REINSTALL_NOTE,
            fix = if (r.ok) null else Fix.ReinstallBootstrap,
        )
    }

    private const val REINSTALL_NOTE =
        "Reinstalling the base system keeps your home folder and projects; installed packages and agents need setting up again."

    private fun termuxExec(paths: RuntimePaths): Check {
        val lib = RuntimeEnv.termuxExecLib(paths)
        return if (lib != null) {
            Check("termux-exec", "Shebang fixups (termux-exec)", CheckStatus.Pass, lib.name)
        } else {
            // Every npm CLI (the agents' ACP adapters included) starts with #!/usr/bin/env node.
            Check(
                "termux-exec", "Shebang fixups (termux-exec)", CheckStatus.Fail,
                "Missing: agents and other scripts starting with #!/usr/bin/env won't start. Installs with the developer tools.",
                fix = Fix.InstallDevTools,
            )
        }
    }

    private fun runtimeScripts(error: String): Check = Check(
        "runtime-scripts", "App scripts in the runtime", CheckStatus.Fail,
        "Couldn't update them at launch: $error",
        fix = Fix.RepairRuntime,
    )

    /**
     * Tools from [Toolchain], probed by actually running them. Slower than the other checks, so
     * the screen asks for it explicitly rather than on every resume. Agent and SDK tools are only
     * probed once their setup has installed them.
     */
    suspend fun tools(paths: RuntimePaths): List<Check> {
        if (!paths.isBootstrapInstalled) return emptyList()
        val wanted = Toolchain.tools.filter { t -> t.onlyIf == null || File(paths.prefix, t.onlyIf).exists() }
        val r = CommandRunner.shell(paths, Toolchain.probeScript(wanted), timeoutMs = Toolchain.probeBudgetMs(wanted.size, wanted.maxOfOrNull { it.timeoutS } ?: Toolchain.PROBE_TIMEOUT_S))
        if (!r.ok && r.output.isBlank()) {
            // Not by itself a sign of a broken base system (the shell check says if it is): no reinstall offered.
            return listOf(Check("tools", "Tools", CheckStatus.Warn, "Couldn't check the tools: " + r.describe("probe") + ". Tap refresh to retry.", required = false))
        }
        return Toolchain.parseProbe(r.output, wanted).map(::toolCheck)
    }

    internal fun toolCheck(state: ToolState): Check {
        val t = state.tool
        val fix = fixFor(t.setup)
        val id = "tool-${t.id}"
        return when {
            state.ok -> Check(id, t.name, CheckStatus.Pass, state.firstLine, required = t.essential)
            state.missing && t.setup.optional -> Check(
                id, t.name, CheckStatus.Info, "Not installed. Tap Install for ${t.setup.title}.", fix = fix, required = false,
            )
            state.missing && t.onlyIf == null -> Check(
                id, t.name, if (t.essential) CheckStatus.Fail else CheckStatus.Warn,
                "Not installed. ${t.setup.title} setup installs it.", fix = fix, required = t.essential,
            )
            state.timedOut -> Check(
                id, t.name, CheckStatus.Warn,
                "Didn't answer within ${Toolchain.PROBE_TIMEOUT_S} s. Check again; if it persists, rerun ${t.setup.title} setup.",
                fix = fix, required = false,
            )
            else -> Check(
                id, t.name, CheckStatus.Fail,
                // 126/127 from an installed tool: a broken binary, or a library missing after an upgrade.
                (if (state.exitCode == 126 || state.exitCode == 127) "Installed but can't run" else "Fails (exit ${state.exitCode})") +
                    (state.firstLine.takeIf { it.isNotBlank() }?.let { ": $it" } ?: "") + ". Rerun ${t.setup.title} setup to repair it.",
                fix = fix, required = t.essential,
            )
        }
    }

    private fun fixFor(setup: ToolSetup) = when (setup) {
        ToolSetup.DevTools -> Fix.InstallDevTools
        ToolSetup.Agents -> Fix.InstallAgents
        ToolSetup.AndroidSdk -> Fix.InstallAndroidSdk
        else -> Fix.entries.first { it.setup == setup }
    }

    /** The last run of each setup script that failed, from the status file the script writes on exit. */
    private fun setupRuns(paths: RuntimePaths): List<Check> = ToolSetup.entries.mapNotNull { setup ->
        val name = setup.script?.removeSuffix(".sh") ?: return@mapNotNull null
        val status = SetupStatus.read(File(paths.setupStatusDir, "$name.status")) ?: return@mapNotNull null
        if (status.exitCode == 0) return@mapNotNull null
        val running = status.unfinished && SetupStatus.isAlive(status.pid)
        Check(
            "setup-$name", "Last ${setup.title.lowercase()} setup",
            if (running) CheckStatus.Info else CheckStatus.Warn,
            SetupStatus.summary(status, running),
            fix = if (running) null else fixFor(setup), required = false, log = status.log,
        )
    }

    private fun notifications(context: Context): Check {
        val enabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
        return Check(
            "notifications", "Notifications", if (enabled) CheckStatus.Pass else CheckStatus.Warn,
            if (enabled) "Enabled" else "Disabled — you won't see runtime status or agent alerts",
            fix = if (enabled) null else Fix.Notifications,
            required = false,
        )
    }

    private fun battery(context: Context): Check {
        val ignoring = context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
        return Check(
            "battery", "Battery optimization", if (ignoring) CheckStatus.Pass else CheckStatus.Warn,
            if (ignoring) "Unrestricted" else "Restricted — Android may kill long-running agents in the background",
            fix = if (ignoring) null else Fix.BatteryOptimization,
            required = false,
        )
    }

    private fun phantomProcesses(context: Context): Check {
        val sdk = Build.VERSION.SDK_INT
        if (sdk < 31) return Check("phantom", "Phantom process killer", CheckStatus.Pass, "Not present before Android 12")

        // Android 12L+ exposes this as a global setting ("Disable child process restrictions").
        val monitor = runCatching {
            Settings.Global.getString(context.contentResolver, "settings_enable_monitor_phantom_procs")
        }.getOrNull()
        if (monitor == "false") {
            return Check("phantom", "Phantom process killer", CheckStatus.Pass, "Disabled (child process restrictions off)")
        }
        val markedDone = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_PHANTOM_DONE, false)
        if (markedDone) {
            return Check(
                "phantom", "Phantom process killer", CheckStatus.Pass,
                "Marked as fixed by you (Android 12 doesn't let apps verify this)",
                fix = Fix.PhantomProcesses,
            )
        }
        return Check(
            "phantom", "Phantom process killer", CheckStatus.Warn,
            "Android may kill processes beyond 32 (signal 9) during npm/Gradle/agent runs. One-time fix needed.",
            fix = Fix.PhantomProcesses,
            required = false,
        )
    }

    private fun agents(paths: RuntimePaths): Check {
        // Claude Code (glibc build) and Antigravity only exist for 64-bit ARM: don't expect them elsewhere.
        val arm64 = RuntimePaths.termuxArch() == "aarch64"
        // Optional agents (installed one by one) aren't expected: only the core ones the setup installs.
        val statuses = agentStatuses(paths).filter {
            !it.agent.isOptional &&
                (arm64 || it.agent !in setOf(dev.yurlungr.app.agents.Agent.Claude, dev.yurlungr.app.agents.Agent.Antigravity))
        }
        val missing = statuses.filter { !it.installed }.map { it.agent.displayName }
        return if (missing.isEmpty()) {
            val signedOut = statuses.filter { it.signedIn == false }.map { it.agent.displayName }
            Check(
                "agents", "Coding agents", CheckStatus.Pass,
                "All agents installed" +
                    if (signedOut.isEmpty()) " and signed in" else " · not signed in: ${signedOut.joinToString()}",
                required = false,
            )
        } else {
            Check(
                "agents", "Coding agents", CheckStatus.Warn,
                "Not installed: ${missing.joinToString()}",
                fix = Fix.InstallAgents,
                required = false,
            )
        }
    }

    private fun androidSdk(paths: RuntimePaths): Check =
        if (paths.isAndroidSdkInstalled) {
            Check(
                "android-sdk", "Android SDK (build APKs)", CheckStatus.Pass,
                "Platform 36 + native build tools at ~/android-sdk",
                required = false,
            )
        } else {
            Check(
                "android-sdk", "Android SDK (build APKs)", CheckStatus.Info,
                "Not set up. Needed to build Android apps on this phone (~600 MB).",
                fix = Fix.InstallAndroidSdk,
                required = false,
            )
        }

    /** Package updates known from the lists already on the phone (no download), when there are any. */
    private suspend fun updates(paths: RuntimePaths): Check? {
        val n = CommandRunner.shell(paths, dev.yurlungr.app.runtime.Storage.UPGRADABLE_SCRIPT, timeoutMs = 30_000)
            .output.trim().lines().lastOrNull()?.trim()?.toIntOrNull() ?: return null
        if (n <= 0) return null
        return Check(
            "updates", "Package updates", CheckStatus.Info,
            "$n package${if (n == 1) "" else "s"} can be upgraded. Update all refreshes the lists first.",
            fix = Fix.UpdatePackages,
            required = false,
        )
    }

    /** Which JDK is the default, and the others installed; Change opens Development environment. */
    private fun jdk(paths: RuntimePaths): Check? {
        val current = dev.yurlungr.app.runtime.Jdks.current(paths.prefix) ?: return null
        val others = dev.yurlungr.app.runtime.Jdks.installed(paths.prefix).filter { it != current }.map { it.version }
        val how = if (dev.yurlungr.app.runtime.Jdks.isManual(paths.prefix)) "chosen" else "newest"
        return Check(
            "jdk", "Default JDK", CheckStatus.Info,
            "Java ${current.version} ($how)" + (if (others.isEmpty()) "" else " · also ${others.joinToString()}") +
                " · JAVA_HOME and Gradle toolchains set",
            fix = Fix.ChooseJdk,
            required = false,
        )
    }

    private fun cliReport(paths: RuntimePaths): Check {
        if (!paths.spikeReport.isFile) {
            return Check(
                "cli-report", "Agent CLI compatibility", CheckStatus.Info,
                "Not run yet. In the terminal: bash ~/spike-test.sh",
                fix = if (paths.isBootstrapInstalled) Fix.OpenTerminal else null,
                required = false,
            )
        }
        val lines = paths.spikeReport.readLines()
        val pass = lines.count { it.startsWith("PASS\t") }
        val fail = lines.count { it.startsWith("FAIL\t") }
        return Check(
            "cli-report", "Agent CLI compatibility", if (fail == 0) CheckStatus.Pass else CheckStatus.Warn,
            "$pass passed · $fail failed — details below",
            required = false,
        )
    }

    /** ADB/manual steps for the phantom process killer, by Android version. */
    fun phantomFixInstructions(): Pair<String, String?> = when {
        Build.VERSION.SDK_INT >= 34 ->
            "Developer options → enable “Disable child process restrictions”." to null
        Build.VERSION.SDK_INT >= 32 ->
            "Run once from a computer with USB debugging enabled:" to
                "adb shell settings put global settings_enable_monitor_phantom_procs false"
        else ->
            "Android 12: run once from a computer with USB debugging enabled (survives reboots):" to
                "adb shell \"/system/bin/device_config set_sync_disabled_for_tests persistent; " +
                "/system/bin/device_config put activity_manager max_phantom_processes 2147483647\""
    }
}
