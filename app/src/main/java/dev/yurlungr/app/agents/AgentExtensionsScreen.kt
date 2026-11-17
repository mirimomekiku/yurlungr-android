package dev.yurlungr.app.agents

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.yurlungr.app.projects.LastProject
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private sealed interface Dialog {
    data object NewSkill : Dialog
    data object NewCommand : Dialog
    data object FromGit : Dialog
    data class Plugin(val action: String) : Dialog
    data class Delete(val label: String, val target: File) : Dialog
}

/**
 * Skills, custom commands and plugins per agent. Skills and commands are plain files, so this
 * lists, creates, opens (in the editor) and deletes them; plugins run the agent's own CLI in a terminal.
 */
@Composable
fun AgentExtensionsScreen(
    initialAgent: String?,
    projectPath: String?,
    onBack: () -> Unit,
    onOpenFile: (File) -> Unit,
    onRun: (command: String, title: String) -> Unit,
) {
    val context = LocalContext.current
    val paths = remember { RuntimePaths(context) }
    val project = remember(projectPath) { (projectPath?.let(::File) ?: LastProject.get(context))?.takeIf { it.isDirectory } }
    val supported = remember { Agent.entries.filter { AgentExtensions.layout(it) != null } }
    var agent by remember { mutableStateOf(supported.firstOrNull { it.name == initialAgent } ?: supported.first()) }
    var tick by remember { mutableIntStateOf(0) }
    var dialog by remember { mutableStateOf<Dialog?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val layout = AgentExtensions.layout(agent)!!
    val found by produceState(emptyList<Skill>() to emptyList<CommandFile>(), agent, project, tick) {
        value = withContext(Dispatchers.IO) { AgentExtensions.skills(agent, project, paths.home) to AgentExtensions.commands(agent, project, paths.home) }
    }
    val (skills, commands) = found
    val installed = agent.isInstalled(paths)
    val colors = MaterialTheme.colorScheme
    val roots = buildList {
        project?.let { p -> (layout.skillsProject + layout.commandsProject).forEach { add(File(p, it)) } }
        (layout.skillsUser + layout.commandsUser).forEach { add(File(paths.home, it)) }
    }

    Column(Modifier.fillMaxSize().background(colors.background).systemBarsPadding()) {
        ScreenHeader("Skills & commands", onBack) {
            IconButton(onClick = { tick++ }) { PhIcon(Ph.ArrowClockwise, "Refresh", size = 20.dp) }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (a in supported) Chip(a.displayName, onClick = { agent = a }, selected = a == agent)
        }
        LazyColumn(
            contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Text(
                    (if (project != null) "Project: ${project.name}" else "No project selected: only your user folders are shown") +
                        if (!installed) " · ${agent.displayName} isn't installed" else "",
                    style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp),
                )
            }

            item { SectionLabel("Skills") }
            item {
                Group {
                    if (skills.isEmpty()) GroupRow("No skills yet", subtitle = "A skill is a folder with a SKILL.md the agent loads when it applies.", icon = Ph.Sparkle, trailing = {})
                    for (s in skills) GroupRow(
                        s.name,
                        subtitle = (s.description.ifBlank { "No description" }) + " · ${s.scope.label} · ${s.where}",
                        subtitleLines = 3,
                        icon = Ph.Sparkle,
                        onClick = { onOpenFile(s.file) },
                        trailing = { IconButton(onClick = { dialog = Dialog.Delete("skill ${s.name}", s.dir) }) { PhIcon(Ph.Trash, "Delete skill", size = 18.dp, tint = colors.error) } },
                    )
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { dialog = Dialog.NewSkill }) { Text("New skill") }
                    OutlinedButton(onClick = { dialog = Dialog.FromGit }) { Text("From Git") }
                }
            }

            if (layout.commandsProject.isNotEmpty() || layout.commandsUser.isNotEmpty()) {
                item { SectionLabel("Custom commands") }
                item {
                    Group {
                        if (commands.isEmpty()) GroupRow("No custom commands", subtitle = "A command file becomes a /command you can run in chat.", icon = Ph.Terminal, trailing = {})
                        for (c in commands) GroupRow(
                            "/" + c.name,
                            subtitle = "${c.scope.label} · ${c.where}",
                            icon = Ph.Terminal,
                            onClick = { onOpenFile(c.file) },
                            trailing = { IconButton(onClick = { dialog = Dialog.Delete("command /${c.name}", c.file) }) { PhIcon(Ph.Trash, "Delete command", size = 18.dp, tint = colors.error) } },
                        )
                    }
                }
                item { OutlinedButton(onClick = { dialog = Dialog.NewCommand }) { Text("New command") } }
            }

            layout.plugins?.let { cli ->
                item { SectionLabel("Plugins") }
                item {
                    Group {
                        cli.list?.let { cmd -> GroupRow("List installed", subtitle = cmd, icon = Ph.ListChecks, onClick = { if (installed) onRun(cmd, "${agent.displayName} plugins") }) }
                        cli.install?.let { GroupRow("Install…", subtitle = "From a name, git URL or path, with ${agent.displayName}'s own command", icon = Ph.DownloadSimple, onClick = { dialog = Dialog.Plugin("install") }) }
                        cli.marketplaceAdd?.let { GroupRow("Add a marketplace…", subtitle = "Where plugins can be found", icon = Ph.Globe, onClick = { dialog = Dialog.Plugin("marketplace") }) }
                        cli.remove?.let { GroupRow("Remove…", icon = Ph.Trash, onClick = { dialog = Dialog.Plugin("remove") }) }
                    }
                }
            } ?: item {
                Text("${agent.displayName} has no plugin command the app knows of.", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp))
            }
            item {
                Text(
                    "Skills, plugins and commands can make an agent run code or reach new tools. Only add ones you trust, and read a skill before you rely on it.",
                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        }
    }

    when (val d = dialog) {
        null -> Unit
        Dialog.NewSkill -> NewItemSheet("New skill", askDescription = true, scopes = scopesFor(agent, project, skills = true), onDismiss = { dialog = null }) { name, desc, scope ->
            val root = AgentExtensions.skillsRoot(agent, scope, project, paths.home) ?: return@NewItemSheet "Nowhere to put it for ${agent.displayName}."
            AgentExtensions.createSkill(root, name, desc).fold(
                onSuccess = { f -> dialog = null; tick++; onOpenFile(f); null },
                onFailure = { it.message ?: "Couldn't create it." },
            )
        }
        Dialog.NewCommand -> NewItemSheet("New command", askDescription = true, scopes = scopesFor(agent, project, skills = false), onDismiss = { dialog = null }) { name, desc, scope ->
            val root = AgentExtensions.commandsRoot(agent, scope, project, paths.home) ?: return@NewItemSheet "Nowhere to put it for ${agent.displayName}."
            val format = if (layout.commandFormat == CommandFormat.Both) CommandFormat.Markdown else layout.commandFormat
            AgentExtensions.createCommand(root, name, format, desc).fold(
                onSuccess = { f -> dialog = null; tick++; onOpenFile(f); null },
                onFailure = { it.message ?: "Couldn't create it." },
            )
        }
        Dialog.FromGit -> GitSkillSheet(scopes = scopesFor(agent, project, skills = true), onDismiss = { dialog = null }) { url, scope ->
            val root = AgentExtensions.skillsRoot(agent, scope, project, paths.home) ?: return@GitSkillSheet "Nowhere to put it for ${agent.displayName}."
            val cmd = AgentExtensions.cloneCommand(url, root) ?: return@GitSkillSheet "Use a plain https:// or git@ repository address."
            dialog = null
            onRun(cmd, "Install skills")
            null
        }
        is Dialog.Plugin -> {
            val cli = layout.plugins!!
            val (title, template) = when (d.action) {
                "install" -> "Install a plugin" to cli.install
                "marketplace" -> "Add a marketplace" to cli.marketplaceAdd
                else -> "Remove a plugin" to cli.remove
            }
            ArgSheet(title, "Name, git URL or path", onDismiss = { dialog = null }) { arg ->
                val cmd = AgentExtensions.pluginCommand(template.orEmpty(), arg) ?: return@ArgSheet "Use only letters, digits and . _ - : / @ # ~ + = (no spaces)."
                dialog = null
                onRun(cmd, "${agent.displayName} plugin")
                null
            }
        }
        is Dialog.Delete -> AppSheet("Delete ${d.label}?", onDismiss = { dialog = null }, icon = Ph.Trash, tile = AppColors.tiles.peach, subtitle = d.target.path.replace(paths.home.path, "~"),
            actions = {
                OutlinedButton(onClick = { dialog = null }, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) { Text("Cancel") }
                Button(onClick = {
                    dialog = null
                    // A cloned skills repo can be large: delete off the main thread, then refresh.
                    scope.launch {
                        withContext(Dispatchers.IO) { AgentExtensions.delete(d.target, roots) }
                            .onFailure { android.widget.Toast.makeText(context, it.message, android.widget.Toast.LENGTH_LONG).show() }
                        tick++
                    }
                }, modifier = Modifier.weight(2f).heightIn(min = 52.dp)) { Text("Delete") }
            }) {
            Text("This removes it from disk. It can't be undone from here.", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

private fun scopesFor(agent: Agent, project: File?, skills: Boolean): List<ExtScope> {
    val l = AgentExtensions.layout(agent) ?: return emptyList()
    val user = if (skills) l.skillsUser.isNotEmpty() else l.commandsUser.isNotEmpty()
    val proj = project != null && (if (skills) l.skillsProject.any { it != ".agents/skills" } else l.commandsProject.isNotEmpty())
    return listOfNotNull(if (proj) ExtScope.Project else null, if (user) ExtScope.User else null)
}

@Composable
private fun ScopeChips(scopes: List<ExtScope>, selected: ExtScope?, onSelect: (ExtScope) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (s in scopes) FilterChip(selected = s == selected, onClick = { onSelect(s) }, label = { Text(if (s == ExtScope.Project) "This project" else "All my projects") })
    }
}

/** Name + description + where, validated by [onCreate], which returns an error message or null on success. */
@Composable
private fun NewItemSheet(title: String, askDescription: Boolean, scopes: List<ExtScope>, onDismiss: () -> Unit, onCreate: (String, String, ExtScope) -> String?) {
    var name by remember { mutableStateOf("") }
    var desc by remember { mutableStateOf("") }
    var scope by remember { mutableStateOf(scopes.firstOrNull()) }
    var error by remember { mutableStateOf<String?>(null) }
    AppSheet(title, onDismiss = onDismiss, icon = Ph.Sparkle, tile = AppColors.tiles.lilac) {
        OutlinedTextField(name, { name = it.lowercase().trim(); error = null }, label = { Text("Name (e.g. code-review)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        if (askDescription) OutlinedTextField(desc, { desc = it }, label = { Text("What is it for?") }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
        if (scopes.isEmpty()) Text("This agent has no folder for it here.", color = MaterialTheme.colorScheme.error)
        else ScopeChips(scopes, scope) { scope = it }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        Button(onClick = { error = scope?.let { onCreate(name, desc, it) } }, enabled = name.isNotBlank() && scope != null, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Create and open") }
    }
}

@Composable
private fun GitSkillSheet(scopes: List<ExtScope>, onDismiss: () -> Unit, onClone: (String, ExtScope) -> String?) {
    var url by remember { mutableStateOf("") }
    var scope by remember { mutableStateOf(scopes.firstOrNull()) }
    var error by remember { mutableStateOf<String?>(null) }
    AppSheet("Install skills from Git", onDismiss = onDismiss, icon = Ph.GitBranch, tile = AppColors.tiles.sky, subtitle = "Clones the repository into the skills folder") {
        OutlinedTextField(url, { url = it; error = null }, label = { Text("https://github.com/owner/skills") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        ScopeChips(scopes, scope) { scope = it }
        Text("Skills can tell an agent to run commands. Read what you clone before you use it.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        Button(onClick = { error = scope?.let { onClone(url, it) } }, enabled = url.isNotBlank() && scope != null, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Clone") }
    }
}

@Composable
private fun ArgSheet(title: String, label: String, onDismiss: () -> Unit, onRun: (String) -> String?) {
    var arg by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    AppSheet(title, onDismiss = onDismiss, icon = Ph.Wrench, tile = AppColors.tiles.sky, subtitle = "Runs in a terminal so you can see what happens") {
        OutlinedTextField(arg, { arg = it; error = null }, label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        Button(onClick = { error = onRun(arg) }, enabled = arg.isNotBlank(), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Run") }
    }
}
