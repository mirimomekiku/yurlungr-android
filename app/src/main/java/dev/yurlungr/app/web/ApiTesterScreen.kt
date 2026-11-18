package dev.yurlungr.app.web

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.yurlungr.app.editor.FilePreview
import dev.yurlungr.app.ui.AppColors
import dev.yurlungr.app.ui.AppSheet
import dev.yurlungr.app.ui.Chip
import dev.yurlungr.app.ui.Group
import dev.yurlungr.app.ui.GroupRow
import dev.yurlungr.app.ui.MenuChip
import dev.yurlungr.app.ui.Ph
import dev.yurlungr.app.ui.PhIcon
import dev.yurlungr.app.ui.ScreenHeader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

private const val MAX_LINES = 4_000
private const val MAX_LINE = 2_000

/** Bytes as "512 B", "4.2 KB", "1.3 MB". */
private fun sizeLabel(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "%.1f MB".format(bytes / (1024.0 * 1024))
}

/**
 * Send HTTP requests to the project's local server: method, address, headers and a body; the
 * answer's status, time, size, headers and body (JSON pretty-printed). Requests can be saved per
 * project (in app storage, not the repo) or picked from the project's OpenAPI / Swagger routes.
 */
@Composable
fun ApiTesterScreen(projectPath: String, port: Int?, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val root = remember(projectPath) { File(projectPath) }
    val serverPort = remember(projectPath, port) {
        port ?: WebRunSessions.get(root)?.takeIf { it.state == WebRunState.Running }?.config?.port
            ?: WebRunPreferences.read(context, root)?.get("port")?.jsonPrimitive?.contentOrNull?.toIntOrNull()
    }
    var method by remember { mutableStateOf("GET") }
    var url by remember { mutableStateOf(ApiRequests.defaultUrl(serverPort)) }
    var headers by remember { mutableStateOf("") }
    var bodyType by remember { mutableStateOf(ApiBodyType.Json) }
    var body by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var response by remember { mutableStateOf<ApiResponse?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var showHeaders by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf<List<ApiRequest>>(emptyList()) }
    var showSaved by remember { mutableStateOf(false) }
    var saveName by remember { mutableStateOf<String?>(null) }
    var showRoutes by remember { mutableStateOf(false) }
    LaunchedEffect(projectPath) { saved = withContext(Dispatchers.IO) { ApiRequestStore.load(context, projectPath) } }

    fun current() = ApiRequest(method = method, url = url, headers = headers, bodyType = bodyType, body = body)
    fun load(request: ApiRequest) {
        method = request.method; url = request.url; headers = request.headers; bodyType = request.bodyType; body = request.body
        response = null; error = null
    }
    fun persist(list: List<ApiRequest>) {
        saved = list
        scope.launch(Dispatchers.IO) {
            runCatching { ApiRequestStore.save(context, projectPath, list) }
                .onFailure { withContext(Dispatchers.Main) { Toast.makeText(context, "Couldn't save: ${it.message}", Toast.LENGTH_LONG).show() } }
        }
    }
    fun send() {
        val built = runCatching { ApiRequests.build(current()) }.getOrElse { error = it.message; response = null; return }
        sending = true; error = null
        scope.launch {
            runCatching { ApiRequests.send(built) }
                .onSuccess { response = it }
                .onFailure { response = null; error = ApiRequests.describe(it) }
            sending = false
        }
    }

    val answer = response
    // Pretty JSON and line splitting happen off the main thread: a big answer would stall the screen.
    val lines by produceState<List<String>>(emptyList(), answer, showHeaders) {
        value = withContext(Dispatchers.Default) {
            when {
                answer == null -> emptyList()
                showHeaders -> answer.headers.map { (name, value) -> "$name: $value" }
                else -> {
                    val text = if (!answer.truncated && ApiRequests.looksLikeJson(answer.contentType, answer.body)) FilePreview.prettyJson(answer.body) ?: answer.body else answer.body
                    text.lineSequence().take(MAX_LINES).map { if (it.length > MAX_LINE) it.take(MAX_LINE) + "…" else it }.toList()
                }
            }
        }
    }
    val colors = MaterialTheme.colorScheme
    val mono = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp)

    Column(Modifier.fillMaxSize().background(colors.background).systemBarsPadding().imePadding()) {
        ScreenHeader("API tester", onBack) {
            IconButton(onClick = { showRoutes = true }) { PhIcon(Ph.BookOpen, "Routes from OpenAPI", size = 20.dp) }
            IconButton(onClick = { showSaved = true }) { PhIcon(Ph.FloppyDisk, "Saved requests", size = 20.dp) }
        }
        LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MenuChip(method) { close ->
                        ApiRequests.METHODS.forEach { candidate -> DropdownMenuItem(text = { Text(candidate) }, onClick = { method = candidate; close() }) }
                    }
                    if (ApiRequests.hasBody(method)) MenuChip("Body: ${bodyType.label}") { close ->
                        ApiBodyType.entries.forEach { candidate -> DropdownMenuItem(text = { Text(candidate.label) }, onClick = { bodyType = candidate; close() }) }
                    }
                }
            }
            item {
                OutlinedTextField(url, { url = it }, label = { Text("Address") }, singleLine = true, textStyle = mono,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth())
            }
            item {
                OutlinedTextField(headers, { headers = it }, label = { Text("Headers") }, textStyle = mono, minLines = 1, maxLines = 6,
                    placeholder = { Text("Authorization: Bearer …", style = mono) },
                    supportingText = { Text("One per line, Name: value") }, modifier = Modifier.fillMaxWidth())
            }
            if (ApiRequests.hasBody(method) && bodyType != ApiBodyType.None) item {
                OutlinedTextField(body, { body = it }, label = { Text("Body (${bodyType.label})") }, textStyle = mono, minLines = 3, maxLines = 14,
                    placeholder = { Text(when (bodyType) { ApiBodyType.Json -> "{\"name\": \"value\"}"; ApiBodyType.Form -> "name=value"; else -> "" }, style = mono) },
                    supportingText = if (bodyType == ApiBodyType.Form) ({ Text("One field per line, key=value") }) else null,
                    modifier = Modifier.fillMaxWidth())
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = ::send, enabled = !sending, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                        if (sending) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = colors.onPrimary)
                        else PhIcon(Ph.Play, null, size = 18.dp)
                        Spacer(Modifier.width(8.dp)); Text(if (sending) "Sending…" else "Send")
                    }
                    OutlinedButton(onClick = { saveName = "$method ${url.substringAfter("://").substringAfter('/', "").let { "/$it" }}" },
                        modifier = Modifier.heightIn(min = 48.dp)) { Text("Save") }
                }
            }
            error?.let { message -> item { Text(message, color = colors.error, style = MaterialTheme.typography.bodyMedium) } }
            if (answer != null) {
                item {
                    val tone = when (answer.status) { in 200..299 -> AppColors.status.success; in 300..399 -> AppColors.status.warning; else -> colors.error }
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("${answer.status} ${answer.reason}".trim(), style = MaterialTheme.typography.titleMedium, color = tone)
                        Text("${answer.millis} ms · ${sizeLabel(answer.size)}" + if (answer.truncated) " · showing the first ${sizeLabel(answer.body.length.toLong())}" else "",
                            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Chip("Body", onClick = { showHeaders = false }, selected = !showHeaders)
                            Chip("Headers · ${answer.headers.size}", onClick = { showHeaders = true }, selected = showHeaders)
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = {
                                context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Response", lines.joinToString("\n")))
                                Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                            }) { Text("Copy") }
                        }
                        if (lines.isEmpty() || lines.singleOrNull()?.isEmpty() == true) Text(if (showHeaders) "No headers." else "Empty body.", color = colors.onSurfaceVariant)
                    }
                }
                items(lines.size) { index -> SelectionContainer { Text(lines[index], style = mono.copy(fontSize = 12.sp)) } }
                if (lines.size >= MAX_LINES) item { Text("Only the first $MAX_LINES lines are shown.", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant) }
            }
        }
    }

    saveName?.let { name ->
        var text by remember { mutableStateOf(name) }
        AppSheet("Save request", onDismiss = { saveName = null }, icon = Ph.FloppyDisk, subtitle = "Kept in the app for this project, not in the project's files.",
            actions = {
                Button(onClick = {
                    val label = text.trim().ifBlank { name }
                    persist(saved.filterNot { it.name == label } + current().copy(name = label))
                    saveName = null
                    Toast.makeText(context, "Saved", Toast.LENGTH_SHORT).show()
                }, modifier = Modifier.weight(1f)) { Text("Save") }
            }) {
            OutlinedTextField(text, { text = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        }
    }

    if (showSaved) {
        AppSheet("Saved requests", onDismiss = { showSaved = false }, icon = Ph.FloppyDisk) {
            if (saved.isEmpty()) Text("Nothing saved yet. Use Save to keep a request for this project.", color = colors.onSurfaceVariant)
            else Group {
                for (request in saved) GroupRow(
                    request.name.ifBlank { "${request.method} ${request.url}" },
                    subtitle = "${request.method} ${request.url}", subtitleLines = 1,
                    onClick = { load(request); showSaved = false },
                    trailing = {
                        IconButton(onClick = { persist(saved - request) }) { PhIcon(Ph.Trash, "Delete", size = 18.dp, tint = colors.error) }
                    },
                )
            }
        }
    }

    if (showRoutes) ApiRoutesSheet(root, base = "http://127.0.0.1:${serverPort ?: 3000}", onDismiss = { showRoutes = false }) { routeBase, route ->
        load(ApiRequest(method = route.method, url = routeBase + route.path, headers = headers, bodyType = route.bodyType, body = route.body))
        showRoutes = false
    }
}

/** Routes from an OpenAPI / Swagger JSON file in the project, or from the running server. */
@Composable
private fun ApiRoutesSheet(root: File, base: String, onDismiss: () -> Unit, onPick: (base: String, ApiRoute) -> Unit) {
    val scope = rememberCoroutineScope()
    val specs by produceState<List<File>?>(null, root) { value = withContext(Dispatchers.IO) { runCatching { OpenApi.findSpecs(root) }.getOrDefault(emptyList()) } }
    var source by remember { mutableStateOf<String?>(null) }
    var routes by remember { mutableStateOf<List<ApiRoute>?>(null) }
    var prefix by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val colors = MaterialTheme.colorScheme

    fun open(label: String, read: () -> String?) {
        loading = true; message = null
        scope.launch {
            val parsed = withContext(Dispatchers.IO) {
                runCatching { read()?.let { text -> OpenApi.basePath(text) to OpenApi.parse(text) } }.getOrNull()
            }
            loading = false
            if (parsed == null || parsed.second.isEmpty()) message = "No routes found in $label."
            else { source = label; prefix = parsed.first; routes = parsed.second }
        }
    }

    AppSheet("Routes", onDismiss = onDismiss, icon = Ph.BookOpen, subtitle = source ?: "From an OpenAPI or Swagger JSON description") {
        val list = routes
        if (list != null) {
            TextButton(onClick = { routes = null; source = null }) { Text("Choose another description") }
            Group {
                for (route in list) GroupRow(
                    "${route.method} ${route.path}",
                    subtitle = route.summary.ifBlank { null }, subtitleLines = 2,
                    onClick = { onPick(base + prefix, route) },
                )
            }
            return@AppSheet
        }
        when {
            specs == null -> Text("Looking for openapi.json and swagger.json…", color = colors.onSurfaceVariant)
            specs.orEmpty().isEmpty() -> Text("No openapi.json or swagger.json in this project.", color = colors.onSurfaceVariant)
            else -> Group {
                for (file in specs.orEmpty()) GroupRow(
                    file.name, subtitle = file.parentFile?.relativeToOrNull(root)?.invariantSeparatorsPath?.ifEmpty { null } ?: "Project folder",
                    icon = Ph.FileText,
                    onClick = { open(file.name) { file.takeIf { it.length() <= OpenApi.MAX_SPEC_BYTES }?.readText() } },
                )
            }
        }
        OutlinedButton(onClick = {
            open("the server") { OpenApi.fetchFromServer(base)?.second }
        }, enabled = !loading, modifier = Modifier.fillMaxWidth()) { Text("Load from the running server") }
        Text("Tries ${OpenApi.SERVER_PATHS.joinToString(", ")} on $base. FastAPI serves /openapi.json by itself.",
            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
        if (loading) Text("Reading…", color = colors.onSurfaceVariant)
        message?.let { Text(it, color = colors.error) }
    }
}
