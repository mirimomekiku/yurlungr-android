package dev.yurlungr.app.projects

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.yurlungr.app.ui.AppColors
import dev.yurlungr.app.ui.AppSheet
import dev.yurlungr.app.ui.Ph
import dev.yurlungr.app.ui.PhIcon
import java.io.File

/**
 * Whose repository a clone URL points at. Cloning someone else's marks the folder untrusted
 * ([ProjectTrust]); the user's own (their GitHub account or one of their organisations) keeps
 * the usual install-and-run. When it can't be told (not GitHub, signed out, lists not loaded),
 * it counts as someone else's: holding back is the safe mistake.
 */
object CloneTrust {
    /** The logins that are the user's: the signed-in account and its organisations, lower case. */
    fun owners(account: String?, orgs: List<String>, repos: List<GhRepo>): Set<String> =
        (listOfNotNull(account) + orgs + repos.map { it.owner }).map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()

    /** Whether [url] is a GitHub repository of one of [owners]. */
    fun isOwned(url: String, owners: Set<String>): Boolean {
        val owner = Git.githubSlug(url)?.substringBefore('/') ?: return false
        return owner in owners
    }
}

/** What an untrusted project doesn't get, in the user's words (see [ProjectTrust]). */
internal val HELD_BACK = listOf(
    "Installing and starting it by itself: after cloning, or the automatic setup when it opens (npm install scripts, the dev server)",
    "Language servers (they can run its build scripts) and its Prettier config and plugins",
    "Deploy tools from its node_modules or with its .npmrc",
    "Its committed .venv first on the PATH",
    "Git hooks when checking out pull requests",
)

/**
 * Shown on an untrusted project: why it's held back, and Trust. [compact] is the one-line
 * version above the tabs; the Overview shows it in full.
 */
@Composable
internal fun UntrustedBanner(root: File, compact: Boolean, onTrust: () -> Unit, modifier: Modifier = Modifier) {
    val tones = AppColors.status
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(if (compact) 14.dp else 20.dp)).background(tones.warningContainer)
            .padding(start = 12.dp, end = 6.dp, top = if (compact) 4.dp else 12.dp, bottom = if (compact) 4.dp else 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PhIcon(Ph.Warning, null, size = 16.dp, tint = tones.onWarningContainer)
            Spacer(Modifier.width(8.dp))
            Text(
                if (compact) "Not trusted yet: nothing in it runs on its own."
                else "${root.name} isn't trusted yet",
                style = if (compact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.titleSmall,
                color = tones.onWarningContainer, modifier = Modifier.weight(1f),
            )
            if (compact) TextButton(onClick = onTrust) { Text("Trust…") }
        }
        if (!compact) {
            Text(
                "It was cloned from someone else's repository. Until you trust it, Yurlungr holds back what would run its code by itself:",
                style = MaterialTheme.typography.bodySmall, color = tones.onWarningContainer,
            )
            Text(HELD_BACK.joinToString("\n") { "• $it" }, style = MaterialTheme.typography.bodySmall, color = tones.onWarningContainer)
            Text(
                "What you start yourself (Run, Install, scripts, the terminal, agents) still runs it. Look through it first, then trust it.",
                style = MaterialTheme.typography.bodySmall, color = tones.onWarningContainer,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Button(onClick = onTrust) {
                    PhIcon(Ph.ShieldCheck, null, size = 16.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Trust this project")
                }
            }
        }
    }
}

/** Confirms trusting [root] (the untrusted project folder, maybe a parent of the one open). */
@Composable
internal fun TrustSheet(root: File, onDismiss: () -> Unit, onTrusted: () -> Unit) {
    val context = LocalContext.current
    AppSheet(
        "Trust ${root.name}?", onDismiss = onDismiss, icon = Ph.ShieldCheck, tile = AppColors.tiles.peach,
        subtitle = "Only for code you've looked through, or whose authors you trust.",
        actions = {
            OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) { Text("Cancel") }
            Button(onClick = { ProjectTrust.trust(context, root); onTrusted() }, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) { Text("Trust") }
        },
    ) {
        Text("Trusting it lets its own code run without asking:", style = MaterialTheme.typography.bodyMedium)
        Text(HELD_BACK.joinToString("\n") { "• $it" }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("You can mark it untrusted again from the project's menu (tap its name).",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Confirms marking [dir] untrusted again. */
@Composable
internal fun UntrustSheet(dir: File, onDismiss: () -> Unit, onDone: () -> Unit) {
    val context = LocalContext.current
    AppSheet(
        "Stop trusting ${dir.name}?", onDismiss = onDismiss, icon = Ph.Warning, tile = AppColors.tiles.peach,
        actions = {
            OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) { Text("Cancel") }
            Button(
                onClick = { ProjectTrust.markUntrusted(context, dir); onDone() },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
                modifier = Modifier.weight(1f).heightIn(min = 52.dp),
            ) { Text("Mark untrusted") }
        },
    ) {
        Text("From now on Yurlungr holds back what would run its code by itself:", style = MaterialTheme.typography.bodyMedium)
        Text(HELD_BACK.joinToString("\n") { "• $it" }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Language servers or a dev server already running keep running until they're stopped.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Normal)
    }
}
