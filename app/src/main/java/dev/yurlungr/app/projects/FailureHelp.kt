package dev.yurlungr.app.projects

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import dev.yurlungr.app.projects.Git.Companion.shellQuote
import dev.yurlungr.app.runtime.CommandRunner
import dev.yurlungr.app.runtime.RuntimePaths
import dev.yurlungr.app.runtime.SetupStatus
import dev.yurlungr.app.ui.AppColors
import dev.yurlungr.app.ui.LocalOpenChat
import dev.yurlungr.app.ui.LocalRunInTerminal
import dev.yurlungr.app.ui.Ph
import dev.yurlungr.app.ui.PhIcon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap

/**
 * A failure, the known phone fixes for it (each with Run fix when there's a sure command) and
 * Ask the agent. Nothing runs on its own. [dir]: where fixes run and the chat starts.
 */
@Composable
fun FailureHelpCard(
    dir: File,
    title: String,
    report: FailureReport,
    pm: String,
    modifier: Modifier = Modifier,
    onDismiss: (() -> Unit)? = null,
) {
    val openChat = LocalOpenChat.current
    val runInTerminal = LocalRunInTerminal.current
    // Matching scans up to a few hundred KB of log: off the main thread.
    val fixes by produceState(emptyList<KnownFix>(), report.log, pm) {
        value = withContext(Dispatchers.Default) { runCatching { KnownFixes.match(report.log, pm) }.getOrDefault(emptyList()) }
    }
    val colors = MaterialTheme.colorScheme
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(colors.errorContainer.copy(alpha = 0.45f)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PhIcon(Ph.Warning, null, size = 16.dp, tint = colors.error)
            Spacer(Modifier.width(8.dp))
            Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            onDismiss?.let { TextButton(onClick = it) { Text("Hide") } }
        }
        report.message?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        for (fix in fixes.take(3)) {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(colors.surfaceContainer).padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(fix.title, style = MaterialTheme.typography.bodyMedium)
                Text(fix.explanation, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                val code = listOfNotNull(
                    fix.env.takeIf { it.isNotEmpty() }?.entries?.joinToString(" ") { "${it.key}=${it.value}" },
                    fix.command?.let { "$ $it" },
                ).joinToString("\n")
                if (code.isNotEmpty()) {
                    Text(code, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp, color = colors.onSurface,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(colors.surfaceContainerHighest).padding(8.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val command = fix.command
                    if (command != null && runInTerminal != null) {
                        FilledTonalButton(onClick = { runInTerminal("cd ${shellQuote(dir.path)} && $command", "Fix · ${dir.name}") }) {
                            PhIcon(Ph.Play, null, size = 14.dp); Spacer(Modifier.width(6.dp)); Text("Run fix")
                        }
                    }
                    if (openChat != null) {
                        TextButton(onClick = { openChat(dir, FixPrompt.build(report, listOf(fix))) }) {
                            PhIcon(Ph.Sparkle, null, size = 14.dp); Spacer(Modifier.width(6.dp)); Text("Ask the agent")
                        }
                    }
                }
            }
        }
        if (fixes.isEmpty() && openChat != null) {
            FilledTonalButton(onClick = { openChat(dir, FixPrompt.build(report, emptyList())) }) {
                PhIcon(Ph.Sparkle, null, size = 14.dp); Spacer(Modifier.width(6.dp)); Text("Ask the agent to fix it")
            }
        }
    }
}

/** The first [head] and last [tail] characters of a file (logs can be huge). */
internal fun readEnds(f: File, head: Int = 4096, tail: Int = 256 * 1024): Pair<String, String> = runCatching {
    RandomAccessFile(f, "r").use { r ->
        val len = r.length()
        fun read(from: Long, n: Int): String {
            val buf = ByteArray(n)
            r.seek(from)
            r.readFully(buf)
            return String(buf, Charsets.UTF_8)
        }
        read(0, minOf(len, head.toLong()).toInt()) to read(maxOf(0L, len - tail), minOf(len, tail.toLong()).toInt())
    }
}.getOrDefault("" to "")

/** Installed runtime versions (their version commands' output), run once per binary update. */
internal object InstalledVersions {
    private val cache = ConcurrentHashMap<String, Pair<Long, String?>>()

    /** Runtime → version output, null when it isn't installed. Runs commands: call off the main thread. */
    suspend fun get(paths: RuntimePaths, runtimes: Collection<String>): Map<String, String?> = runtimes.associateWith { runtime ->
        val binary = RuntimeRequirements.BINARIES[runtime]?.let { File(paths.prefix, it) }
        val command = RuntimeRequirements.VERSION_COMMANDS[runtime]
        if (binary == null || command == null || !binary.exists()) return@associateWith null
        val stamp = binary.lastModified()
        cache[runtime]?.takeIf { it.first == stamp }?.let { return@associateWith it.second }
        val r = CommandRunner.shell(paths, command, timeoutMs = 20_000)
        val out = r.output.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.takeIf { r.ok }
        if (!r.timedOut) cache[runtime] = stamp to out
        out
    }
}

/** Runtime versions [dir] asks for that this phone doesn't have (also shown on the Overview). */
internal suspend fun versionWarnings(paths: RuntimePaths, dir: File, stacks: List<DetectedStack>): List<VersionWarning> = withContext(Dispatchers.IO) {
    runCatching {
        val dirs = (listOf(dir) + stacks.map { it.dir }).distinctBy { it.path }
        val wanted = dirs.flatMap { RuntimeRequirements.read(it) }.distinctBy { it.runtime to it.range }
        if (wanted.isEmpty()) return@runCatching emptyList()
        RuntimeRequirements.mismatches(wanted, InstalledVersions.get(paths, wanted.map { it.runtime }.toSet()))
    }.getOrDefault(emptyList())
}

private object HealthPrefs {
    private const val PREFS = "project_health"
    fun hidden(context: Context, key: String) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(key, false)
    fun hide(context: Context, key: String) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean(key, true) }
}

