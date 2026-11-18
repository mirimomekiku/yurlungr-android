@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.yurlungr.app.setup

import androidx.core.net.toUri
import dev.yurlungr.app.runtime.ToolSetup
import dev.yurlungr.app.runtime.Toolchain
import dev.yurlungr.app.ui.FlowBar
import dev.yurlungr.app.ui.SmoothProgressBar
import dev.yurlungr.app.ui.WaveDots
import dev.yurlungr.app.ui.SkeletonCard
import android.text.format.DateUtils
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.IconButton
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.draw.rotate
import dev.yurlungr.app.ui.AppColors
import dev.yurlungr.app.ui.Group
import dev.yurlungr.app.ui.GroupRow
import dev.yurlungr.app.ui.GroupScope
import dev.yurlungr.app.ui.Ph
import dev.yurlungr.app.ui.PhIcon
import dev.yurlungr.app.ui.ScreenHeader
import dev.yurlungr.app.ui.SectionLabel
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Check ids by area, in display order. Unknown ids land in "Other". */
private val AREAS = listOf(
    "Phone" to listOf("device", "storage"),
    "Linux runtime" to listOf("identity", "exec", "bootstrap", "shell", "apt", "termux-exec", "runtime-scripts", "runtime-notes", "checks"),
    "Developer tools" to listOf("tools", "tool-git", "tool-gh", "tool-node", "tool-npm", "tool-rg", "setup-dev-tools-setup"),
    "Tools" to listOf(
        "agents", "tool-gemini", "tool-codex", "tool-codex-acp", "tool-claude", "tool-claude-agent-acp", "tool-agy", "setup-agents-setup",
        "tool-qwen", "tool-kimi", "tool-auggie", "tool-commandcode", "tool-copilot", "tool-goose", "tool-opencode",
        "tool-mcode", "tool-pi", "tool-pi-acp", "tool-qodercli", "tool-cline", "tool-kilo", "tool-cursor-agent", "tool-amp", "tool-amp-acp",
        "tool-vibe-acp", "tool-kiro-cli", "tool-fast-agent-acp", "tool-minion-code",
        "android-sdk", "tool-java", "jdk", "tool-aapt2", "updates", "setup-android-sdk-setup", "cli-report",
    ),
    // One group per optional pack, each with its own Install button.
    *ToolSetup.entries.filter { it.optional }.map { pack ->
        "${pack.title} (optional)" to Toolchain.tools.filter { it.setup == pack }.map { "tool-${it.id}" } + "setup-${pack.script!!.removeSuffix(".sh")}"
    }.toTypedArray(),
    "Background" to listOf("notifications", "battery", "phantom"),
)

private val SHORT_NAMES = mapOf(
    "identity" to "App ID", "exec" to "binaries", "bootstrap" to "base system", "shell" to "shell",
    "apt" to "apt", "termux-exec" to "termux-exec",
)

/**
 * System checks, grouped by area (Phone · Linux runtime · Tools · Background). An area where
 * everything passes folds into one "N checks passed" row; problems stay open, in amber or red,
 * with their fix. Also the first-run screen: then it offers to install the Linux environment.
 */
