package dev.yurlungr.app.projects

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.yurlungr.app.databases.DbNaming
import dev.yurlungr.app.editor.FileSave
import dev.yurlungr.app.projects.Git.Companion.shellQuote
import dev.yurlungr.app.runtime.CommandRunner
import dev.yurlungr.app.runtime.RuntimePaths
import dev.yurlungr.app.ui.AppColors
import dev.yurlungr.app.ui.AppSheet
import dev.yurlungr.app.ui.Ph
import dev.yurlungr.app.ui.PhIcon
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Collections

/**
 * Outdated-dependency reports per project: kept in memory (observed by the sheet and the Overview
 * tile) and saved under the app's files, so a check (slow, online) is only run when asked.
 */
internal object DependencyStore {
    /** Checks run here, not in a screen's scope: closing the sheet mustn't kill a half-done check. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** The last report per project path. */
    val reports = mutableStateMapOf<String, DepReport>()

    /** What's being asked right now, per project path ("Asking npm…"). */
    val checking = mutableStateMapOf<String, String>()

    private val loaded = Collections.synchronizedSet(HashSet<String>())

    private fun file(context: Context, dir: File) = File(context.filesDir, "dependency-updates/${DbNaming.projectId(dir.absolutePath)}.json")

    /** The saved report for [dir], read once per app run. */
    suspend fun load(context: Context, dir: File): DepReport? {
        reports[dir.path]?.let { return it }
        if (!loaded.add(dir.path)) return null
        val saved = withContext(Dispatchers.IO) { runCatching { file(context, dir).takeIf { it.isFile }?.readText() }.getOrNull()?.let(DependencyUpdates::decode) }
        if (saved != null && dir.path !in reports) reports[dir.path] = saved
        return reports[dir.path]
    }

    /** Asks each of the project's package managers for outdated dependencies, one after another. */
    fun refresh(context: Context, dir: File, stacks: List<DetectedStack>) {
        if (dir.path in checking) return
        val app = context.applicationContext
        checking[dir.path] = "Starting…"
        scope.launch {
            try {
                val paths = RuntimePaths(app)
                val groups = ArrayList<DepGroup>()
                for ((tool, folder) in DependencyUpdates.toolsFor(stacks)) {
                    checking[dir.path] = "Asking ${tool.label}…"
                    groups += runCatching { check(paths, tool, folder) }.getOrElse { e ->
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        DepGroup(tool, folder, emptyList(), "Couldn't check: ${e.message}", failed = true)
                    }
                }
                val report = DepReport(System.currentTimeMillis(), groups)
                reports[dir.path] = report
                loaded.add(dir.path)
                withContext(Dispatchers.IO) {
                    runCatching { file(app, dir).apply { parentFile?.mkdirs() }.let { FileSave.writeAtomically(it, DependencyUpdates.encode(report)) } }
                }
            } finally {
                checking.remove(dir.path)
            }
        }
    }

    private suspend fun check(paths: RuntimePaths, tool: DepTool, dir: File): DepGroup {
        withContext(Dispatchers.IO) { DependencyUpdates.notReady(tool, dir) }?.let { return DepGroup(tool, dir, emptyList(), it) }
        val r = CommandRunner.run(paths, listOf(paths.bash.path, "-c", DependencyUpdates.listCommand(tool)), cwd = dir, timeoutMs = 240_000, forParsing = true)
        val what = "${tool.label}: checking for updates"
        if (tool == DepTool.Cargo && r.output.contains(DependencyUpdates.NO_CARGO_OUTDATED)) {
            return DepGroup(tool, dir, emptyList(), "Listing Cargo updates needs cargo-outdated, which isn't installed. " +
                "Install it with `cargo install cargo-outdated` in a terminal (it compiles for a while), then refresh.")
        }
        if (r.timedOut || r.launchError != null) return DepGroup(tool, dir, emptyList(), r.describe(what), failed = true)
        val parsed = withContext(Dispatchers.Default) { DependencyUpdates.parse(tool, r.output) }
        // npm and pnpm exit 1 when something is outdated: a list read is a result whatever the exit code.
        if (parsed == null || (parsed.isEmpty() && !r.ok)) {
            return DepGroup(tool, dir, emptyList(), DependencyUpdates.errorOf(r.output) ?: r.describe(what), failed = true)
        }
        val sorted = parsed.sortedWith(compareBy<OutdatedDep>({ it.kind == "indirect" }, { it.bump == Bump.Major }, { it.name.lowercase() }))
        return DepGroup(tool, dir, sorted, DependencyUpdates.caveat(tool).takeIf { sorted.isNotEmpty() })
    }

