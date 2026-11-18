package dev.yurlungr.app.web

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.yurlungr.app.projects.Git
import dev.yurlungr.app.runtime.RuntimePaths
import dev.yurlungr.app.ui.MenuChip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.io.File

/**
 * The project's web server: what it is, where it stands (a coloured status, progress while it
 * installs or starts, the address once it's up) and what to do next: Open, Logs, and ⋮ for
 * Restart, Stop and the run options.
 */
@Composable
internal fun WebRunCard(root: File, onLogs: () -> Unit, onOptions: (() -> Unit)? = null, onPreview: ((String, String) -> Unit)? = null,
    /** Opens the API tester aimed at this server's port. */
    onApiTester: ((Int) -> Unit)? = null) {
    val context = LocalContext.current
    val run = WebRunSessions.get(root) ?: return
    var confirmation by remember(run.id) { mutableStateOf<String?>(null) }
    var menu by remember { mutableStateOf(false) }
    var sharing by remember { mutableStateOf(false) }
    if (sharing) TunnelSheet(run.config.port, "${run.config.project.framework} · ${run.config.project.directory.name}", onDismiss = { sharing = false }, onOpenTerminal = onLogs)
    val colors = MaterialTheme.colorScheme
    val tones = dev.yurlungr.app.ui.AppColors.status
    val tile = dev.yurlungr.app.ui.AppColors.tiles.lime
    val busy = run.busy && run.state != WebRunState.Running
    val failed = run.state in setOf(WebRunState.Failed, WebRunState.NeedsSetup, WebRunState.CompatibilityFailure)
    val (stateLabel, stateColor) = when (run.state) {
        WebRunState.Running -> "Running" to tones.success
        WebRunState.Installing -> "Installing dependencies" to tones.warning
        WebRunState.Starting -> "Starting" to tones.warning
        WebRunState.Compiling -> "Compiling" to tones.warning
        WebRunState.NeedsSetup -> "Needs setup" to colors.error
        WebRunState.CompatibilityFailure -> "Compatibility error" to colors.error
        WebRunState.Stopping -> "Stopping" to colors.onSurfaceVariant
        WebRunState.Stopped -> "Stopped" to colors.outline
        WebRunState.Failed -> "Failed" to colors.error
    }
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(22.dp)
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(shape).background(colors.surfaceContainer)
            .border(1.dp, if (failed) colors.error.copy(alpha = 0.5f) else colors.outlineVariant, shape),
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp, top = 12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            androidx.compose.foundation.layout.Box(
                Modifier.size(40.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(13.dp)).background(tile.fill),
                contentAlignment = androidx.compose.ui.Alignment.Center,
            ) { dev.yurlungr.app.ui.PhIcon(dev.yurlungr.app.ui.Ph.Globe, null, size = 20.dp, tint = tile.on) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("${run.config.project.framework} · ${run.config.project.directory.name}", style = MaterialTheme.typography.titleSmall,
                    maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    androidx.compose.foundation.layout.Box(Modifier.size(8.dp).clip(androidx.compose.foundation.shape.CircleShape).background(stateColor))
                    Spacer(Modifier.width(6.dp))
                    Text(stateLabel, style = MaterialTheme.typography.labelMedium, color = stateColor)
                }
            }
            androidx.compose.foundation.layout.Box {
                IconButton(onClick = { menu = true }) { dev.yurlungr.app.ui.PhIcon(dev.yurlungr.app.ui.Ph.DotsThreeVertical, "Server actions", size = 20.dp) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (run.state == WebRunState.Running) DropdownMenuItem(text = { Text("Open in browser") },
                        leadingIcon = { dev.yurlungr.app.ui.PhIcon(dev.yurlungr.app.ui.Ph.Globe, null, size = 18.dp) },
                        onClick = { menu = false; WebRunSessions.open(context, run) })
                    if (run.state == WebRunState.Running) DropdownMenuItem(
                        text = { Text(if (TunnelSessions.get(run.config.port)?.active == true) "Public link (on)" else "Share public link") },
                        leadingIcon = { dev.yurlungr.app.ui.PhIcon(dev.yurlungr.app.ui.Ph.Globe, null, size = 18.dp) },
                        onClick = { menu = false; sharing = true })
                    if (run.state == WebRunState.Running) onApiTester?.let { open ->
                        DropdownMenuItem(text = { Text("Test API") },
                            leadingIcon = { dev.yurlungr.app.ui.PhIcon(dev.yurlungr.app.ui.Ph.Lightning, null, size = 18.dp) },
                            onClick = { menu = false; open(run.config.port) })
                    }
                    DropdownMenuItem(text = { Text("Restart") }, enabled = run.state != WebRunState.Stopping,
                        leadingIcon = { dev.yurlungr.app.ui.PhIcon(dev.yurlungr.app.ui.Ph.ArrowClockwise, null, size = 18.dp) },
                        onClick = { menu = false; confirmation = "Restart" })
                    DropdownMenuItem(text = { Text("Stop") }, enabled = run.state.active && run.state != WebRunState.Stopping,
                        leadingIcon = { dev.yurlungr.app.ui.PhIcon(dev.yurlungr.app.ui.Ph.Stop, null, size = 18.dp) },
                        onClick = { menu = false; confirmation = "Stop" })
                    onOptions?.let { options ->
                        DropdownMenuItem(text = { Text("Run options") },
                            leadingIcon = { dev.yurlungr.app.ui.PhIcon(dev.yurlungr.app.ui.Ph.GearSix, null, size = 18.dp) },
                            onClick = { menu = false; options() })
                    }
                }
            }
        }
        // The address once it's up (tap to copy); otherwise what's happening, or why it failed.
        val detail = if (run.state == WebRunState.Running) run.url else run.message.ifBlank { when (run.state) {
            WebRunState.Stopped -> "Server stopped."
            WebRunState.Stopping -> "Stopping server…"
            WebRunState.Failed -> "Couldn't run this app. Check Logs."
            WebRunState.Installing -> "Installing dependencies…"
            else -> "Preparing local server…"
        } }
        Text(
            detail,
            style = if (run.state == WebRunState.Running) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodySmall,
            fontFamily = if (run.state == WebRunState.Running) androidx.compose.ui.text.font.FontFamily.Monospace else null,
            color = when (run.state) { WebRunState.Failed, WebRunState.NeedsSetup, WebRunState.CompatibilityFailure -> colors.error; WebRunState.Running -> colors.primary; else -> colors.onSurfaceVariant },
            maxLines = 3, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 66.dp, end = 14.dp, top = 2.dp)
                .then(if (run.state == WebRunState.Running) Modifier.clickable {
                    context.getSystemService(android.content.ClipboardManager::class.java)
                        .setPrimaryClip(android.content.ClipData.newPlainText("url", run.url))
                    android.widget.Toast.makeText(context, "Address copied", android.widget.Toast.LENGTH_SHORT).show()
                } else Modifier),
        )
        if (run.notice.isNotBlank() && run.state.active) {
            Text(run.notice, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant,
                modifier = Modifier.padding(start = 66.dp, end = 14.dp, top = 2.dp))
        }
        if (busy) {
            LinearProgressIndicator(
                Modifier.fillMaxWidth().padding(start = 66.dp, end = 14.dp, top = 8.dp).height(3.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(2.dp)),
                color = stateColor, trackColor = colors.surfaceContainerHighest,
            )
        }
        if (!run.busy && run.state == WebRunState.CompatibilityFailure && run.config.project.kind == WebKind.Next) {
            TextButton(onClick = { confirmation = "Repair compiler" }, modifier = Modifier.padding(start = 56.dp)) { Text("Try compatibility") }
        }
        Row(Modifier.fillMaxWidth().padding(start = 66.dp, end = 12.dp, top = 10.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when (run.state) {
                WebRunState.Running -> Button(onClick = {
                    if (onPreview != null) onPreview(run.url, "${run.config.project.framework} · ${run.config.project.directory.name}")
                    else WebRunSessions.open(context, run)
                }, modifier = Modifier.weight(1f)) {
                    dev.yurlungr.app.ui.PhIcon(dev.yurlungr.app.ui.Ph.Globe, null, size = 16.dp)
                    Spacer(Modifier.width(6.dp)); Text(if (onPreview != null) "Preview" else "Open")
                }
                WebRunState.Failed, WebRunState.Stopped -> Button(enabled = !run.busy, onClick = { confirmation = "Restart" }, modifier = Modifier.weight(1f)) {
                    dev.yurlungr.app.ui.PhIcon(dev.yurlungr.app.ui.Ph.Play, null, size = 16.dp)
                    Spacer(Modifier.width(6.dp)); Text(if (run.state == WebRunState.Failed) "Try again" else "Start")
                }
                WebRunState.NeedsSetup, WebRunState.CompatibilityFailure -> onOptions?.let { options ->
                    Button(enabled = !run.busy, onClick = options, modifier = Modifier.weight(1f)) { Text("Run setup") }
                }
                else -> Unit
            }
            FilledTonalButton(enabled = run.terminal != null, onClick = { WebRunSessions.logs(run); onLogs() }, modifier = Modifier.weight(1f)) {
                dev.yurlungr.app.ui.PhIcon(dev.yurlungr.app.ui.Ph.TerminalWindow, null, size = 16.dp)
                Spacer(Modifier.width(6.dp)); Text("Logs")
            }
        }
        WebRunFailureHelp(run)
    }
    confirmation?.let { action ->
        WebRunConfirmation(action, run.config.project.directory.name, onDismiss = { confirmation = null }) {
            confirmation = null
            when (action) {
                "Stop" -> WebRunSessions.stop(context,run)
                "Repair compiler" -> WebRunSessions.start(context,root,run.config.copy(
                    install = false, mode = WebLaunchMode.Framework, profile = WebProfile.Compatibility, repairNextCompiler = true))
                else -> WebRunSessions.restart(context,run)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WebRunConfirmation(action: String, name: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (action == "Repair compiler") "Install matching Next.js compiler?" else "$action local server?", style = MaterialTheme.typography.titleLarge)
            Text(name, style = MaterialTheme.typography.bodyLarge)
            Text(when (action) {
                "Stop" -> "Stop this project's server and its child processes. Logs stay available."
                "Repair compiler" -> "Download this Next.js version's WASM compiler and any missing matching Lightning CSS fallback, then retry with Webpack. Package manifests and lockfiles stay unchanged. Framework CLI skips project script setup steps."
                else -> "Stop this server and start it again using the saved settings."
            })
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Button(onClick = onConfirm) { Text(if (action == "Repair compiler") "Install & retry" else action) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WebRunSheet(
    root: File,
    projects: List<WebProject>,
    onDismiss: () -> Unit,
    onStarted: () -> Unit,
    runInTerminal: (String, String) -> Unit,
) {
    val context = LocalContext.current
    val paths = remember { RuntimePaths(context) }
    val saved = remember(root.path) { WebRunPreferences.read(context,root) }
    var selectedPath by remember(root.path) { mutableStateOf(saved?.get("cwd")?.jsonPrimitive?.contentOrNull
        ?.takeIf { path -> projects.any { it.directory.path == path } }
        ?: projects.first().directory.path) }
    val project = projects.first { it.directory.path == selectedPath }
    val stored = saved?.takeIf { it["cwd"]?.jsonPrimitive?.contentOrNull == selectedPath }
    var manager by remember(selectedPath) { mutableStateOf(stored?.get("manager")?.jsonPrimitive?.contentOrNull
        ?.let { value -> WebPackageManager.entries.firstOrNull { it.executable == value } } ?: project.manager) }
    var script by remember(selectedPath) { mutableStateOf(stored?.get("script")?.jsonPrimitive?.contentOrNull
        ?.takeIf { it in project.scripts } ?: project.scripts.firstOrNull().orEmpty()) }
    var port by remember(selectedPath) { mutableStateOf(stored?.get("port")?.jsonPrimitive?.contentOrNull
        ?: if (project.kind == WebKind.Static) "8080" else "5173") }
    var custom by remember(selectedPath) { mutableStateOf(stored?.get("command")?.jsonPrimitive?.contentOrNull.orEmpty()) }
    var mode by remember(selectedPath) { mutableStateOf(stored?.get("mode")?.jsonPrimitive?.contentOrNull
        ?.let { value -> WebLaunchMode.entries.firstOrNull { it.name == value } }
        ?: if (custom.isNotBlank()) WebLaunchMode.Custom else project.defaultMode) }
    var profile by remember(selectedPath) { mutableStateOf(stored?.get("profile")?.jsonPrimitive?.contentOrNull
        ?.let { value -> WebProfile.entries.firstOrNull { it.name == value } }
        ?: if (project.kind == WebKind.Next) WebProfile.Compatibility else WebProfile.Default) }
    var healthPath by remember(selectedPath) { mutableStateOf(stored?.get("healthPath")?.jsonPrimitive?.contentOrNull ?: "/") }
    var timeout by remember(selectedPath) { mutableStateOf(stored?.get("startupTimeoutSeconds")?.jsonPrimitive?.contentOrNull ?: "180") }
    var advanced by remember(selectedPath) { mutableStateOf(false) }
    var install by remember(selectedPath) { mutableStateOf(!project.dependenciesPresent) }
    var openBrowser by remember(selectedPath) { mutableStateOf(stored?.get("openBrowser")?.jsonPrimitive?.booleanOrNull ?: true) }
    // Null until the switch is touched: then it follows the launch command (see RestartRule).
    var restartOnChange by remember(selectedPath) { mutableStateOf(stored?.get("restartOnChange")?.jsonPrimitive?.booleanOrNull) }
    var tools by remember { mutableStateOf<Set<String>?>(null) }
    var error by remember(selectedPath) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { tools = withContext(Dispatchers.IO) {
        listOf("node","npm","pnpm","yarn","bun").filter { File(paths.bin,it).canExecute() }.toSet()
    } }
    val active = WebRunSessions.get(root)?.busy == true
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().padding(start = 20.dp,end = 20.dp,bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Run locally", style = MaterialTheme.typography.titleLarge)
            MenuChip("App: ${project.directory.relativeTo(root.canonicalFile).path.ifEmpty { root.name }}") { close ->
                projects.forEach { candidate -> DropdownMenuItem(
                    text = { Text("${candidate.directory.relativeTo(root.canonicalFile).path.ifEmpty { root.name }} · ${candidate.framework}") },
                    onClick = { selectedPath = candidate.directory.path; close() }) }
            }
            Text(project.framework, style = MaterialTheme.typography.titleMedium)
            project.notes.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary) }
            project.versionHint?.let { Text("Version: $it", style = MaterialTheme.typography.bodySmall) }
            project.nodeRequirement?.let { Text("Requires Node $it", style = MaterialTheme.typography.bodySmall) }
            Text("Detected project. Runtime compatibility is checked when you run it.", style = MaterialTheme.typography.bodySmall)
            val needsManager = project.kind != WebKind.Static && (install || mode == WebLaunchMode.Script)
            if (project.kind != WebKind.Static) {
                MenuChip("Launch: ${mode.label}") { close ->
                    WebLaunchMode.entries.filter { candidate -> when (candidate) {
                        WebLaunchMode.Framework -> project.hasAdapter
                        WebLaunchMode.Script -> project.scripts.isNotEmpty()
                        WebLaunchMode.Custom -> true
                    } }.forEach { candidate -> DropdownMenuItem(text = { Text(candidate.label) },
                        onClick = { mode = candidate; close() }) }
                }
                when (mode) {
                    WebLaunchMode.Framework -> {
                        Text("Launch the installed ${project.framework} CLI directly. Project script setup steps (including predev) are skipped; choose Project script if needed.", style = MaterialTheme.typography.bodySmall)
                        if (project.kind == WebKind.Next) {
                            MenuChip(profile.label) { close ->
                                WebProfile.entries.forEach { candidate -> DropdownMenuItem(text = { Text(candidate.label) }, onClick = { profile = candidate; close() }) }
                            }
                            Text("Compatibility uses Webpack and matching WASM fallbacks for Next.js and Lightning CSS. Missing CSS fallbacks download into the app cache. Native-only packages may still need project setup.", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    WebLaunchMode.Script -> {
                        MenuChip("Script: $script") { close ->
                            project.scripts.forEach { candidate -> DropdownMenuItem(text = { Text(candidate) }, onClick = { script = candidate; close() }) }
                        }
                        Text(project.scriptCommands[script].orEmpty(), style = MaterialTheme.typography.bodySmall)
                        Text("Compound scripts keep their commands intact. They must honor HOST and PORT; choose Framework CLI if their launch flags conflict.", style = MaterialTheme.typography.bodySmall)
                    }
                    WebLaunchMode.Custom -> OutlinedTextField(custom, { custom = it }, label = { Text("Custom command") },
                        supportingText = { Text("Listen on HOST=127.0.0.1 and the private port supplied in \$PORT. The public URL uses the local port below.") }, modifier = Modifier.fillMaxWidth())
                }
                if (needsManager) {
                    MenuChip("Package manager: ${manager?.executable ?: "Choose"}") { close ->
                        WebPackageManager.entries.forEach { candidate -> DropdownMenuItem(text = { Text(candidate.executable) }, onClick = { manager = candidate; close() }) }
                    }
                    project.managerHint?.let { Text("Project declares $it", style = MaterialTheme.typography.bodySmall) }
                }
                Row(Modifier.fillMaxWidth().clickable { install = !install }, horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        Text("Install dependencies")
                        Text(if (project.dependenciesPresent) "Dependencies detected" else "Dependencies not detected", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = install, onCheckedChange = { install = it })
                }
                if (project.installDirectory != project.directory) Text("Dependencies install at workspace root: ${project.installDirectory.name}", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "Hide startup options" else "Startup options") }
                if (advanced) {
                    OutlinedTextField(healthPath, { healthPath = it }, label = { Text("Readiness path") }, singleLine = true,
                        supportingText = { Text("A route that returns HTTP 200–399, such as / or /login. First compilation must succeed before the browser opens.") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(timeout, { timeout = it.filter(Char::isDigit).take(3) }, label = { Text("Startup timeout (seconds)") }, singleLine = true,
                        supportingText = { Text("30–900 seconds; initial compilation can take longer on a phone.") }, modifier = Modifier.fillMaxWidth())
                }
            }
            OutlinedTextField(port, { port = it.filter(Char::isDigit).take(5) }, label = { Text("Local port") }, singleLine = true,
                supportingText = { Text("http://127.0.0.1:$port · this device only") }, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number))
            if (!active) PortConflictNotice(port.toIntOrNull() ?: 0, root) { port = it.toString() }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Open browser when ready", modifier = Modifier.weight(1f))
                Switch(checked = openBrowser, onCheckedChange = { openBrowser = it })
            }
            if (project.kind != WebKind.Static) {
                val restartChecked = restartOnChange ?: RestartRule.defaultFor(project, mode, script, custom)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        Text("Restart when files change")
                        Text(
                            if (restartChecked) "For servers that don't reload by themselves." else "Off: this server reloads changes by itself.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Switch(checked = restartChecked, onCheckedChange = { restartOnChange = it })
                }
            }
            if (tools != null && "node" !in tools.orEmpty()) {
                Text("Node.js is required.", color = MaterialTheme.colorScheme.error)
                Button(onClick = { onDismiss(); runInTerminal("pkg install -y nodejs-lts", "Install Node.js") }) { Text("Install Node.js") }
            } else if (needsManager && manager != null && tools != null && manager!!.executable !in tools.orEmpty()) {
                Text("${manager!!.executable} is not installed.", color = MaterialTheme.colorScheme.error)
                if (manager == WebPackageManager.Bun) Button(onClick = { onDismiss(); runInTerminal("pkg install -y bun", "Install bun") }) { Text("Install bun") }
                else if ("npm" in tools.orEmpty()) Button(onClick = {
                    val declared = project.managerHint?.substringBefore('+')?.takeIf {
                        it.startsWith(manager!!.executable + "@") && Regex("[a-z]+@[0-9][0-9A-Za-z.+-]*").matches(it)
                    } ?: manager!!.executable
                    onDismiss(); runInTerminal("npm install --global ${Git.shellQuote(declared)}", "Install ${manager!!.executable}")
                }) { Text("Install ${manager!!.executable}") }
            }
            if (active) Text("This project already has an active run. Use its server card for Logs, Stop or Restart.")
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(
                modifier = Modifier.fillMaxWidth(),
                enabled = !active && tools != null && "node" in tools.orEmpty() &&
                    (!needsManager || (manager != null && manager!!.executable in tools.orEmpty())),
                onClick = {
                    scope.launch {
                        val config = WebRunConfig(project, manager ?: WebPackageManager.Npm,script,port.toIntOrNull() ?: 0,install,custom,openBrowser,mode,profile,healthPath,timeout.toIntOrNull() ?: 0,
                            restartOnChange = restartOnChange)
                        val validation = withContext(Dispatchers.IO) { runCatching { config.validate() } }
                        if (validation.isFailure) error = validation.exceptionOrNull()?.message
                        else { WebRunSessions.start(context,root,config); onDismiss(); onStarted() }
                    }
                },
            ) { Text(if (install && project.kind != WebKind.Static) "Install & Run" else "Run") }
        }
    }
}