@Composable
fun SetupScreen(
    vm: SetupViewModel,
    /** Opened at launch (no runtime yet) rather than from Settings. */
    firstRun: Boolean,
    onOpenTerminal: () -> Unit,
    onInstallAgents: () -> Unit,
    onInstallAndroidSdk: () -> Unit,
    onInstallDevTools: () -> Unit,
    onInstallSetup: (ToolSetup) -> Unit,
    onHome: () -> Unit,
    onDevices: () -> Unit,
    onDevEnvironment: () -> Unit = {},
    onUpdatePackages: () -> Unit = {},
) {
    val context = LocalContext.current
    var logPath by remember { mutableStateOf<String?>(null) }
    var showPhantomHelp by remember { mutableStateOf(false) }
    var confirmReinstall by remember { mutableStateOf(false) }
    // Opening the screen runs the full checks, tools included (resumes elsewhere only run the quick ones).
    LaunchedEffect(Unit) { vm.refresh(deep = true) }
    var showReport by remember { mutableStateOf(false) }
    val expanded = remember { mutableStateListOf<String>() } // areas unfolded by the user
    val installing = vm.install is InstallState.Running

    fun runFix(fix: Fix) = when (fix) {
        Fix.InstallBootstrap -> vm.installBootstrap()
        Fix.Notifications -> openNotificationSettings(context)
        Fix.BatteryOptimization -> requestBatteryExemption(context)
        Fix.PhantomProcesses -> showPhantomHelp = true
        Fix.OpenTerminal -> onOpenTerminal()
        Fix.InstallAgents -> onInstallAgents()
        Fix.InstallAndroidSdk -> onInstallAndroidSdk()
        Fix.InstallDevTools -> onInstallDevTools()
        Fix.InstallPython, Fix.InstallBuildTools, Fix.InstallUtils, Fix.InstallEditors,
        Fix.InstallGo, Fix.InstallRust, Fix.InstallRuby, Fix.InstallJsRuntimes, Fix.InstallJvm, Fix.InstallPhp, Fix.InstallDart, Fix.InstallDotnet, Fix.InstallDatabases, Fix.InstallSwift, Fix.InstallZig, Fix.InstallElixir, Fix.InstallLua, Fix.InstallCrystal, Fix.InstallNim, Fix.InstallHaskell, Fix.InstallScala, Fix.InstallDlang, Fix.InstallPerl, Fix.InstallShell, Fix.InstallCliTools, Fix.InstallServers, Fix.InstallDevOps, Fix.InstallMedia, Fix.InstallLocalAi, Fix.InstallDistros, Fix.InstallNativeLibs, Fix.InstallPyScience, Fix.InstallDebugging, Fix.InstallLanguageServers, Fix.InstallNetworking, Fix.InstallSecurity, Fix.InstallDataTools, Fix.InstallTaskRunners, Fix.InstallDocs, Fix.InstallArchives, Fix.InstallOcr, Fix.InstallBrowser,
        Fix.InstallUbuntu, Fix.InstallFlutter -> onInstallSetup(requireNotNull(fix.setup))
        Fix.ReinstallBootstrap -> confirmReinstall = true
        Fix.RepairRuntime -> vm.repairRuntime()
        Fix.ChooseJdk -> onDevEnvironment()
        Fix.UpdatePackages -> onUpdatePackages()
    }

    val byId = vm.checks.associateBy { it.id }
    val known = AREAS.flatMap { it.second }.toSet()
    val areas = AREAS.map { (name, ids) -> name to ids.mapNotNull(byId::get) } +
        listOf("Other" to vm.checks.filter { it.id !in known })
    val passing = vm.checks.count { it.status == CheckStatus.Pass }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding(),
    ) {
        ScreenHeader(if (firstRun && !vm.bootstrapInstalled) "Set up" else "System checks", onHome) {
            IconButton(onClick = { vm.refresh(deep = true, force = true) }, enabled = !vm.checking && !installing) {
                if (vm.checking) WaveDots(dotSize = 4.dp) else PhIcon(Ph.ArrowClockwise, "Run checks again", size = 20.dp)
            }
        }
        // Pull down to run every check again (tools included), like the refresh button.
        val pullState = rememberPullToRefreshState()
        var pulled by remember { mutableStateOf(false) }
        LaunchedEffect(vm.checking) { if (!vm.checking) pulled = false }
        PullToRefreshBox(
            isRefreshing = pulled && vm.checking,
            onRefresh = {
                if (!installing) {
                    pulled = true
                    vm.refresh(deep = true, force = true)
                }
            },
            state = pullState,
            modifier = Modifier.fillMaxWidth().weight(1f),
            indicator = {
                PullToRefreshDefaults.IndicatorBox(
                    state = pullState,
                    isRefreshing = pulled && vm.checking,
                    modifier = Modifier.align(Alignment.TopCenter),
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ) {
                    if (pulled && vm.checking) WaveDots(Modifier.align(Alignment.Center), dotSize = 4.dp)
                    else PhIcon(Ph.ArrowClockwise, "Pull to run the checks again", Modifier.align(Alignment.Center), size = 18.dp)
                }
            },
        ) {
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item {
                Text(
                    when {
                        vm.checks.isEmpty() -> "Checking this phone…"
                        else -> (if (vm.checkedAt > 0) "Checked " + DateUtils.getRelativeTimeSpanString(vm.checkedAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString().replaceFirstChar { it.lowercase() } + " · " else "") +
                            "$passing of ${vm.checks.size} passing"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }

            when (val state = vm.install) {
                is InstallState.Running -> item { InstallProgressCard(state) }
                is InstallState.Failed -> item { InstallFailedCard(state, onRetry = vm::installBootstrap) }
                InstallState.Idle -> if (!vm.bootstrapInstalled && vm.checks.isNotEmpty()) item {
                    SetupCard(onInstall = vm::installBootstrap, onDevices = onDevices)
                }
            }

            if (vm.checks.isEmpty()) items(4) { SkeletonCard(lines = 2) }
            for ((area, checks) in areas) {
                if (checks.isEmpty()) continue
                item(key = "label:$area") { SectionLabel(area) }
                item(key = "group:$area") {
                    val allPass = checks.all { it.status == CheckStatus.Pass }
                    val folded = allPass && checks.size >= 3 && area !in expanded
                    Group(Modifier.animateContentSize()) {
                        if (folded) {
                            GroupRow(
                                "${checks.size} checks passed",
                                subtitle = checks.joinToString(", ") { SHORT_NAMES[it.id] ?: it.title },
                                onClick = { expanded += area },
                                leading = { StatusIcon(CheckStatus.Pass) },
                                trailing = { PhIcon(Ph.CaretDown, "Show all", size = 14.dp, tint = MaterialTheme.colorScheme.outline) },
                            )
                        } else {
                            for (check in checks) {
                                CheckRow(
                                    check,
                                    fixEnabled = !installing,
                                    onFix = { check.fix?.let(::runFix) },
                                    extra = when {
                                        check.id == "cli-report" && vm.report.isNotEmpty() -> {
                                            { TextButton(onClick = { showReport = !showReport }) { Text(if (showReport) "Hide" else "Report") } }
                                        }
                                        check.log != null -> {
                                            { TextButton(onClick = { logPath = check.log }) { Text("Log") } }
                                        }
                                        else -> null
                                    },
                                )
                            }
                            if (allPass && checks.size >= 3) {
                                GroupRow("Fold", onClick = { expanded -= area }, trailing = { PhIcon(Ph.CaretDown, null, size = 14.dp, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.rotate(180f)) })
                            }
                        }
                    }
                }
                if (area == "Tools" && showReport && vm.report.isNotEmpty()) {
                    item(key = "report") {
                        Group {
                            for (line in vm.report) GroupRow(
                                line.name,
                                subtitle = line.detail,
                                leading = { StatusIcon(if (line.status == "PASS") CheckStatus.Pass else if (line.status == "FAIL") CheckStatus.Fail else CheckStatus.Info, size = 16.dp) },
                                trailing = {},
                            )
                        }
                    }
                }
            }
        }
        }

        if (firstRun) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp, top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onHome, enabled = vm.bootstrapInstalled && !installing, modifier = Modifier.weight(1f).height(48.dp)) { Text("Start a chat") }
                FilledTonalButton(onClick = onOpenTerminal, enabled = vm.bootstrapInstalled && !installing, modifier = Modifier.height(48.dp)) { Text("Terminal") }
            }
        }
    }

    logPath?.let { path -> LogSheet(path, onDismiss = { logPath = null }) }

    if (confirmReinstall) {
        dev.yurlungr.app.ui.AppSheet(
            "Reinstall the base system?",
            onDismiss = { confirmReinstall = false },
            subtitle = "Downloads the Termux base system again (~30 MB) and replaces the broken one.",
            icon = Ph.ArrowClockwise,
            tile = AppColors.tiles.peach,
            actions = {
                OutlinedButton(onClick = { confirmReinstall = false }, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) { Text("Cancel") }
                Button(onClick = { confirmReinstall = false; vm.reinstallBootstrap() }, modifier = Modifier.weight(2f).heightIn(min = 52.dp)) { Text("Reinstall") }
            },
        ) {
            Text("Running chats, terminals and web servers are stopped first.", style = MaterialTheme.typography.bodyMedium)
            Text(
                "Your home folder, projects and chats are kept. Installed packages and agents need their setup run again afterwards.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (showPhantomHelp) {
        PhantomHelpSheet(
            onDismiss = { showPhantomHelp = false },
            onMarkDone = { done ->
                vm.setPhantomFixDone(done)
                showPhantomHelp = false
            },
        )
    }
}

/** The end of a setup log, read-only (logs are often too big for the editor, and shouldn't be edited). */
@Composable
private fun LogSheet(path: String, onDismiss: () -> Unit) {
    val file = java.io.File(path)
    val text by androidx.compose.runtime.produceState<String?>(null, path) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { dev.yurlungr.app.runtime.SetupStatus.readLogTail(file) }.getOrElse { "Couldn't read the log: ${it.message}" }
        }
    }
    val scroll = androidx.compose.foundation.rememberScrollState()
    // Open at the end: the failure is at the bottom.
    LaunchedEffect(text) { scroll.scrollTo(scroll.maxValue) }
    dev.yurlungr.app.ui.AppSheet(file.name, onDismiss = onDismiss, subtitle = "The end of the log: the failure is usually at the bottom.", icon = Ph.FileText) {
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 420.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0xFF0D1117))
                .verticalScroll(scroll)
                .padding(10.dp),
        ) {
            Text(text ?: "Loading…", color = Color(0xFFE6EDF3), fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp)
        }
    }
}

