package dev.yurlungr.app.projects

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import dev.yurlungr.app.runtime.projectSetupCommand
import dev.yurlungr.app.ui.AppColors
import dev.yurlungr.app.ui.AppSheet
import dev.yurlungr.app.ui.Ph
import dev.yurlungr.app.ui.PhIcon
import java.io.File

/** The command that installs [stacks] (in their folders) in one terminal run. */
internal fun installCommand(root: File, stacks: List<DetectedStack>): String {
    val packages = stacks.flatMap { it.packages }.distinct()
    val steps = stacks.flatMap { s ->
        val rel = s.dir.relativeTo(root).invariantSeparatorsPath
        if (rel.isEmpty()) s.steps else s.steps.map { "cd ${Git.shellQuote(rel)} && $it" }
    }
    return projectSetupCommand(root, packages, steps)
}

/** Projects whose "dependencies aren't installed" banner the user dismissed, and automatic setup. */
internal object SetupBannerPrefs {
    private const val PREFS = "project_setup"
    private const val AUTO = "auto_setup"

    /** Install runtimes and dependencies as soon as a project that needs them is opened. */
    fun auto(context: Context): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(AUTO, false)
    fun setAuto(context: Context, on: Boolean) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean(AUTO, on) }

    /**
     * Whether automatic setup already ran for this exact need ([signature]: what was missing), so
     * reopening the project doesn't start it again; a new need (another stack, a removed runtime) does.
     */
    fun autoRan(context: Context, dir: File, signature: String): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("ran:" + dir.canonicalPath, null) == signature
    fun markAutoRan(context: Context, dir: File, signature: String) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putString("ran:" + dir.canonicalPath, signature) }
    fun dismissed(context: Context, dir: File): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(dir.canonicalPath, false)
    fun dismiss(context: Context, dir: File) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean(dir.canonicalPath, true) }
}

/** What a project still needs, in one line: "Needs Bun and pnpm · dependencies not installed". */
internal fun setupSummary(stacks: List<DetectedStack>, prefix: File): String {
    val runtimes = stacks.flatMap { it.missingPackages(prefix) }.distinct().map(Runtimes::name).distinct()
    val deps = stacks.any { it.installed == false }
    return listOfNotNull(
        runtimes.takeIf { it.isNotEmpty() }?.let { "Needs " + if (it.size == 1) it[0] else it.dropLast(1).joinToString() + " and " + it.last() },
        if (deps) "dependencies not installed" else null,
    ).joinToString(" · ").replaceFirstChar { it.uppercase() }
}

/** Shown on the project when it needs runtimes or dependencies (e.g. right after cloning). */
@Composable
fun SetupBanner(missing: List<DetectedStack>, prefix: File, onInstall: () -> Unit, onDetails: () -> Unit, onDismiss: () -> Unit) {
    val tones = AppColors.status
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).clip(RoundedCornerShape(16.dp))
            .background(tones.warningContainer).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            missing.joinToString { it.name } + ": " + setupSummary(missing, prefix).replaceFirstChar { it.lowercase() },
            style = MaterialTheme.typography.bodyMedium, color = tones.onWarningContainer,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = onInstall) { Text("Install") }
            TextButton(onClick = onDetails) { Text("Details") }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onDismiss) { Text("Not now") }
        }
    }
}

