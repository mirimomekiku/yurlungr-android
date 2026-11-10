package dev.yurlungr.app.setup

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.buildAnnotatedString
import dev.yurlungr.app.editor.EditorPrefs
import dev.yurlungr.app.editor.EditorTheme
import dev.yurlungr.app.editor.EditorThemes
import dev.yurlungr.app.editor.Highlighter
import dev.yurlungr.app.editor.Languages
import dev.yurlungr.app.editor.TokenKind
import dev.yurlungr.app.projects.GhStatus
import dev.yurlungr.app.projects.Git
import dev.yurlungr.app.runtime.RuntimePaths
import dev.yurlungr.app.ui.AppColors
import dev.yurlungr.app.ui.Group
import dev.yurlungr.app.ui.GroupRow
import dev.yurlungr.app.ui.Ph
import dev.yurlungr.app.ui.PhIcon
import dev.yurlungr.app.ui.ScreenHeader
import dev.yurlungr.app.ui.SectionLabel
import dev.yurlungr.app.ui.SkeletonLine
import dev.yurlungr.app.ui.WaveDots
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Settings as a grouped list: Runtime, Keep agents running, Accounts, About. Rows come from the
 * same doctor checks as the System checks screen; anything that needs attention shows amber with a Fix.
 */
@Composable
fun SettingsScreen(
    vm: SetupViewModel,
    refreshTick: Int,
    onBack: () -> Unit,
    onSystemChecks: () -> Unit,
    onAgents: () -> Unit,
    runInTerminal: (command: String, name: String) -> Unit,
) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val paths = remember { RuntimePaths(context) }
    var showPhantomHelp by remember { mutableStateOf(false) }
    var showLicenses by remember { mutableStateOf(false) }
    var showThemes by remember { mutableStateOf(false) }
    LaunchedEffect(context) { EditorPrefs.load(context) }
    var gh by remember { mutableStateOf<GhStatus?>(null) }
    LaunchedEffect(refreshTick) { if (paths.isBootstrapInstalled) gh = Git(paths).ghStatus() }
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"
    }
    val checks = vm.checks.associateBy { it.id }
    val tones = AppColors.status
    val tiles = AppColors.tiles

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding(),
    ) {
        ScreenHeader("Settings", onBack) {
            IconButton(onClick = vm::refresh, enabled = !vm.checking) {
                if (vm.checking) WaveDots(dotSize = 4.dp) else PhIcon(Ph.ArrowClockwise, "Re-run checks", size = 20.dp)
            }
        }
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item { SectionLabel("Runtime") }
            item {
                Group {
                    // One entry for everything the checks screen covers (Linux environment, SDK, tools).
                    val problems = vm.checks.filter { it.status == CheckStatus.Warn || it.status == CheckStatus.Fail }
                    val worst = vm.checks.firstOrNull { it.status == CheckStatus.Fail } ?: problems.firstOrNull()
                    GroupRow(
                        "System runtime",
                        subtitle = when {
                            vm.checks.isEmpty() -> "Checking…"
                            checks["bootstrap"]?.status != CheckStatus.Pass -> "Linux environment not installed"
                            problems.isNotEmpty() -> "${problems.size} to fix · ${problems.joinToString { it.title }}"
                            else -> listOfNotNull(
                                "All ${vm.checks.size} checks pass",
                                checks["storage"]?.detail,
                                if (checks["android-sdk"]?.status == CheckStatus.Pass) "Android SDK ready" else "no Android SDK",
                            ).joinToString(" · ")
                        },
                        icon = Ph.TerminalWindow,
                        tile = if (problems.isEmpty()) tiles.sky else tiles.peach,
                        iconTint = worst.tint(),
                        subtitleColor = worst.subtitleColor(),
                        onClick = onSystemChecks,
                        trailing = { if (problems.isNotEmpty()) FixLabel("Review") else Chevron() },
                    )
                    val agents = checks["agents"]
                    GroupRow(
                        "Coding agents",
                        subtitle = agents?.detail ?: "Checking…",
                        icon = Ph.Sparkle,
                        tile = tiles.lilac,
                        iconTint = agents.tint(),
                        onClick = onAgents,
                    )
                }
            }

            item { SectionLabel("Keep agents running") }
            item {
                Group {
                    val n = checks["notifications"]
                    GroupRow(
                        "Notifications",
                        subtitle = if (n?.status == CheckStatus.Warn) "Off · you won't hear when an agent finishes" else "When an agent finishes or needs you",
                        icon = Ph.Bell,
                        tile = if (n?.status == CheckStatus.Warn) tiles.peach else tiles.lime,
                        iconTint = n.tint(),
                        subtitleColor = n.subtitleColor(),
                        onClick = { openNotificationSettings(context) },
                        trailing = { if (n?.status == CheckStatus.Warn) FixLabel("Turn on") else Chevron() },
                    )
                    val b = checks["battery"]
                    GroupRow(
                        "Battery optimization",
                        subtitle = if (b?.status == CheckStatus.Warn) "On · Android may stop agents" else "Unrestricted",
                        icon = if (b?.status == CheckStatus.Warn) Ph.Warning else Ph.Lightning,
                        tile = if (b?.status == CheckStatus.Warn) tiles.peach else tiles.lime,
                        iconTint = b.tint(),
                        subtitleColor = b.subtitleColor(),
                        onClick = { requestBatteryExemption(context) },
                        trailing = { if (b?.status == CheckStatus.Warn) FixLabel("Fix") else Chevron() },
                    )
                    val p = checks["phantom"]
                    GroupRow(
                        "Background process limit",
                        subtitle = when (p?.status) {
                            CheckStatus.Pass -> p.detail
                            CheckStatus.Warn -> "Android may kill builds and agents · one-time fix"
                            else -> "Checking…"
                        },
                        icon = Ph.ShieldCheck,
                        tile = if (p?.status == CheckStatus.Warn) tiles.peach else tiles.lime,
                        iconTint = p.tint(),
                        subtitleColor = p.subtitleColor(),
                        onClick = { showPhantomHelp = true },
                        trailing = { if (p?.status == CheckStatus.Warn) FixLabel("Fix") else Chevron() },
                    )
                }
            }

            item { SectionLabel("Editor") }
            item {
                Group {
                    val theme = EditorPrefs.theme
                    GroupRow(
                        "Color theme",
                        subtitle = theme.name,
                        icon = Ph.PencilSimple,
                        tile = tiles.lilac,
                        onClick = { showThemes = true },
                        trailing = { ThemeSwatch(theme); Spacer(Modifier.width(8.dp)); PhIcon(Ph.CaretRight, null, size = 14.dp, tint = MaterialTheme.colorScheme.outline) },
                    )
                    GroupRow(
                        "Text size",
                        subtitle = "${EditorPrefs.fontSize.toInt()} sp",
                        icon = Ph.FileText,
                        tile = tiles.sky,
                        trailing = {
                            TextButton(onClick = { EditorPrefs.setFontSize(context, EditorPrefs.fontSize - 1) }, enabled = EditorPrefs.fontSize > 9f) { Text("A−") }
                            TextButton(onClick = { EditorPrefs.setFontSize(context, EditorPrefs.fontSize + 1) }, enabled = EditorPrefs.fontSize < 22f) { Text("A+") }
                        },
                    )
                }
            }

            item { SectionLabel("Accounts") }
            item {
                Group {
                    val g = gh
                    GroupRow(
                        "GitHub",
                        subtitle = when {
                            !paths.isBootstrapInstalled -> "Needs the Linux environment"
                            g == null -> null
                            !g.installed -> "GitHub CLI not installed"
                            g.problem == dev.yurlungr.app.projects.GhProblem.Broken -> "GitHub CLI isn't working: ${g.detail ?: "unknown error"}"
                            g.problem == dev.yurlungr.app.projects.GhProblem.Unreachable ->
                                "Can't reach GitHub right now" + (g.account?.let { " · signed in as $it" } ?: "")
                            g.problem == dev.yurlungr.app.projects.GhProblem.SignInExpired -> "Sign-in expired · sign in again"
                            g.signedIn -> "Signed in" + (g.account?.let { " as $it" } ?: "")
                            else -> "Not signed in · needed to push and clone private repos"
                        },
                        icon = Ph.GithubLogo,
                        tile = tiles.sky,
                        onClick = if (!paths.isBootstrapInstalled || g == null) null else {
                            {
                                when {
                                    !g.installed -> runInTerminal("pkg install -y gh", "Install gh")
                                    g.problem == dev.yurlungr.app.projects.GhProblem.Broken -> runInTerminal("pkg reinstall -y gh", "Reinstall gh")
                                    !g.signedIn -> runInTerminal(
                                        "echo 'Copy the one-time code, then press Enter: the GitHub page opens in your browser.'; " +
                                            "gh auth login --hostname github.com --git-protocol https --web && gh auth setup-git",
                                        "GitHub sign-in",
                                    )
                                    else -> runInTerminal("gh auth status", "GitHub")
                                }
                            }
                        },
                        trailing = {
                            when {
                                g == null && paths.isBootstrapInstalled -> SkeletonLine(Modifier.padding(start = 8.dp), width = 0.25f, height = 10.dp)
                                g != null && g.problem == dev.yurlungr.app.projects.GhProblem.Broken -> FixLabel("Reinstall")
                                g != null && (!g.installed || !g.signedIn) -> FixLabel(if (!g.installed) "Install" else "Sign in")
                                g != null -> Chevron()
                            }
                        },
                    )
                }
            }

            item { SectionLabel("About") }
            item {
                Group {
                    GroupRow("Version", trailing = { Text(version, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) })
                    GroupRow("Open-source licenses", onClick = { showLicenses = true })
                }
            }
        }
    }

    if (showPhantomHelp) {
        PhantomHelpDialog(
            onDismiss = { showPhantomHelp = false },
            onMarkDone = { done ->
                vm.setPhantomFixDone(done)
                showPhantomHelp = false
            },
        )
    }
    if (showLicenses) LicensesDialog { showLicenses = false }
    if (showThemes) ThemeDialog(onDismiss = { showThemes = false }) { EditorPrefs.setTheme(context, it) }
}