/** First run, before the Linux environment exists. */
@Composable
private fun SetupCard(onInstall: () -> Unit, onDevices: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(MaterialTheme.colorScheme.primaryContainer)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Set up the Linux environment", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
        Text(
            "Downloads the Termux base system (~30 MB) so agents, git and builds can run on this phone.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onInstall) { Text("Install") }
            TextButton(onClick = onDevices) { Text("Use another phone instead") }
        }
    }
}

@Composable
private fun GroupScope.CheckRow(check: Check, fixEnabled: Boolean, onFix: () -> Unit, extra: (@Composable RowScope.() -> Unit)?) {
    val tones = AppColors.status
    val problem = check.status == CheckStatus.Warn || check.status == CheckStatus.Fail
    var full by remember { mutableStateOf(false) }
    GroupRow(
        check.title + if (!check.required && problem) " · optional" else "",
        subtitle = check.detail,
        subtitleColor = when (check.status) {
            CheckStatus.Warn -> tones.onWarningContainer
            CheckStatus.Fail -> MaterialTheme.colorScheme.error
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        },
        subtitleLines = if (full) Int.MAX_VALUE else 2,
        background = if (check.status == CheckStatus.Warn) tones.warningContainer else if (check.status == CheckStatus.Fail) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f) else Color.Transparent,
        onClick = { full = !full },
        leading = { StatusIcon(check.status) },
        trailing = {
            extra?.invoke(this)
            check.fix?.takeIf { problem || check.status == CheckStatus.Info }?.let { fix ->
                TextButton(onClick = onFix, enabled = fixEnabled) { Text(shortFixLabel(fix)) }
            }
        },
    )
}