/**
 * The project's tech stacks, found from their manifests, each with what installing it does
 * (toolchain packages, then commands in its folder) and anything that won't work on the phone.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun ProjectSetupSheet(root: File, stacks: List<DetectedStack>, onDismiss: () -> Unit, onRun: (command: String, name: String) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefix = androidx.compose.runtime.remember { dev.yurlungr.app.runtime.RuntimePaths(context).prefix }
    val installable = stacks.filter { it.canInstall }
    var auto by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(SetupBannerPrefs.auto(context)) }
    AppSheet(
        "Project setup",
        onDismiss = onDismiss,
        subtitle = if (stacks.isEmpty()) "No known tech stack found in ${root.name}."
        else "${stacks.size} stack${if (stacks.size == 1) "" else "s"} found. Installing runs in a terminal, with a log.",
        icon = Ph.Wrench,
        tile = AppColors.tiles.lime,
        actions = if (installable.size > 1) {
            {
                Button(onClick = { onRun(installCommand(root, installable), "Install dependencies · ${root.name}") },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                    Text("Install all (${installable.size})")
                }
            }
        } else null,
    ) {
        if (stacks.isEmpty()) {
            Text(
                "Detected: Node.js (npm, yarn, pnpm: Expo, React Native, Vite, Next.js…), Android/Kotlin (Gradle), Flutter and Dart, " +
                    "Python, Rust, Go, Ruby, PHP, Java (Maven) and Swift, by their manifest files in the project or its app folders.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // Opt-in: setting up starts a terminal on its own when a project is opened.
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .clickable { auto = !auto; SetupBannerPrefs.setAuto(context, auto) }.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Automatic setup", style = MaterialTheme.typography.titleSmall)
                Text("When you open a project that needs runtimes or dependencies, install them right away (once per need).",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(10.dp))
            androidx.compose.material3.Switch(auto, { auto = it; SetupBannerPrefs.setAuto(context, it) })
        }
        for (s in stacks) StackCard(root, s, prefix, onRun)
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun StackCard(root: File, s: DetectedStack, prefix: File, onRun: (String, String) -> Unit) {
    val missingRuntimes = androidx.compose.runtime.remember(s) { s.missingPackages(prefix) }
    val colors = MaterialTheme.colorScheme
    val tones = AppColors.status
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(colors.surfaceContainerHigh).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(s.name, style = MaterialTheme.typography.titleSmall)
                Text(s.evidence, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant, fontFamily = FontFamily.Monospace)
            }
            val (label, fg, bg) = when {
                s.unsupported != null -> Triple("Can't run here", colors.onErrorContainer, colors.errorContainer)
                missingRuntimes.isNotEmpty() -> Triple("Needs runtime", tones.onWarningContainer, tones.warningContainer)
                s.installed == true -> Triple("Installed", tones.onSuccessContainer, tones.successContainer)
                s.installed == false -> Triple("Not installed", tones.onWarningContainer, tones.warningContainer)
                else -> Triple("Unknown", colors.onSurfaceVariant, colors.surfaceContainer)
            }
            Text(label, style = MaterialTheme.typography.labelSmall, color = fg,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(bg).padding(horizontal = 8.dp, vertical = 3.dp))
        }
        if (s.tags.isNotEmpty()) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (t in s.tags.filter { it != "workspaces" }) {
                    Text(t, style = MaterialTheme.typography.labelSmall, color = colors.onSurface,
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(colors.surfaceContainer).padding(horizontal = 8.dp, vertical = 3.dp))
                }
                if ("workspaces" in s.tags) Text("monorepo: installs once at the root", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
            }
        }
        s.unsupported?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant) }
        if (s.packages.isNotEmpty() && s.unsupported == null) {
            // Each runtime the stack needs: ✓ installed, or ○ installed when you tap Install.
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (pkg in s.packages) {
                    val missing = pkg in missingRuntimes
                    Row(
                        Modifier.clip(RoundedCornerShape(50)).background(if (missing) tones.warningContainer else colors.surfaceContainer)
                            .padding(horizontal = 9.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        PhIcon(if (missing) Ph.DownloadSimple else Ph.CheckCircle, null, size = 13.dp,
                            tint = if (missing) tones.onWarningContainer else tones.success)
                        Spacer(Modifier.width(5.dp))
                        Text(Runtimes.name(pkg), style = MaterialTheme.typography.labelSmall,
                            color = if (missing) tones.onWarningContainer else colors.onSurface)
                    }
                }
            }
        }
        if (s.canInstall) {
            // Exactly what will run, so nothing surprising happens.
            Text(
                (if (s.packages.isNotEmpty()) "pkg: ${s.packages.joinToString(" ")}\n" else "") + s.steps.joinToString("\n") { "$ $it" },
                fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp, color = Color(0xFFE6EDF3),
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color(0xFF0D1117)).padding(10.dp),
            )
        }
        for (n in s.notes) {
            Row {
                PhIcon(Ph.Info, null, size = 14.dp, tint = colors.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
                Spacer(Modifier.width(6.dp))
                Text(n, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
        }
        if (s.canInstall) {
            FilledTonalButton(onClick = { onRun(installCommand(root, listOf(s)), "Install ${s.name} · ${root.name}") }) {
                Text(when { missingRuntimes.isNotEmpty() -> "Install runtime & dependencies"; s.installed == true -> "Reinstall"; else -> "Install" })
            }
        }
    }
}