    // ---- The last update run ----

    private fun yDir(root: File) = File(root, ProjectChecks.DIR)

    /** Saves what's about to run, so its result can be told later (from the files the terminal leaves). */
    fun recordRun(root: File, run: DependencyUpdates.Run) {
        val y = yDir(root)
        y.mkdirs()
        File(y, ".gitignore").takeIf { !it.exists() }?.writeText("*\n")
        File(y, DependencyUpdates.EXIT).delete()
        File(y, DependencyUpdates.CHECKED).delete()
        FileSave.writeAtomically(File(y, DependencyUpdates.RECORD), DependencyUpdates.encodeRun(run))
    }

    fun forgetRun(root: File) { File(yDir(root), DependencyUpdates.RECORD).delete() }

    /** Reads files: call off the main thread. Null when there's no recent run to show. */
    fun outcome(root: File, stacks: List<DetectedStack>): UpdateOutcome? {
        val y = yDir(root)
        val run = runCatching { File(y, DependencyUpdates.RECORD).takeIf { it.isFile }?.readText() }.getOrNull()?.let(DependencyUpdates::decodeRun) ?: return null
        val names = stacks.flatMap { listOf(it.name) + it.tags.filter { t -> t != "workspaces" } }.distinct()
        val exitFile = File(y, DependencyUpdates.EXIT)
        // A few seconds' slack: file times can be coarse.
        val exit = exitFile.takeIf { it.isFile && it.lastModified() >= run.startedAt - 5_000 }?.let { runCatching { it.readText().trim() }.getOrNull() }
        if (exit == null) {
            // Never finished (the terminal was closed): stop saying "updating" after a while.
            return if (System.currentTimeMillis() - run.startedAt < 6 * 3_600_000L) UpdateOutcome.Running(run) else null
        }
        if (exit != "0") {
            val log = readEnds(File(y, DependencyUpdates.LOG), head = 0).second
            return UpdateOutcome.Failed(run, "The update failed", FailureReport(
                "Dependency update", run.command, names, log, message = "Exit code $exit",
                details = "The update was meant to do this:\n${run.summary}",
            ))
        }
        val checks = run.checks ?: return UpdateOutcome.Done(run, checked = false)
        if (!File(y, DependencyUpdates.CHECKED).isFile) return UpdateOutcome.Checking(run)
        val log = readEnds(ProjectChecks.problemsLog(root), head = 0).second
        val problems = runCatching { ProjectChecks.parseProblems(log, root) }.getOrDefault(emptyList())
        val errors = problems.count { it.severity == Severity.Error }
        val failed = errors > 0 || (problems.isEmpty() && LogExcerpt.hasErrors(log))
        if (!failed) return UpdateOutcome.Done(run, checked = true)
        return UpdateOutcome.Failed(run, "Checks fail after the update", FailureReport(
            "The project's checks after a dependency update", checks, names, log,
            message = if (errors > 0) "$errors error${if (errors == 1) "" else "s"}" else null,
            details = "These dependencies were just updated:\n${run.summary}\n\nIf an update caused this, adapt the code to the new version; " +
                "if that isn't reasonable, go back to the previous version of that dependency and say why.",
        ))
    }
}

internal sealed interface UpdateOutcome {
    val run: DependencyUpdates.Run
    data class Running(override val run: DependencyUpdates.Run) : UpdateOutcome
    data class Checking(override val run: DependencyUpdates.Run) : UpdateOutcome
    data class Failed(override val run: DependencyUpdates.Run, val title: String, val report: FailureReport) : UpdateOutcome
    data class Done(override val run: DependencyUpdates.Run, val checked: Boolean) : UpdateOutcome
}