private fun shortFixLabel(fix: Fix) = when (fix) {
    Fix.InstallBootstrap -> "Install"
    Fix.Notifications -> "Turn on"
    Fix.BatteryOptimization -> "Fix"
    Fix.PhantomProcesses -> "Fix"
    Fix.OpenTerminal -> "Run"
    Fix.InstallAgents -> "Install"
    Fix.InstallAndroidSdk -> "Set up"
    Fix.InstallDevTools -> "Install"
    Fix.InstallPython, Fix.InstallBuildTools, Fix.InstallUtils, Fix.InstallEditors,
    Fix.InstallGo, Fix.InstallRust, Fix.InstallRuby, Fix.InstallJsRuntimes, Fix.InstallJvm, Fix.InstallPhp, Fix.InstallDart, Fix.InstallDotnet, Fix.InstallDatabases, Fix.InstallSwift, Fix.InstallZig, Fix.InstallElixir, Fix.InstallLua, Fix.InstallCrystal, Fix.InstallNim, Fix.InstallHaskell, Fix.InstallScala, Fix.InstallDlang, Fix.InstallPerl, Fix.InstallShell, Fix.InstallCliTools, Fix.InstallServers, Fix.InstallDevOps, Fix.InstallMedia, Fix.InstallLocalAi, Fix.InstallDistros, Fix.InstallNativeLibs, Fix.InstallPyScience, Fix.InstallDebugging, Fix.InstallLanguageServers, Fix.InstallNetworking, Fix.InstallSecurity, Fix.InstallDataTools, Fix.InstallTaskRunners, Fix.InstallDocs, Fix.InstallArchives, Fix.InstallOcr, Fix.InstallBrowser,
    Fix.InstallUbuntu, Fix.InstallFlutter -> "Install"
    Fix.ReinstallBootstrap -> "Reinstall"
    Fix.RepairRuntime -> "Repair"
    Fix.ChooseJdk -> "Change"
    Fix.UpdatePackages -> "Update all"
}

