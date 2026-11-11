package dev.yurlungr.app.projects

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import dev.yurlungr.app.agents.Agent
import dev.yurlungr.app.agents.AgentBadge
import dev.yurlungr.app.agents.agentStatuses
import dev.yurlungr.app.runtime.RuntimePaths
import dev.yurlungr.app.ui.AppColors
import dev.yurlungr.app.ui.AppSheet
import dev.yurlungr.app.ui.Chip
import dev.yurlungr.app.ui.Group
import dev.yurlungr.app.ui.GroupRow
import dev.yurlungr.app.ui.Ph
import dev.yurlungr.app.ui.PhIcon
import dev.yurlungr.app.ui.ScreenHeader
import dev.yurlungr.app.ui.SectionLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/** Per-project agent choices kept by the app (not in the project). */
object ProjectAgentPrefs {
    private const val PREFS = "project_agents"
    private fun key(dir: File, what: String) = what + ":" + dir.canonicalPath

    /** The agent Chat starts in this project; null asks each time. */
    fun defaultAgent(context: Context, dir: File): Agent? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(key(dir, "agent"), null)
            ?.let { name -> Agent.entries.firstOrNull { it.name == name } }
    fun setDefaultAgent(context: Context, dir: File, agent: Agent?) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { if (agent == null) remove(key(dir, "agent")) else putString(key(dir, "agent"), agent.name) }

    /** Write MCP servers to Gemini's and Qwen's project settings too. */
    fun syncMcp(context: Context, dir: File): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(key(dir, "mcpSync"), true)
    fun setSyncMcp(context: Context, dir: File, on: Boolean) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean(key(dir, "mcpSync"), on) }
}

/** One MCP server: a command (stdio) or a URL (HTTP). */
data class McpServer(
    val name: String,
    val command: String = "",
    val args: List<String> = emptyList(),
    val env: Map<String, String> = emptyMap(),
    val url: String = "",
) {
    val isRemote: Boolean get() = url.isNotBlank()
}

/**
 * The project's MCP servers. `.mcp.json` (read by Claude Code and others) is the source; Gemini
 * and Qwen read the same servers from `mcpServers` in `.gemini/settings.json` and
 * `.qwen/settings.json`, which are merged so their other settings are kept.
 */
object ProjectMcp {
    private val json = Json { prettyPrint = true; prettyPrintIndent = "  " }
    fun file(dir: File) = File(dir, ".mcp.json")

    private fun readObject(f: File): JsonObject? = runCatching { if (f.isFile) Json.parseToJsonElement(f.readText()) as? JsonObject else null }.getOrNull()

    fun read(dir: File): List<McpServer> = parse(readObject(file(dir)))

