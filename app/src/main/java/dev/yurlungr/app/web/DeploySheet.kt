package dev.yurlungr.app.web

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.yurlungr.app.editor.FileExport
import dev.yurlungr.app.runtime.RuntimePaths
import dev.yurlungr.app.terminal.TerminalSessions
import dev.yurlungr.app.ui.AppColors
import dev.yurlungr.app.ui.AppSheet
import dev.yurlungr.app.ui.Chip
import dev.yurlungr.app.ui.MenuChip
import dev.yurlungr.app.ui.Ph
import dev.yurlungr.app.ui.PhIcon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/** What's in the output folder right now. */
private enum class OutputState { Checking, Missing, NoIndex, Ready, Outside }

/**
 * Build & deploy for a web project: build it in a terminal, preview the build as a static site, save it
 * as a zip, or publish it to Cloudflare Pages, Netlify or Vercel with a token kept in the app.
 */
@Composable
internal fun BuildDeploySheet(
    root: File,
    projects: List<WebProject>,
    onDismiss: () -> Unit,
    runInTerminal: (command: String, name: String) -> Unit,
    onPreview: (url: String, title: String) -> Unit,
    onOpenTerminal: () -> Unit,
) {
    val context = LocalContext.current
    val paths = remember { RuntimePaths(context) }
    val scope = rememberCoroutineScope()
    val saveFile = FileExport.rememberSaveFile()
    val colors = MaterialTheme.colorScheme

    var selectedPath by remember(root.path) {
        mutableStateOf((projects.firstOrNull { WebDeploy.buildScript(it) != null } ?: projects.first()).directory.path)
    }
    val project = projects.firstOrNull { it.directory.path == selectedPath } ?: projects.first()
    val dir = project.directory
    val detected = remember(dir.path) { mutableStateOf<String?>(null) }
    var outRel by remember(dir.path) { mutableStateOf("") }
    var outState by remember(dir.path) { mutableStateOf(OutputState.Checking) }
    var checkTick by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var hasNode by remember { mutableStateOf(true) }

    LaunchedEffect(dir.path) {
        val (found, saved) = withContext(Dispatchers.IO) {
            WebDeploy.detectOutput(dir, WebDeploy.outputCandidates(project.kind, project.framework)) to DeployPrefs.output(context, dir)
        }
        detected.value = found
        outRel = saved ?: found
        hasNode = withContext(Dispatchers.IO) { File(paths.bin, "npx").canExecute() }
    }
    LaunchedEffect(dir.path, outRel, checkTick) {
        if (detected.value == null) return@LaunchedEffect
        outState = withContext(Dispatchers.IO) {
            val f = WebDeploy.resolveOutput(dir, outRel)
            when {
                f == null -> OutputState.Outside
                !f.isDirectory -> OutputState.Missing
                !File(f, "index.html").isFile -> OutputState.NoIndex
                else -> OutputState.Ready
            }
        }
    }

    /** The output folder, remembered for this project when it isn't the detected one; null (and says why) if unusable. */
    suspend fun output(): File? {
        val (f, inside) = withContext(Dispatchers.IO) {
            DeployPrefs.setOutput(context, dir, outRel.trim().takeIf { it != detected.value })
            val resolved = WebDeploy.resolveOutput(dir, outRel)
            resolved?.takeIf { it.isDirectory } to (resolved != null)
        }
        if (f == null) error = if (!inside) "Choose a folder inside the project." else "Nothing at ${outRel.ifBlank { "." }} yet. Build first."
        checkTick++
        return f
    }

    AppSheet(
        "Build & deploy",
        onDismiss = onDismiss,
        subtitle = "${project.framework} · ${dir.name}",
        icon = Ph.CloudArrowUp,
        tile = AppColors.tiles.sky,
        dismissable = busy == null,
    ) {
        if (projects.size > 1) {
            MenuChip("App: ${dir.relativeTo(root.canonicalFile).path.ifEmpty { root.name }}") { close ->
                projects.forEach { candidate ->
                    DropdownMenuItem(
                        text = { Text("${candidate.directory.relativeTo(root.canonicalFile).path.ifEmpty { root.name }} · ${candidate.framework}") },
                        onClick = { selectedPath = candidate.directory.path; error = null; close() },
                    )
                }
            }
        }

        // Build
        Text("Build", style = MaterialTheme.typography.titleMedium)
        val build = WebDeploy.buildCommand(project)
        if (build != null) {
            Text(build.substringAfterLast("&& "), fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = colors.onSurfaceVariant)
            if (project.kind == WebKind.Next) Text("Next.js writes a static site to out only with output: 'export' in next.config.", style = MaterialTheme.typography.bodySmall, color = colors.tertiary)
            if (project.kind == WebKind.Nuxt) Text("For a static site, use nuxt generate (output in .output/public).", style = MaterialTheme.typography.bodySmall, color = colors.tertiary)
            Button(onClick = { onDismiss(); runInTerminal(build, "Build · ${dir.name}") }, modifier = Modifier.fillMaxWidth(), enabled = busy == null) {
                Text(if (project.dependenciesPresent) "Build" else "Install & build")
            }
        } else {
            Text("No build script in package.json. The folder below is published as it is.", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }

        // Output folder
        OutlinedTextField(
            outRel, { outRel = it.take(200); error = null },
            label = { Text("Output folder") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            supportingText = {
                Text(when (outState) {
                    OutputState.Checking -> "Checking…"
                    OutputState.Missing -> "Not built yet"
                    OutputState.NoIndex -> "No index.html in it"
                    OutputState.Ready -> "Ready"
                    OutputState.Outside -> "Must be a folder inside the app"
                } + (detected.value?.takeIf { it != outRel.trim() }?.let { " · detected: $it" } ?: ""))
            },
            trailingIcon = {
                if (detected.value != null && detected.value != outRel.trim()) {
                    IconButton(onClick = { outRel = detected.value.orEmpty() }) { PhIcon(Ph.ArrowCounterClockwise, "Use the detected folder", size = 18.dp) }
                }
            },
        )
        if (outRel.trim().trim('/').let { it.isEmpty() || it == "." }) {
            Text("The whole app folder is published. Keep secrets such as .env out of it.", style = MaterialTheme.typography.bodySmall, color = AppColors.status.warning)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(
                enabled = busy == null && outState == OutputState.Ready,
                modifier = Modifier.weight(1f),
                onClick = {
                    error = null
                    scope.launch {
                        val out = output() ?: return@launch
                        busy = "preview"
                        try {
                            val title = "Build · ${dir.name}"
                            val running = WebRunSessions.get(out)
                            val run = if (running?.busy == true) running else {
                                val port = withContext(Dispatchers.IO) { WebQuickRun.freePort(4173) }
                                val config = WebRunConfig(WebProject(out, out, WebKind.Static, "Static build", manager = null), WebPackageManager.Npm, "", port, install = false, openBrowser = false)
                                withContext(Dispatchers.IO) { runCatching { config.validate() } }.exceptionOrNull()?.let { error = it.message; return@launch }
                                WebRunSessions.start(context, out, config)
                            }
                            // The static server answers within a second or two; give a slow phone some slack.
                            withTimeoutOrNull(30_000) { snapshotFlow { run.state == WebRunState.Running || run.finalized }.first { it } }
                            if (run.state == WebRunState.Running) { onDismiss(); onPreview(run.url, title) }
                            else error = run.message.ifBlank { "The preview server didn't start. Check its terminal." }
                        } finally { busy = null }
                    }
                },
            ) { Text(if (busy == "preview") "Starting…" else "Preview") }
            OutlinedButton(
                enabled = busy == null && (outState == OutputState.Ready || outState == OutputState.NoIndex),
                modifier = Modifier.weight(1f),
                onClick = {
                    error = null
                    scope.launch {
                        val out = output() ?: return@launch
                        busy = "zip"
                        try {
                            val name = "${dir.name}-${if (out.canonicalPath == dir.canonicalPath) "site" else out.name}.zip".replace(Regex("[^A-Za-z0-9._-]"), "-")
                            val result = withContext(Dispatchers.IO) {
                                runCatching {
                                    // One zip at a time in the cache: the copy the picker saves is what lasts.
                                    val folder = File(context.cacheDir, "exports").apply { deleteRecursively(); mkdirs() }
                                    val zip = File(folder, name)
                                    zip to WebDeploy.zipFolder(out, zip)
                                }
                            }
                            result.onSuccess { (zip, summary) ->
                                if (summary.skippedLinks > 0) Toast.makeText(context, "Skipped ${summary.skippedLinks} linked item(s)", Toast.LENGTH_SHORT).show()
                                saveFile(zip)
                            }.onFailure { error = "Couldn't zip it: ${it.message}" }
                        } finally { busy = null }
                    }
                },
            ) { Text(if (busy == "zip") "Zipping…" else "Save as zip") }
        }

        HorizontalDivider(Modifier.padding(vertical = 4.dp), color = colors.outlineVariant)
        DeploySection(dir, busy == null, hasNode, error = { error = it }, runInTerminal = { c, n -> onDismiss(); runInTerminal(c, n) }) { provider, site ->
            error = null
            scope.launch {
                val out = output() ?: return@launch
                busy = "deploy"
                try {
                    val values = withContext(Dispatchers.IO) {
                        DeployPrefs.setSite(context, dir, provider, site)
                        DeployPrefs.setLastProvider(context, dir, provider)
                        DeployPrefs.values(context, provider)
                    }
                    val env = WebDeploy.deployEnv(provider, values).getOrElse { error = it.message; return@launch }
                    // The CLI runs from app-owned folders, never the project's (see deployCommand).
                    val cliDir = File(context.cacheDir, "deploy-cli")
                    val command = WebDeploy.deployCommand(provider, dir, cliDir, out, site, npm = File(paths.bin, "npm").path)
                        ?: run { error = if (site.isBlank()) "Enter the ${provider.siteLabel.lowercase()}." else "Use letters, digits, dots, dashes or underscores in the name."; return@launch }
                    val workDir = WebDeploy.deployWorkDir(cliDir, dir)
                    withContext(Dispatchers.IO) { workDir.mkdirs() }
                    runCatching { TerminalSessions.create(context, command, "Deploy · ${dir.name}", workingDirectory = workDir, extraEnv = env) }
                        .onSuccess { onDismiss(); onOpenTerminal() }
                        .onFailure { error = "Couldn't start the deploy: ${it.message}" }
                } finally { busy = null }
            }
        }
        error?.let { Text(it, color = colors.error, style = MaterialTheme.typography.bodySmall) }
    }
}

/** Provider, its saved keys (masked) and the site name; [onDeploy] gets the provider and site name. */
@Composable
private fun DeploySection(
    dir: File,
    enabled: Boolean,
    hasNode: Boolean,
    error: (String?) -> Unit,
    runInTerminal: (String, String) -> Unit,
    onDeploy: (DeployProvider, String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val colors = MaterialTheme.colorScheme
    var provider by remember(dir.path) { mutableStateOf(DeployProvider.Cloudflare) }
    var site by remember(dir.path, provider) { mutableStateOf("") }
    /** env name → masked saved value; never the value itself. */
    var masked by remember(provider) { mutableStateOf<Map<String, String>?>(null) }
    var reload by remember { mutableIntStateOf(0) }

    LaunchedEffect(dir.path) {
        withContext(Dispatchers.IO) { DeployPrefs.lastProvider(context, dir) }?.let { provider = it }
    }
    LaunchedEffect(dir.path, provider, reload) {
        val (m, s) = withContext(Dispatchers.IO) {
            provider.keys.mapNotNull { k -> DeployPrefs.secret(context, provider, k.env)?.let { k.env to if (k.secret) WebDeploy.mask(it) else it } }.toMap() to
                DeployPrefs.site(context, dir, provider)
        }
        masked = m
        if (site.isEmpty()) site = s
    }

    Text("Deploy", style = MaterialTheme.typography.titleMedium)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        DeployProvider.entries.forEach { p -> Chip(p.label, onClick = { provider = p; error(null) }, selected = p == provider) }
    }
    Text(provider.tokenHelp, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
    for (k in provider.keys) {
        key(provider, k.env) {
            KeyField(k, masked?.get(k.env), enabled) { value ->
                scope.launch {
                    withContext(Dispatchers.IO) { DeployPrefs.setSecret(context, provider, k.env, value) }
                    reload++
                }
            }
        }
    }
    if (masked.orEmpty().isNotEmpty()) {
        TextButton(onClick = {
            scope.launch { withContext(Dispatchers.IO) { DeployPrefs.forget(context, provider) }; reload++ }
        }, enabled = enabled) { Text("Forget saved ${provider.label} keys") }
    }
    OutlinedTextField(site, { site = it.trim().take(100) }, label = { Text(provider.siteLabel) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        supportingText = { Text("Remembered for this project.") })
    if (!hasNode) {
        Text("Deploying uses npx, which comes with Node.js.", style = MaterialTheme.typography.bodySmall, color = colors.error)
        OutlinedButton(onClick = { runInTerminal("pkg install -y nodejs-lts", "Install Node.js") }, modifier = Modifier.fillMaxWidth()) { Text("Install Node.js") }
    }
    Button(
        onClick = { onDeploy(provider, site) },
        enabled = enabled && hasNode && masked != null && provider.keys.all { masked.orEmpty().containsKey(it.env) } && (!provider.siteRequired || site.isNotBlank()),
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Deploy to ${provider.label}") }
    Text("Runs in a terminal so you can follow it. Keys are stored encrypted in the app, never in the project.",
        style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
}

/** A saved key shows masked with Change; otherwise a field (hidden while typing for secrets) and Save. */
@Composable
private fun KeyField(key: DeployKey, saved: String?, enabled: Boolean, onSave: (String) -> Unit) {
    var editing by remember(saved) { mutableStateOf(saved == null) }
    var value by remember { mutableStateOf("") }
    var show by remember { mutableStateOf(false) }
    if (!editing && saved != null) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(key.label, style = MaterialTheme.typography.labelLarge)
                Text(saved, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = { editing = true; value = "" }, enabled = enabled) { Text("Change") }
        }
        return
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value, { value = it.trim() }, label = { Text(key.label) }, singleLine = true, modifier = Modifier.weight(1f),
            visualTransformation = if (key.secret && !show) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = if (key.secret) KeyboardType.Password else KeyboardType.Ascii),
            trailingIcon = if (key.secret) ({ IconButton(onClick = { show = !show }) { PhIcon(Ph.Eye, if (show) "Hide" else "Show", size = 18.dp) } }) else null,
        )
        Button(onClick = { onSave(value); value = ""; show = false }, enabled = enabled && value.isNotBlank()) { Text("Save") }
    }
    if (saved != null) TextButton(onClick = { editing = false; value = "" }) { Text("Keep saved ${key.label.lowercase()}") }
}