/** The last update's result: still running, the failure card (with Ask the agent), or done. Nothing when there's none. */
@Composable
internal fun DependencyUpdateResult(root: File, stacks: List<DetectedStack>, refreshKey: Int, modifier: Modifier = Modifier) {
    var hidden by remember(root.path) { mutableIntStateOf(0) }
    val outcome by produceState<UpdateOutcome?>(null, root, stacks, refreshKey, hidden) {
        // While it runs, look again now and then: the terminal leaves its result in files.
        while (true) {
            value = withContext(Dispatchers.IO) { runCatching { DependencyStore.outcome(root, stacks) }.getOrNull() }
            if (value !is UpdateOutcome.Running && value !is UpdateOutcome.Checking) break
            delay(4_000)
        }
    }
    val scope = rememberCoroutineScope()
    fun hide() = scope.launch { withContext(Dispatchers.IO) { DependencyStore.forgetRun(root) }; hidden++ }
    val colors = MaterialTheme.colorScheme
    when (val o = outcome) {
        null -> {}
        is UpdateOutcome.Failed -> FailureHelpCard(root, o.title, o.report, KnownFixes.packageManager(stacks), modifier, onDismiss = { hide() })
        else -> {
            val tones = AppColors.status
            val done = o is UpdateOutcome.Done
            Row(
                modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                    .background(if (done) tones.successContainer else colors.surfaceContainerHigh).padding(start = 12.dp, top = 6.dp, bottom = 6.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (done) PhIcon(Ph.CheckCircle, null, size = 16.dp, tint = tones.success)
                else CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text(
                    when (o) {
                        is UpdateOutcome.Running -> "Updating in a terminal…"
                        is UpdateOutcome.Checking -> "Updated; running the checks…"
                        is UpdateOutcome.Done -> if (o.checked) "Updated, and the checks pass." else "Updated. This project has no checks to run."
                        else -> ""
                    },
                    style = MaterialTheme.typography.bodySmall, color = if (done) tones.onSuccessContainer else colors.onSurface, modifier = Modifier.weight(1f),
                )
                if (done) TextButton(onClick = { hide() }) { Text("Hide") }
            }
        }
    }
}

/**
 * Outdated dependencies for each of the project's package managers: pick some (or all safe,
 * non-major ones), then update in a terminal, followed by the project's checks.
 */
@Composable
internal fun DependencyUpdatesSheet(
    root: File,
    stacks: List<DetectedStack>,
    onDismiss: () -> Unit,
    runInTerminal: (String, String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(root) { DependencyStore.load(context, root) }
    val report = DependencyStore.reports[root.path]
    val checking = DependencyStore.checking[root.path]
    val tools = remember(stacks) { DependencyUpdates.toolsFor(stacks) }
    /** Selected: "<tool>|<dir>|<name>" → version. */
    val selected = remember(root.path) { mutableStateMapOf<String, String>() }
    fun key(g: DepGroup, d: OutdatedDep) = "${g.tool}|${g.dir.path}|${d.name}|${d.current}"
    val colors = MaterialTheme.colorScheme

    fun update(r: DepReport) {
        val chosen = r.groups.mapNotNull { g ->
            g.deps.mapNotNull { d -> selected[key(g, d)]?.let { DepUpdate(d, it) } }.filter(DependencyUpdates::valid).takeIf { it.isNotEmpty() }?.let { g to it }
        }
        val steps = chosen.mapNotNull { (g, ups) -> DependencyUpdates.updateCommand(g.tool, ups)?.let { g.dir to it } }
        if (steps.isEmpty()) return
        scope.launch {
            val started = withContext(Dispatchers.IO) {
                runCatching {
                    // Reads package.json and friends: off the main thread.
                    val checks = ProjectChecks.checkCommands(stacks)
                    val checksCommand = checks.takeIf { it.isNotEmpty() }?.let { ProjectChecks.terminalCommand(root, it, ProjectChecks.problemsLog(root), clearResults = false) }
                    fun rel(f: File) = f.relativeTo(root).invariantSeparatorsPath.ifEmpty { "." }
                    val run = DependencyUpdates.Run(
                        System.currentTimeMillis(),
                        chosen.joinToString("\n") { (g, ups) -> DependencyUpdates.describe(g.tool, ups) },
                        steps.joinToString(" && ") { (dir, c) -> "(cd ${shellQuote(rel(dir))} && $c)" },
                        checks.takeIf { it.isNotEmpty() }?.joinToString("; ") { it.command },
                    )
                    DependencyStore.recordRun(root, run)
                    DependencyUpdates.terminalCommand(root, steps, checksCommand)
                }.getOrNull()
            }
            if (started == null) {
                android.widget.Toast.makeText(context, "Couldn't prepare the update", android.widget.Toast.LENGTH_LONG).show()
                return@launch
            }
            onDismiss()
            runInTerminal(started, "Update dependencies · ${root.name}")
        }
    }

    val listed = report?.takeIf { it.outdated > 0 }
    AppSheet(
        "Dependency updates", onDismiss = onDismiss, subtitle = root.name, icon = Ph.ArrowsClockwise, tile = AppColors.tiles.sky,
        actions = if (listed == null) null else {
            {
                val r = listed
                val safe = r.groups.flatMap { g -> g.deps.filter { it.autoSafe }.map { key(g, it) to it.safeTarget!! } }
                OutlinedButton(enabled = safe.isNotEmpty() && checking == null, modifier = Modifier.weight(1f), onClick = {
                    selected.clear()
                    safe.forEach { (k, v) -> selected[k] = v }
                }) { Text("All safe (${safe.size})", maxLines = 1) }
                Button(enabled = selected.isNotEmpty() && checking == null, modifier = Modifier.weight(1f), onClick = { update(r) }) {
                    Text(if (selected.isEmpty()) "Update" else "Update ${selected.size}", maxLines = 1)
                }
            }
        },
    ) {
        DependencyUpdateResult(root, stacks, refreshKey = 0)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    when {
                        checking != null -> checking
                        report == null -> if (tools.isEmpty()) "No npm, pnpm, Yarn, Bun, Python, Go or Cargo project found here." else "Not checked yet."
                        report.outdated == 0 && report.groups.none { it.failed || it.note != null } -> "Everything is up to date."
                        else -> "${report.outdated} outdated" + (if (report.majors > 0) ", ${report.majors} major" else "")
                    },
                    style = MaterialTheme.typography.titleSmall,
                )
                report?.let {
                    Text("Checked " + android.text.format.DateUtils.getRelativeTimeSpanString(it.checkedAt), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
            }
            if (checking != null) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            else if (tools.isNotEmpty()) TextButton(onClick = { DependencyStore.refresh(context, root, stacks) }) {
                PhIcon(Ph.ArrowClockwise, null, size = 14.dp); Spacer(Modifier.width(6.dp)); Text(if (report == null) "Check now" else "Refresh")
            }
        }
        if (report == null && checking == null && tools.isNotEmpty()) {
            Text("Asks each package manager what's newer. Needs the internet and can take a minute.", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
        report?.groups?.forEach { g ->
            val where = g.dir.relativeTo(root).invariantSeparatorsPath.ifEmpty { null }
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(colors.surfaceContainerHigh).padding(vertical = 6.dp),
            ) {
                Text(
                    g.tool.label + (where?.let { " · $it" } ?: "") + if (g.deps.isNotEmpty()) " · ${g.deps.size}" else "",
                    style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
                if (g.deps.isEmpty() && g.note == null) {
                    Text("Up to date.", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, modifier = Modifier.padding(horizontal = 12.dp))
                }
                g.note?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = if (g.failed) colors.error else colors.onSurfaceVariant,
                        maxLines = 6, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp))
                }
                for (d in g.deps) DepRow(d, selected[key(g, d)]) { version ->
                    if (version == null) selected.remove(key(g, d)) else selected[key(g, d)] = version
                }
            }
        }
    }
}