    internal fun parse(root: JsonObject?): List<McpServer> {
        val servers = root?.get("mcpServers") as? JsonObject ?: return emptyList()
        return servers.mapNotNull { (name, value) ->
            val o = value as? JsonObject ?: return@mapNotNull null
            fun str(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull.orEmpty()
            McpServer(
                name = name,
                command = str("command"),
                args = (o["args"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty(),
                env = (o["env"] as? JsonObject)?.mapValues { it.value.jsonPrimitive.contentOrNull.orEmpty() }.orEmpty(),
                url = str("url").ifBlank { str("httpUrl") },
            )
        }
    }

    /** Claude's shape: `{command, args, env}` or `{type: "http", url}`. */
    internal fun claudeEntry(s: McpServer): JsonObject = JsonObject(buildMap {
        if (s.isRemote) { put("type", JsonPrimitive("http")); put("url", JsonPrimitive(s.url)) }
        else {
            put("command", JsonPrimitive(s.command))
            if (s.args.isNotEmpty()) put("args", JsonArray(s.args.map(::JsonPrimitive)))
            if (s.env.isNotEmpty()) put("env", JsonObject(s.env.mapValues { JsonPrimitive(it.value) }))
        }
    })

    /** Gemini's (and Qwen's) shape: `{command, args, env}` or `{httpUrl}`. */
    internal fun geminiEntry(s: McpServer): JsonObject = JsonObject(buildMap {
        if (s.isRemote) put("httpUrl", JsonPrimitive(s.url))
        else {
            put("command", JsonPrimitive(s.command))
            if (s.args.isNotEmpty()) put("args", JsonArray(s.args.map(::JsonPrimitive)))
            if (s.env.isNotEmpty()) put("env", JsonObject(s.env.mapValues { JsonPrimitive(it.value) }))
        }
    })

    /** [existing] with its `mcpServers` replaced; everything else kept. */
    internal fun merged(existing: JsonObject?, servers: Map<String, JsonElement>): JsonObject =
        JsonObject((existing ?: JsonObject(emptyMap())) + ("mcpServers" to JsonObject(servers)))

    fun write(dir: File, servers: List<McpServer>, alsoGeminiQwen: Boolean) {
        val f = file(dir)
        f.writeText(json.encodeToString(JsonObject.serializer(), merged(readObject(f), servers.associate { it.name to claudeEntry(it) })) + "\n")
        if (alsoGeminiQwen) for (sub in listOf(".gemini", ".qwen")) {
            val settings = File(dir, "$sub/settings.json")
            settings.parentFile?.mkdirs()
            settings.writeText(json.encodeToString(JsonObject.serializer(), merged(readObject(settings), servers.associate { it.name to geminiEntry(it) })) + "\n")
        }
    }

    /** "npx -y @scope/server --flag 'a b'" into words, honouring quotes. */
    fun splitArgs(line: String): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        var quote: Char? = null
        var any = false
        for (c in line) when {
            quote != null && c == quote -> quote = null
            quote != null -> cur.append(c)
            c == '"' || c == '\'' -> { quote = c; any = true }
            c.isWhitespace() -> { if (cur.isNotEmpty() || any) out += cur.toString(); cur.clear(); any = false }
            else -> cur.append(c)
        }
        if (cur.isNotEmpty() || any) out += cur.toString()
        return out
    }
}

/** Instruction files agents read from the project root, and who reads each. */
private val INSTRUCTION_FILES = listOf(
    "AGENTS.md" to "Codex, OpenCode, Copilot, Goose and most newer agents",
    "CLAUDE.md" to "Claude Code",
    "GEMINI.md" to "Gemini CLI",
    "QWEN.md" to "Qwen Code",
)

/**
 * A project's agent settings: which agent Chat starts, the instruction files agents read (open,
 * create, or point them all at AGENTS.md), and the project's MCP servers.
 */
@Composable
fun ProjectAgentsScreen(dir: File, onBack: () -> Unit, onOpenFile: (File) -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val paths = remember { RuntimePaths(context) }
    var reload by remember { mutableIntStateOf(0) }
    val installed by produceState(emptyList<Agent>()) {
        value = withContext(Dispatchers.IO) { agentStatuses(paths).filter { it.installed }.map { it.agent } }
    }
    var defaultAgent by remember { mutableStateOf(ProjectAgentPrefs.defaultAgent(context, dir)) }
    var servers by remember { mutableStateOf(emptyList<McpServer>()) }
    var sync by remember { mutableStateOf(ProjectAgentPrefs.syncMcp(context, dir)) }
    var editing by remember { mutableStateOf<McpServer?>(null) }
    var removing by remember { mutableStateOf<McpServer?>(null) }
    LaunchedEffect(reload) { servers = withContext(Dispatchers.IO) { ProjectMcp.read(dir) } }
    fun save(list: List<McpServer>) {
        runCatching { ProjectMcp.write(dir, list, sync) }
            .onSuccess { servers = list }
            .onFailure { Toast.makeText(context, "Couldn't save: ${it.message}", Toast.LENGTH_LONG).show() }
    }
    val colors = MaterialTheme.colorScheme

    Column(Modifier.fillMaxSize().background(colors.background).systemBarsPadding()) {
        ScreenHeader("Agent settings", onBack)
        Text(dir.name, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp))
        LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { SectionLabel("Chat starts") }
            item {
                Group {
                    GroupRow("Ask each time", subtitle = "Pick an agent when you tap Chat", leading = { RadioButton(defaultAgent == null, null) },
                        onClick = { defaultAgent = null; ProjectAgentPrefs.setDefaultAgent(context, dir, null) }, trailing = {})
                    for (agent in installed) {
                        GroupRow(agent.displayName, leading = { AgentBadge(agent, size = 26.dp) },
                            onClick = { defaultAgent = agent; ProjectAgentPrefs.setDefaultAgent(context, dir, agent) },
                            trailing = { RadioButton(defaultAgent == agent, null) })
                    }
                }
            }

            item { SectionLabel("Instructions") }
            item {
                Group {
                    for ((name, readers) in INSTRUCTION_FILES) {
                        val f = File(dir, name)
                        val exists = remember(reload) { f.isFile }
                        GroupRow(
                            name, subtitle = "Read by $readers" + if (exists) "" else " · not created",
                            icon = Ph.FileText, iconTint = if (exists) colors.primary else colors.outline,
                            onClick = {
                                if (!exists) f.writeText("# ${dir.name}\n\nNotes for coding agents working in this project: how to build, test and run it, and conventions to follow.\n")
                                onOpenFile(f)
                            },
                            trailing = { Text(if (exists) "Open" else "Create", color = colors.primary, style = MaterialTheme.typography.labelLarge) },
                        )
                    }
                }
            }
            item {
                val agents = File(dir, "AGENTS.md")
                val missing = INSTRUCTION_FILES.drop(1).map { File(dir, it.first) }.filter { !it.exists() }
                if (agents.isFile && missing.isNotEmpty()) {
                    OutlinedButton(onClick = {
                        // Each of these reads @-imports, so one file serves every agent.
                        missing.forEach { it.writeText("@AGENTS.md\n") }
                        reload++
                        Toast.makeText(context, "${missing.joinToString { it.name }} now use AGENTS.md", Toast.LENGTH_SHORT).show()
                    }, modifier = Modifier.fillMaxWidth()) { Text("Use AGENTS.md for ${missing.joinToString { it.name }}") }
                }
            }

            item { SectionLabel("MCP servers") }
            item {
                Group {
                    if (servers.isEmpty()) GroupRow("No MCP servers", subtitle = "Tools agents can call, like a database, a browser or an API.", icon = Ph.Plus, trailing = {})
                    for (s in servers) {
                        GroupRow(
                            s.name,
                            subtitle = if (s.isRemote) s.url else (listOf(s.command) + s.args).joinToString(" "),
                            subtitleLines = 2,
                            icon = if (s.isRemote) Ph.Globe else Ph.TerminalWindow,
                            onClick = { editing = s },
                            trailing = { IconButton(onClick = { removing = s }) { PhIcon(Ph.Trash, "Remove", size = 17.dp, tint = colors.error) } },
                        )
                    }
                    GroupRow("Add server", icon = Ph.Plus, onClick = { editing = McpServer("") }, trailing = {})
                }
            }
            item {
                Group {
                    GroupRow(
                        "Also for Gemini and Qwen",
                        subtitle = "Writes the same servers to .gemini/settings.json and .qwen/settings.json. Claude Code reads .mcp.json; Codex only has global servers (~/.codex/config.toml).",
                        trailing = { Switch(sync, { sync = it; ProjectAgentPrefs.setSyncMcp(context, dir, it); if (it) save(servers) }) },
                    )
                }
            }
        }
    }