/**
 * On the project screen: a failed dependency setup (with fixes and Ask the agent), and runtime
 * versions the project asks for that this phone doesn't have. Both can be hidden.
 */
@Composable
fun ProjectHealthCards(dir: File, stacks: List<DetectedStack>?, refreshKey: Int) {
    val context = LocalContext.current
    val paths = remember { RuntimePaths(context) }
    val openChat = LocalOpenChat.current
    val stackNames = stacks.orEmpty().flatMap { listOf(it.name) + it.tags.filter { t -> t != "workspaces" } }.distinct()
    var hiddenNow by remember(dir.path) { mutableStateOf(setOf<String>()) }

    val setup by produceState<Pair<FailureReport, Long>?>(null, dir, refreshKey, stacks) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val status = SetupStatus.read(File(paths.setupStatusDir, "project-setup.status")) ?: return@runCatching null
                val log = File(paths.home, "project-setup.log")
                if (!log.isFile) return@runCatching null
                val (head, tail) = readEnds(log)
                val key = "setup:${dir.path}:${status.finishedAt}"
                if (HealthPrefs.hidden(context, key)) return@runCatching null
                SetupFailure.report(status, SetupFailure.projectOf(head), tail, dir, stackNames)?.let { it to status.finishedAt }
            }.getOrNull()
        }
    }
    setup?.let { (report, finishedAt) ->
        val key = "setup:${dir.path}:$finishedAt"
        if (key !in hiddenNow) {
            FailureHelpCard(dir, "Dependency setup failed", report, KnownFixes.packageManager(stacks.orEmpty()),
                Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                onDismiss = { hiddenNow = hiddenNow + key; HealthPrefs.hide(context, key) })
        }
    }

    val warnings by produceState(emptyList<VersionWarning>(), dir, refreshKey, stacks) {
        if (stacks == null) return@produceState
        value = versionWarnings(paths, dir, stacks)
    }
    val key = "versions:${dir.path}:" + warnings.joinToString("|") { "${it.requirement.runtime}${it.requirement.range}@${it.installed}" }
    if (warnings.isNotEmpty() && key !in hiddenNow && !remember(key) { HealthPrefs.hidden(context, key) }) {
        val tones = AppColors.status
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).clip(RoundedCornerShape(16.dp))
                .background(tones.warningContainer).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            for (w in warnings) {
                Text(w.title, style = MaterialTheme.typography.bodyMedium, color = tones.onWarningContainer)
                Text("${w.requirement.source}. ${RuntimeRequirements.advice(w)}", style = MaterialTheme.typography.bodySmall, color = tones.onWarningContainer)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (openChat != null) TextButton(onClick = { openChat(dir, FixPrompt.versions(warnings)) }) {
                    PhIcon(Ph.Sparkle, null, size = 14.dp); Spacer(Modifier.width(6.dp)); Text("Ask the agent")
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { hiddenNow = hiddenNow + key; HealthPrefs.hide(context, key) }) { Text("Hide") }
            }
        }
    }
}

/**
 * Under the Checks tab's buttons: known phone fixes found in the last check or test output, or
 * Ask the agent when a run failed with nothing this app could parse ([problemsParsed]/[testsParsed]
 * false: no problems or test results read from that log).
 */
@Composable
internal fun ChecksFailureHelp(
    root: File, stacks: List<DetectedStack>, refreshKey: Int,
    checkCommands: String, testCommands: String, problemsParsed: Boolean, testsParsed: Boolean,
) {
    val pm = KnownFixes.packageManager(stacks)
    val stackNames = stacks.flatMap { listOf(it.name) + it.tags.filter { t -> t != "workspaces" } }.distinct()
    val logs by produceState<List<FailureReport>>(emptyList(), root, refreshKey, problemsParsed, testsParsed, checkCommands, testCommands) {
        value = withContext(Dispatchers.IO) {
            listOf(
                Triple(FailureReport("Checks", checkCommands.ifBlank { null }, stackNames, ""), ProjectChecks.problemsLog(root), problemsParsed),
                Triple(FailureReport("Tests", testCommands.ifBlank { null }, stackNames, ""), ProjectChecks.testsLog(root), testsParsed),
            ).mapNotNull { (report, f, parsed) ->
                val text = if (f.isFile) readEnds(f, head = 0).second else return@mapNotNull null
                // A failure worth a card: a known phone issue, or errors that nothing above shows.
                val show = runCatching { KnownFixes.match(text, pm).isNotEmpty() || (!parsed && LogExcerpt.hasErrors(text)) }.getOrDefault(false)
                if (show) report.copy(log = text) else null
            }
        }
    }
    for (report in logs) {
        FailureHelpCard(root, "${report.what} output has errors", report, pm, Modifier.padding(top = 6.dp))
    }
}