@Composable
private fun StatusIcon(status: CheckStatus, size: androidx.compose.ui.unit.Dp = 18.dp) {
    val tones = AppColors.status
    when (status) {
        CheckStatus.Pass -> PhIcon(Ph.CheckCircle, "Passing", size = size, tint = tones.success)
        CheckStatus.Warn -> PhIcon(Ph.Warning, "Needs attention", size = size, tint = tones.warning)
        CheckStatus.Fail -> PhIcon(Ph.XCircle, "Failing", size = size, tint = MaterialTheme.colorScheme.error)
        CheckStatus.Info -> PhIcon(Ph.Circle, "Not set up", size = size, tint = MaterialTheme.colorScheme.outline)
    }
}

@Composable
private fun InstallProgressCard(state: InstallState.Running) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Installing base system", style = MaterialTheme.typography.titleSmall)
            if (state.fraction != null) {
                SmoothProgressBar(state.fraction)
            } else {
                FlowBar()
            }
            Text(state.message, style = MaterialTheme.typography.bodySmall, maxLines = 2)
            if (state.log.size > 1) LogBox(state.log.takeLast(40).joinToString("\n"))
        }
    }
}

@Composable
private fun InstallFailedCard(state: InstallState.Failed, onRetry: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Install failed", style = MaterialTheme.typography.titleSmall)
            LogBox(state.error)
            Button(onClick = onRetry) { Text("Retry") }
        }
    }
}

@Composable
internal fun LogBox(text: String) {
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(max = 180.dp)
            .clip(MaterialTheme.shapes.small)
            .background(Color(0xFF0D1117))
            .verticalScroll(rememberScrollState(), reverseScrolling = true)
            .padding(8.dp),
    ) {
        Text(text, color = Color(0xFFE6EDF3), fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp)
    }
}

@Composable
internal fun PhantomHelpSheet(onDismiss: () -> Unit, onMarkDone: (Boolean) -> Unit) {
    val context = LocalContext.current
    val (instructions, command) = remember { DoctorChecks.phantomFixInstructions() }
    dev.yurlungr.app.ui.AppSheet(
        "Background process limit",
        onDismiss = onDismiss,
        subtitle = "Android 12+ kills background child processes once more than 32 exist across apps. " +
            "Agent CLIs, npm and Gradle easily exceed that.",
        icon = Ph.ShieldCheck,
        tile = AppColors.tiles.peach,
        actions = {
            OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) { Text("Later") }
            Button(onClick = { onMarkDone(true) }, modifier = Modifier.weight(2f).heightIn(min = 52.dp)) { Text("I've done this") }
        },
    ) {
        Text(instructions, style = MaterialTheme.typography.bodyMedium)
        if (command != null) {
            LogBox(command)
            FilledTonalButton(onClick = { copyToClipboard(context, command) }) { Text("Copy command") }
        } else {
            FilledTonalButton(onClick = { openDeveloperOptions(context) }) { Text("Open developer options") }
        }
    }
}

private fun copyToClipboard(context: Context, text: String) {
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("command", text))
    Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
}

internal fun openNotificationSettings(context: Context) {
    val intent = if (android.os.Build.VERSION.SDK_INT >= 26) {
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    } else {
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri())
    }
    startSafely(context, intent)
}

/**
 * Asks to run unrestricted. Android shows nothing when the app is already exempt, so then (or if
 * the request screen is missing, as on some vendor builds) open the battery settings instead.
 */
@SuppressLint("BatteryLife") // the user explicitly asks for this from settings
internal fun requestBatteryExemption(context: Context) {
    val pm = context.getSystemService(android.os.PowerManager::class.java)
    val app = "package:${context.packageName}".toUri()
    if (pm.isIgnoringBatteryOptimizations(context.packageName)) {
        Toast.makeText(context, "Already unrestricted. Opening battery settings.", Toast.LENGTH_SHORT).show()
        startFirst(
            context,
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, app),
        )
    } else {
        startFirst(
            context,
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, app),
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, app),
        )
    }
}

/** Starts the first intent the device can handle. */
private fun startFirst(context: Context, vararg intents: Intent) {
    for (intent in intents) {
        try {
            context.startActivity(intent)
            return
        } catch (_: ActivityNotFoundException) {
        } catch (_: SecurityException) {
        }
    }
    Toast.makeText(context, "Not available on this device", Toast.LENGTH_SHORT).show()
}

private fun openDeveloperOptions(context: Context) =
    startSafely(context, Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))

private fun startSafely(context: Context, intent: Intent) {
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, "Not available on this device", Toast.LENGTH_SHORT).show()
    }
}
