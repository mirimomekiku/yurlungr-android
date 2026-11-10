package dev.yurlungr.app.projects

import android.content.Context
import androidx.compose.foundation.background
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

/** Projects whose "dependencies aren't installed" banner the user dismissed. */
internal object SetupBannerPrefs {
    private const val PREFS = "project_setup"
    fun dismissed(context: Context, dir: File): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(dir.canonicalPath, false)
    fun dismiss(context: Context, dir: File) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean(dir.canonicalPath, true) }
}

/** Shown on the project when a stack's dependencies aren't installed (e.g. right after cloning). */
@Composable
fun SetupBanner(missing: List<DetectedStack>, onInstall: () -> Unit, onDetails: () -> Unit, onDismiss: () -> Unit) {
    val tones = AppColors.status
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).clip(RoundedCornerShape(16.dp))
            .background(tones.warningContainer).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            "Dependencies aren't installed: " + missing.joinToString { it.name },
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
@Composable
fun ProjectSetupSheet(root: File, stacks: List<DetectedStack>, onDismiss: () -> Unit, onRun: (command: String, name: String) -> Unit) {
    val installable = stacks.filter { it.canInstall }
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
        for (s in stacks) StackCard(root, s, onRun)
    }
}

@Composable
private fun StackCard(root: File, s: DetectedStack, onRun: (String, String) -> Unit) {
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
                Text(if (s.installed == true) "Reinstall" else "Install")
            }
        }
    }
}