/** A dependency with its versions; the box picks the safe version when there is one, the chip the major one. */
@Composable
private fun DepRow(d: OutdatedDep, chosen: String?, onChoose: (String?) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val peach = AppColors.tiles.peach
    val default = d.safeTarget ?: d.latest
    Row(
        Modifier.fillMaxWidth().clickable { onChoose(if (chosen == null) default else null) }.padding(end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = chosen != null, onCheckedChange = { onChoose(if (it) default else null) })
        Column(Modifier.weight(1f)) {
            Text(d.name, fontFamily = FontFamily.Monospace, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${d.current ?: "not installed"} → ${chosen ?: default}",
                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                d.kind?.let { Badge(it, colors.surfaceContainerHighest, colors.onSurfaceVariant) }
                val major = d.bump == Bump.Major
                if (major && d.safeTarget != null) {
                    // A safe version exists too: the major one is a separate, deliberate choice.
                    val on = chosen == d.latest
                    Box(Modifier.clip(RoundedCornerShape(8.dp)).clickable { onChoose(if (on) d.safeTarget else d.latest) }) {
                        Badge(if (on) "major ${d.latest} ✓" else "major ${d.latest} available", if (on) peach.fill else colors.surfaceContainerHighest,
                            if (on) peach.on else colors.onSurfaceVariant)
                    }
                } else if (major) Badge("major", peach.fill, peach.on)
                else if (d.bump == Bump.Unknown) Badge("check notes", colors.surfaceContainerHighest, colors.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun Badge(text: String, fill: androidx.compose.ui.graphics.Color, on: androidx.compose.ui.graphics.Color) {
    Text(text, fontSize = 11.sp, color = on, maxLines = 1,
        modifier = Modifier.padding(top = 2.dp).clip(RoundedCornerShape(8.dp)).background(fill).padding(horizontal = 6.dp, vertical = 1.dp))
}
