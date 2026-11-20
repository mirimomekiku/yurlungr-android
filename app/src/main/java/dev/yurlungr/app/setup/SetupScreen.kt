@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.yurlungr.app.setup

import androidx.core.net.toUri
import dev.yurlungr.app.runtime.Essential
import dev.yurlungr.app.runtime.InstallStatus
import dev.yurlungr.app.runtime.ToolPacks
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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
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
    "Developer tools" to listOf("tools", "tool-git", "tool-gh", "tool-node", "tool-npm", "tool-rg", "setup-dev-tools-setup", "setup-essentials-setup"),
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
    /** Installs the chosen essentials and optional packs (and what they need) in a terminal. */
    onInstallTools: (Set<Essential>, Set<ToolSetup>) -> Unit = { _, _ -> },
    /** Runs a command in a new terminal tab (command, tab name). */
    onRunInTerminal: (String, String) -> Unit = { _, _ -> },
) {
    val context = LocalContext.current
    var logPath by remember { mutableStateOf<String?>(null) }
    var showInstallTools by remember { mutableStateOf(false) }
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

            if (vm.bootstrapInstalled && vm.checks.isNotEmpty()) item(key = "install-tools") {
                InstallToolsCard(vm.checks, enabled = !installing, onOpen = { showInstallTools = true })
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
            vm.restore?.let { plan -> item(key = "restore") { RestoreCard(plan, enabled = !installing, onRestore = { vm.restoreCommand()?.let { onRunInTerminal(it, "Reinstall packages") } }, onDismiss = vm::dismissRestore) } }
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

    if (showInstallTools) {
        InstallToolsSheet(
            vm.checks,
            onDismiss = { showInstallTools = false },
            onInstall = { items, packs ->
                showInstallTools = false
                onInstallTools(items, packs)
            },
        )
    }

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
            Text("Running chats, terminals, web servers and databases are stopped first.", style = MaterialTheme.typography.bodyMedium)
            Text(
                "Your home folder, projects and chats are kept. Installed packages and agents are removed; afterwards System checks offers to reinstall them.",
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

/** The check ids that ran, passed and failed, for [InstallStatus.of], and what passing checks printed. */
private class CheckSets(checks: List<Check>) {
    private val byId = checks.associateBy { it.id }
    val checked: Set<String> = byId.keys
    val passing: Set<String> = checks.filter { it.status == CheckStatus.Pass }.mapTo(HashSet()) { it.id }
    val failing: Set<String> = checks.filter { it.status == CheckStatus.Fail }.mapTo(HashSet()) { it.id }

    fun of(item: Essential) = InstallStatus.of(item.checkIds, checked, passing, failing)

    /** Packs without checks (Ubuntu, Flutter) go by their marker file: [markers] holds those that exist, null until read. */
    fun of(pack: ToolSetup, markers: Set<ToolSetup>?): InstallStatus {
        val ids = ToolPacks.checkIds(pack)
        if (ids.isNotEmpty()) return InstallStatus.of(ids, checked, passing, failing)
        return when {
            markers == null -> InstallStatus.Checking
            pack in markers -> InstallStatus.Installed
            else -> InstallStatus.Missing
        }
    }

    /** The version line of the first of [ids] that passes ("git version 2.47.1"). */
    fun version(ids: List<String>): String? =
        ids.firstNotNullOfOrNull { id -> byId[id]?.takeIf { it.status == CheckStatus.Pass }?.detail?.takeIf(String::isNotBlank) }

    fun working(ids: List<String>) = ids.count { it in passing }
}

/** System checks' way into [InstallToolsSheet]: what's left to install. */
@Composable
private fun InstallToolsCard(checks: List<Check>, enabled: Boolean, onOpen: () -> Unit) {
    val sets = remember(checks) { CheckSets(checks) }
    val states = Essential.entries.associateWith(sets::of)
    val toInstall = Essential.entries.filter { states[it] != InstallStatus.Installed && states[it] != InstallStatus.Checking }
    val packs = ToolPacks.offered.count { ToolPacks.checkIds(it).isNotEmpty() && sets.of(it, null) == InstallStatus.Installed }
    val packLine = "$packs of ${ToolPacks.offered.size} packs installed"
    val subtitle = when {
        states.values.any { it == InstallStatus.Checking } -> "Essentials and optional packs in one place. Checking what's installed…"
        toInstall.isEmpty() -> "Essentials ready · $packLine"
        toInstall.size <= 2 -> "${toInstall.joinToString(" and ") { it.title }} to install · $packLine"
        else -> "${toInstall.size} essentials to install · $packLine"
    }
    Group {
        GroupRow(
            "Install tools",
            subtitle = subtitle,
            icon = Ph.DownloadSimple,
            tile = if (toInstall.isEmpty()) AppColors.tiles.sky else AppColors.tiles.lime,
            onClick = if (enabled) onOpen else null,
        )
    }
}

private enum class ToolsTab(val label: String) { Essentials("Essentials"), Packs("Optional packs") }

private enum class PackFilter(val label: String) { All("All"), Missing("Not installed"), Installed("Installed") }

/** Where each essential is listed. */
private fun essentialSection(item: Essential) = when (item) {
    Essential.Git, Essential.GitHubCli, Essential.Node, Essential.Ripgrep -> "Developer tools"
    Essential.Agents -> "Coding agents"
    Essential.Python, Essential.Pip, Essential.Uv -> "Python"
    Essential.Sqlite -> "Databases"
}

/** MB each choice's setup asks to have free (what the scripts check before starting). */
private fun neededMb(essentials: List<Essential>, packs: List<ToolSetup>): Int =
    packs.sumOf(ToolPacks::spaceMb) +
        (if (essentials.any { it != Essential.Agents }) 600 else 0) +
        (if (Essential.Agents in essentials) 1500 else 0)

/**
 * Pick essentials and optional packs to install, on two tabs with the tabs, search and filters pinned and
 * a footer that says what the choice needs. Essentials that aren't installed start selected, so
 * installing them is open → Install; packs are opt-in. What a selected item needs (Python for pip, the
 * developer tools for the agents, Ubuntu for Flutter) is selected with it and locked, with the reason.
 */
@Composable
private fun InstallToolsSheet(checks: List<Check>, onDismiss: () -> Unit, onInstall: (Set<Essential>, Set<ToolSetup>) -> Unit) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val sets = remember(checks) { CheckSets(checks) }
    val markers by androidx.compose.runtime.produceState<Set<ToolSetup>?>(null) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val prefix = dev.yurlungr.app.runtime.RuntimePaths(context).prefix
            ToolPacks.offered.filter { ToolPacks.checkIds(it).isEmpty() && it.marker != null && java.io.File(prefix, it.marker).exists() }.toSet()
        }
    }
    val freeBytes by androidx.compose.runtime.produceState<Long?>(null) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { android.os.StatFs(dev.yurlungr.app.runtime.RuntimePaths(context).prefix.path).availableBytes }.getOrNull()
        }
    }
    // Known to be missing or broken; not those still being checked (that would tick installed ones).
    val missingEssentials = Essential.entries.filter { sets.of(it) in setOf(InstallStatus.Missing, InstallStatus.Partial, InstallStatus.Broken) }
    val selected = remember { mutableStateListOf<Essential>() }
    // The pre-selection follows the checks as they finish, until the user changes it.
    var touched by remember { mutableStateOf(false) }
    LaunchedEffect(missingEssentials) {
        if (!touched) {
            selected.clear()
            selected.addAll(missingEssentials)
        }
    }
    val selectedPacks = remember { mutableStateListOf<ToolSetup>() }
    var tab by remember { mutableStateOf(if (missingEssentials.isEmpty()) ToolsTab.Packs else ToolsTab.Essentials) }
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(PackFilter.All) }
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    // Each tab keeps its own place; new search results or a new filter start from the top.
    val scrolls = remember { ToolsTab.entries.associateWith { androidx.compose.foundation.ScrollState(0) } }
    LaunchedEffect(query, filter) { scrolls.getValue(ToolsTab.Packs).scrollTo(0) }

    val essentials = Essential.closure(selected)
    val packs = ToolPacks.closure(selectedPacks)
    val count = essentials.size + packs.size
    val needMb = neededMb(essentials, packs)
    val freeMb = freeBytes?.let { (it / 1_000_000).toInt() }
    val tight = freeMb != null && needMb > freeMb
    val packStatus = ToolPacks.offered.associateWith { sets.of(it, markers) }
    fun matchesFilter(p: ToolSetup) = when (filter) {
        PackFilter.All -> true
        PackFilter.Installed -> packStatus[p] == InstallStatus.Installed
        PackFilter.Missing -> packStatus[p] != InstallStatus.Installed
    }
    val q = query.trim()
    val shownPacks = ToolPacks.offered.filter {
        matchesFilter(it) && (q.isEmpty() || it.title.contains(q, ignoreCase = true) || ToolPacks.blurb(it).contains(q, ignoreCase = true))
    }

    dev.yurlungr.app.ui.AppSheet(
        "Install tools",
        onDismiss = onDismiss,
        icon = Ph.DownloadSimple,
        tile = AppColors.tiles.lime,
        scrollState = scrolls.getValue(tab),
        header = {
            ToolsTabs(tab, essentials.size, packs.size) { tab = it }
            if (tab == ToolsTab.Packs) {
                OutlinedTextField(
                    query, { query = it },
                    placeholder = { Text("Search packs") },
                    leadingIcon = { PhIcon(Ph.MagnifyingGlass, null, size = 18.dp) },
                    trailingIcon = if (query.isEmpty()) null else {
                        { IconButton(onClick = { query = "" }) { PhIcon(Ph.X, "Clear search", size = 16.dp) } }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { keyboard?.hide() }),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (f in PackFilter.entries) {
                        val n = ToolPacks.offered.count { p ->
                            when (f) {
                                PackFilter.All -> true
                                PackFilter.Installed -> packStatus[p] == InstallStatus.Installed
                                PackFilter.Missing -> packStatus[p] != InstallStatus.Installed
                            }
                        }
                        dev.yurlungr.app.ui.Chip(
                            "${f.label} $n",
                            onClick = { filter = f },
                            selected = filter == f,
                            modifier = Modifier.semantics { this.selected = filter == f },
                        )
                    }
                }
            }
        },
        actions = {
            Column(Modifier.weight(1f).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }) {
                Text(if (count == 0) "Nothing selected" else "$count selected", style = MaterialTheme.typography.titleSmall)
                Text(
                    when {
                        count == 0 -> "Tick what to install"
                        freeMb == null -> "Needs about ${formatMb(needMb)} free"
                        tight -> "Needs ${formatMb(needMb)}, only ${formatMb(freeMb)} free"
                        else -> "Needs ${formatMb(needMb)} · ${formatMb(freeMb)} free"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (tight) colors.error else colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (count > 0) TextButton(onClick = { touched = true; selected.clear(); selectedPacks.clear() }) { Text("Clear") }
            Button(
                onClick = { onInstall(essentials.toSet(), packs.toSet()) },
                enabled = count > 0,
                modifier = Modifier.heightIn(min = 52.dp),
            ) { Text("Install") }
        },
    ) {
        when (tab) {
            ToolsTab.Essentials -> {
                val unselectedMissing = missingEssentials.filter { it !in essentials }
                if (unselectedMissing.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${Essential.entries.count { sets.of(it) == InstallStatus.Installed }} of ${Essential.entries.size} installed",
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { touched = true; selected.addAll(unselectedMissing) }) { Text("Select missing (${unselectedMissing.size})") }
                    }
                }
                for ((section, items) in Essential.entries.groupBy(::essentialSection)) {
                    ToolSection(section) {
                        for (item in items) {
                            Separator()
                            val status = sets.of(item)
                            EssentialOrPackRow(
                                title = item.title,
                                detail = (if (status == InstallStatus.Installed) sets.version(item.checkIds) else null) ?: item.blurb,
                                status = status,
                                partNote = null,
                                size = if (item == Essential.Agents) "1.5 GB" else null,
                                on = item in essentials,
                                lockedBy = Essential.neededBy(item, selected).map { it.title },
                                onToggle = { touched = true; if (item in selected) selected.remove(item) else selected.add(item) },
                            )
                        }
                    }
                }
                if (Essential.Agents in essentials) {
                    Text(
                        "Sign in to the agents from Agents once they're installed.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
            }
            ToolsTab.Packs -> {
                if (shownPacks.isEmpty()) {
                    Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            if (q.isEmpty()) "No ${filter.label.lowercase()} packs." else "No pack matches “$q”.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.onSurfaceVariant,
                        )
                        TextButton(onClick = { query = ""; filter = PackFilter.All }) { Text("Show all packs") }
                    }
                } else {
                    // Searching flattens the categories: the matches are what matter.
                    val groups = if (q.isNotEmpty()) listOf("${shownPacks.size} ${if (shownPacks.size == 1) "match" else "matches"}" to shownPacks)
                    else shownPacks.groupBy { ToolPacks.category(it) }.entries.sortedBy { it.key?.ordinal ?: Int.MAX_VALUE }
                        .map { (it.key?.title ?: "Other") to it.value }
                    for ((title, items) in groups) {
                        ToolSection(title) {
                            for (pack in items) {
                                Separator()
                                val ids = ToolPacks.checkIds(pack)
                                EssentialOrPackRow(
                                    title = pack.title,
                                    detail = ToolPacks.blurb(pack),
                                    status = packStatus.getValue(pack),
                                    partNote = if (ids.size > 1) "${sets.working(ids)} of ${ids.size} tools work" else null,
                                    size = formatMb(ToolPacks.spaceMb(pack)),
                                    on = pack in packs,
                                    lockedBy = ToolPacks.neededBy(pack, selectedPacks).map { it.title },
                                    onToggle = { if (pack in selectedPacks) selectedPacks.remove(pack) else selectedPacks.add(pack) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Essentials | Optional packs, as the app's segmented control; each tab counts what's selected on it. */
@Composable
private fun ToolsTabs(selected: ToolsTab, essentials: Int, packs: Int, onSelect: (ToolsTab) -> Unit) {
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    val colors = MaterialTheme.colorScheme
    val track = if (dark) colors.surfaceContainerHigh else colors.surfaceContainerHighest
    val thumb = if (dark) colors.primary else colors.surfaceContainer
    val onThumb = if (dark) colors.onPrimary else colors.onSurface
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(track)
            .padding(4.dp)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        for (t in ToolsTab.entries) {
            val on = t == selected
            val n = if (t == ToolsTab.Essentials) essentials else packs
            Row(
                Modifier
                    .weight(1f)
                    .height(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (on) thumb else Color.Transparent)
                    .selectable(selected = on, role = Role.Tab, onClick = { onSelect(t) })
                    .semantics { if (n > 0) stateDescription = "$n selected" },
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(t.label, style = MaterialTheme.typography.labelLarge, color = if (on) onThumb else colors.onSurfaceVariant, maxLines = 1)
                if (n > 0) {
                    Spacer(Modifier.width(6.dp))
                    val tile = AppColors.tiles.lime
                    Text(
                        "$n",
                        style = MaterialTheme.typography.labelSmall,
                        color = tile.on,
                        modifier = Modifier.clip(CircleShape).background(tile.fill).padding(horizontal = 7.dp, vertical = 1.dp),
                    )
                }
            }
        }
    }
}

/** A heading and its rows. */
@Composable
private fun ToolSection(title: String, rows: @Composable GroupScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SectionLabel(title, Modifier.padding(horizontal = 4.dp).semantics { heading() })
        Group(content = rows)
    }
}

private fun formatMb(mb: Int) = if (mb >= 1000) "%.1f GB".format(java.util.Locale.ROOT, mb / 1000.0) else "$mb MB"

/**
 * One essential or pack. The whole row toggles it (one target for touch and screen readers). Not
 * installed is the quiet default; installed shows a check, and a coloured line appears only when there's
 * something to know: it's needed by another pick, needs repair, is partly installed, or will be redone.
 */
@Composable
private fun GroupScope.EssentialOrPackRow(
    title: String,
    detail: String,
    status: InstallStatus,
    /** "2 of 4 tools work", shown when partly installed. */
    partNote: String?,
    size: String?,
    on: Boolean,
    lockedBy: List<String>,
    onToggle: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val tones = AppColors.status
    val locked = lockedBy.isNotEmpty()
    val note: Pair<String, Color>? = when {
        locked -> "Needed by ${lockedBy.joinToString()}" to colors.primary
        status == InstallStatus.Checking -> "Checking…" to colors.onSurfaceVariant
        status == InstallStatus.Broken -> "Needs repair" to colors.error
        status == InstallStatus.Partial -> "Partly installed" + (partNote?.let { ": $it" } ?: "") to tones.warning
        on && status == InstallStatus.Installed -> "Will be updated or repaired" to colors.onSurfaceVariant
        else -> null
    }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .toggleable(value = on, enabled = !locked, role = Role.Checkbox, onValueChange = { onToggle() })
            .padding(start = 4.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = on, onCheckedChange = null, enabled = !locked, modifier = Modifier.padding(horizontal = 12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (status == InstallStatus.Installed) {
                    Spacer(Modifier.width(6.dp))
                    PhIcon(Ph.CheckCircle, "Installed", size = 15.dp, tint = tones.success)
                }
            }
            Text(detail, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            note?.let { (text, color) -> Text(text, style = MaterialTheme.typography.labelMedium, color = color) }
        }
        if (size != null) {
            Spacer(Modifier.width(10.dp))
            Text(size, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant, maxLines = 1)
        }
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

/**
 * After a base-system reinstall: what the old system had that the new one doesn't (packages
 * installed by hand, global npm packages, agents and setups), reinstalled in a terminal in one go.
 */
@Composable
private fun RestoreCard(plan: dev.yurlungr.app.runtime.RestoreList.Plan, enabled: Boolean, onRestore: () -> Unit, onDismiss: () -> Unit) {
    val title = if (plan.count > 0) "Reinstall the ${plan.count} package${if (plan.count == 1) "" else "s"} you had" else "Set up again what you had"
    val reruns = plan.setups.map { it.title } + plan.agents.map { "agent: $it" }
    val sample = (plan.packages + plan.npm).take(6)
    val detail = buildList {
        if (sample.isNotEmpty()) add(sample.joinToString(", ") + if (plan.count > sample.size) " and ${plan.count - sample.size} more" else "")
        if (reruns.isNotEmpty()) add("Then runs again: " + reruns.joinToString(", "))
    }.joinToString(". ") + ". Installed before the base system was reinstalled."
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
        Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onRestore, enabled = enabled) { Text("Reinstall") }
            TextButton(onClick = onDismiss, enabled = enabled) { Text("Forget them") }
        }
    }
}