    editing?.let { server ->
        McpEditSheet(server, existingNames = servers.map { it.name }.filter { it != server.name }, onDismiss = { editing = null }) { updated ->
            editing = null
            save(servers.filter { it.name != server.name } + updated)
        }
    }
    removing?.let { server ->
        AppSheet("Remove ${server.name}?", onDismiss = { removing = null }, icon = Ph.Trash, actions = {
            OutlinedButton(onClick = { removing = null }, modifier = Modifier.weight(1f)) { Text("Cancel") }
            Button(onClick = { removing = null; save(servers.filter { it.name != server.name }) }, modifier = Modifier.weight(1f)) { Text("Remove") }
        }) { Text("Agents started after this won't see it.", color = colors.onSurfaceVariant) }
    }
}

@Composable
private fun McpEditSheet(server: McpServer, existingNames: List<String>, onDismiss: () -> Unit, onSave: (McpServer) -> Unit) {
    var name by remember { mutableStateOf(server.name) }
    var remote by remember { mutableStateOf(server.isRemote) }
    var command by remember { mutableStateOf((listOf(server.command) + server.args).filter { it.isNotEmpty() }.joinToString(" ") { if (it.any(Char::isWhitespace)) "\"$it\"" else it }) }
    var url by remember { mutableStateOf(server.url) }
    var env by remember { mutableStateOf(server.env.entries.joinToString("\n") { "${it.key}=${it.value}" }) }
    val nameError = when {
        name.isBlank() -> null
        !Regex("^[A-Za-z0-9_.-]+$").matches(name) -> "Letters, numbers, - _ and . only"
        name in existingNames -> "Already used"
        else -> null
    }
    val valid = name.isNotBlank() && nameError == null && if (remote) url.startsWith("http") else command.isNotBlank()
    AppSheet(
        if (server.name.isEmpty()) "Add MCP server" else "Edit ${server.name}", onDismiss = onDismiss, icon = Ph.Plus, tile = AppColors.tiles.lilac,
        actions = {
            OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) { Text("Cancel") }
            Button(enabled = valid, onClick = {
                val words = ProjectMcp.splitArgs(command)
                onSave(McpServer(
                    name = name.trim(),
                    command = if (remote) "" else words.firstOrNull().orEmpty(),
                    args = if (remote) emptyList() else words.drop(1),
                    env = env.lines().mapNotNull { l -> l.trim().takeIf { '=' in it }?.let { it.substringBefore('=').trim() to it.substringAfter('=') } }
                        .filter { it.first.isNotEmpty() }.toMap(),
                    url = if (remote) url.trim() else "",
                ))
            }, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) { Text("Save") }
        },
    ) {
        OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, isError = nameError != null,
            supportingText = nameError?.let { { Text(it) } }, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("Command", selected = !remote, onClick = { remote = false })
            Chip("URL (HTTP)", selected = remote, onClick = { remote = true })
        }
        if (remote) {
            OutlinedTextField(url, { url = it }, label = { Text("URL") }, placeholder = { Text("https://example.com/mcp") }, singleLine = true,
                shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth())
        } else {
            OutlinedTextField(command, { command = it }, label = { Text("Command") }, placeholder = { Text("npx -y @modelcontextprotocol/server-filesystem .") },
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
                shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(env, { env = it }, label = { Text("Environment (KEY=value per line)") }, minLines = 2,
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
                supportingText = { Text("Saved in the project's files: keep secrets out of git (.gitignore).") },
                shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth())
        }
    }
}
