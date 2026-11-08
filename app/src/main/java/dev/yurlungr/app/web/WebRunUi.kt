package dev.yurlungr.app.web

import androidx.compose.foundation.clickable
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

@Composable
internal fun WebRunCard(root: File, onLogs: () -> Unit) {
    val context = LocalContext.current
    val run = WebRunSessions.get(root) ?: return
    var confirmation by remember(run.id) { mutableStateOf<String?>(null) }
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("${run.config.project.framework} · ${run.state.name}", style = MaterialTheme.typography.titleSmall)
            Text(if (run.state == WebRunState.Running) run.url else run.message.ifBlank { when (run.state) {
                WebRunState.Stopped -> "Server stopped."
                WebRunState.Stopping -> "Stopping server…"
                WebRunState.Failed -> "Couldn't run this app. Check Logs."
                WebRunState.Installing -> "Installing dependencies…"
                else -> "Preparing local server…"
            } },
                style = MaterialTheme.typography.bodySmall,
                color = if (run.state == WebRunState.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(enabled = run.state == WebRunState.Running, onClick = { WebRunSessions.open(context,run) }) { Text("Open browser") }
                TextButton(enabled = run.terminal != null, onClick = { WebRunSessions.logs(run); onLogs() }) { Text("Logs") }
                MenuChip("Actions") { close ->
                    DropdownMenuItem(text = { Text("Restart") }, enabled = run.state != WebRunState.Stopping,
                        onClick = { close(); confirmation = "Restart" })
                    DropdownMenuItem(text = { Text("Stop") }, enabled = run.state.active && run.state != WebRunState.Stopping,
                        onClick = { close(); confirmation = "Stop" })
                }
            }
        }
    }
    confirmation?.let { action ->
        WebRunConfirmation(action, run.config.project.directory.name, onDismiss = { confirmation = null }) {
            confirmation = null
            if (action == "Stop") WebRunSessions.stop(context,run) else WebRunSessions.restart(context,run)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WebRunConfirmation(action: String, name: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("$action local server?", style = MaterialTheme.typography.titleLarge)
            Text(name, style = MaterialTheme.typography.bodyLarge)
            Text(if (action == "Stop") "Stop this project's server and its child processes. Logs stay available." else "Stop this server and start it again using the saved settings.")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Button(onClick = onConfirm) { Text(action) }
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
        ?: projects.firstOrNull { it.supported }?.directory?.path ?: projects.first().directory.path) }
    val project = projects.first { it.directory.path == selectedPath }
    val stored = saved?.takeIf { it["cwd"]?.jsonPrimitive?.contentOrNull == selectedPath }
    var manager by remember(selectedPath) { mutableStateOf(stored?.get("manager")?.jsonPrimitive?.contentOrNull
        ?.let { value -> WebPackageManager.entries.firstOrNull { it.executable == value } } ?: project.manager) }
    var script by remember(selectedPath) { mutableStateOf(stored?.get("script")?.jsonPrimitive?.contentOrNull
        ?.takeIf { it in project.scripts } ?: project.scripts.firstOrNull().orEmpty()) }
    var port by remember(selectedPath) { mutableStateOf(stored?.get("port")?.jsonPrimitive?.contentOrNull
        ?: if (project.kind == WebKind.Static) "8080" else "5173") }
    var custom by remember(selectedPath) { mutableStateOf(stored?.get("command")?.jsonPrimitive?.contentOrNull.orEmpty()) }
    var install by remember(selectedPath) { mutableStateOf(!project.dependenciesPresent) }
    var openBrowser by remember(selectedPath) { mutableStateOf(stored?.get("openBrowser")?.jsonPrimitive?.booleanOrNull ?: true) }
    var tools by remember { mutableStateOf<Set<String>?>(null) }
    var error by remember(selectedPath) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { tools = withContext(Dispatchers.IO) {
        listOf("node","npm","pnpm","yarn").filter { File(paths.bin,it).canExecute() }.toSet()
    } }
    val active = WebRunSessions.get(root)?.state?.active == true
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
            if (!project.supported) {
                Text("Detected ${project.framework}, but this project's launch setup still needs device validation. This release supports Vite development scripts and plain static sites.")
            } else {
                if (project.kind != WebKind.Static) {
                    MenuChip("Package manager: ${manager?.executable ?: "Choose"}") { close ->
                        WebPackageManager.entries.forEach { candidate -> DropdownMenuItem(text = { Text(candidate.executable) },
                            onClick = { manager = candidate; close() }) }
                    }
                    project.managerHint?.let { Text("Project declares $it", style = MaterialTheme.typography.bodySmall) }
                    MenuChip("Script: $script") { close ->
                        project.scripts.forEach { candidate -> DropdownMenuItem(text = { Text(candidate) }, onClick = { script = candidate; close() }) }
                    }
                    Text("${manager?.executable ?: "<manager>"} run $script", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(custom, { custom = it }, label = { Text("Custom command (optional)") },
                        supportingText = { Text("Custom servers must listen on 127.0.0.1 and the port supplied in \$PORT.") }, modifier = Modifier.fillMaxWidth())
                    Row(Modifier.fillMaxWidth().clickable { install = !install }, horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f)) {
                            Text("Install dependencies")
                            Text(if (project.dependenciesPresent) "Dependencies detected" else "Dependencies not detected", style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(checked = install, onCheckedChange = { install = it })
                    }
                    if (project.installDirectory != project.directory) Text("Dependencies install at workspace root: ${project.installDirectory.name}", style = MaterialTheme.typography.bodySmall)
                }
                OutlinedTextField(port, { port = it.filter(Char::isDigit).take(5) }, label = { Text("Local port") }, singleLine = true,
                    supportingText = { Text("http://127.0.0.1:$port · this device only") }, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Open browser when ready", modifier = Modifier.weight(1f))
                    Switch(checked = openBrowser, onCheckedChange = { openBrowser = it })
                }
                if (tools != null && "node" !in tools.orEmpty()) {
                    Text("Node.js is required.", color = MaterialTheme.colorScheme.error)
                    Button(onClick = { onDismiss(); runInTerminal("pkg install -y nodejs-lts", "Install Node.js") }) { Text("Install Node.js") }
                } else if (project.kind != WebKind.Static && manager != null && tools != null && manager!!.executable !in tools.orEmpty()) {
                    Text("${manager!!.executable} is not installed.", color = MaterialTheme.colorScheme.error)
                    if ("npm" in tools.orEmpty()) Button(onClick = {
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
                        (project.kind == WebKind.Static || (manager != null && manager!!.executable in tools.orEmpty())),
                    onClick = {
                        scope.launch {
                            val config = WebRunConfig(project, manager ?: WebPackageManager.Npm,script,port.toIntOrNull() ?: 0,install,custom,openBrowser)
                            val validation = withContext(Dispatchers.IO) { runCatching { config.validate() } }
                            if (validation.isFailure) error = validation.exceptionOrNull()?.message
                            else { WebRunSessions.start(context,root,config); onDismiss(); onStarted() }
                        }
                    },
                ) { Text(if (install && project.kind != WebKind.Static) "Install & Run" else "Run") }
            }
        }
    }
}