@Composable
private fun Check?.tint(): Color = when (this?.status) {
    CheckStatus.Pass -> MaterialTheme.colorScheme.primary
    CheckStatus.Warn -> AppColors.status.warning
    CheckStatus.Fail -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.outline
}

@Composable
private fun Check?.subtitleColor(): Color =
    if (this?.status == CheckStatus.Warn) AppColors.status.warning else MaterialTheme.colorScheme.onSurfaceVariant

@Composable
private fun FixLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun Chevron() = PhIcon(Ph.CaretRight, null, size = 14.dp, tint = MaterialTheme.colorScheme.outline)

@Composable
private fun ThemeSwatch(t: EditorTheme) {
    Row(
        Modifier.clip(RoundedCornerShape(8.dp)).background(t.Bg).border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp)).padding(horizontal = 6.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        for (k in listOf(TokenKind.Keyword, TokenKind.Str, TokenKind.Function, TokenKind.Type)) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(t.style(k).color))
        }
    }
}

private const val PREVIEW = "// Greets the user\nfun greet(name: String): String {\n    val n = 3\n    return \"Hi, \$name!\"\n}"

/** Every theme as a live code sample; tap to choose. */
@Composable
private fun ThemeDialog(onDismiss: () -> Unit, onPick: (EditorTheme) -> Unit) {
    val tokens = remember { Highlighter.tokenize(PREVIEW, Languages.Kotlin) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Editor theme") },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                for (t in EditorThemes.all) {
                    val selected = t.id == EditorPrefs.theme.id
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .border(if (selected) 2.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
                            .background(t.Bg)
                            .clickable { onPick(t) },
                    ) {
                        Row(Modifier.fillMaxWidth().background(t.Bar).padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(t.name, color = t.Fg, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                            if (selected) PhIcon(Ph.CheckCircle, "Selected", size = 16.dp, tint = t.Accent)
                        }
                        val styled = remember(t) {
                            buildAnnotatedString {
                                append(PREVIEW)
                                for (tok in tokens) addStyle(t.style(tok.kind), tok.start, tok.end)
                            }
                        }
                        Text(styled, color = t.Fg, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 16.sp, modifier = Modifier.padding(10.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

/** Licenses of what's bundled in the APK (fonts, icons); runtime packages carry their own. */
@Composable
private fun LicensesDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var texts by remember { mutableStateOf<List<Pair<String, String>>?>(null) }
    LaunchedEffect(Unit) {
        texts = withContext(Dispatchers.IO) {
            context.assets.list("licenses").orEmpty().sorted().map { name ->
                name.substringBeforeLast('.') to context.assets.open("licenses/$name").bufferedReader().use { it.readText() }
            }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Open-source licenses") },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "The Linux environment is the Termux bootstrap (packages under their own licenses, see \$PREFIX/share/doc). " +
                        "The terminal view uses Termux's terminal libraries (Apache 2.0).",
                    style = MaterialTheme.typography.bodySmall,
                )
                for ((name, text) in texts.orEmpty()) {
                    Text(name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(text.trim(), fontFamily = FontFamily.Monospace, fontSize = 10.sp, lineHeight = 13.sp)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
