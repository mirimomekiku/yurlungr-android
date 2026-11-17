package dev.yurlungr.app.agents

import dev.yurlungr.app.projects.Git
import java.io.File

/** Where a skill or command lives: this project, the user's folder, or the shared `.agents` convention. */
enum class ExtScope(val label: String) { Project("Project"), User("User"), Shared("Shared") }

enum class CommandFormat(val extensions: List<String>) { Markdown(listOf("md")), Toml(listOf("toml")), Both(listOf("md", "toml")) }

/** An agent's plugin command line; `%s` is the one argument (a name, git URL or path). Only commands seen in its CLI. */
data class PluginCli(val bin: String, val list: String? = null, val install: String? = null, val remove: String? = null, val marketplaceAdd: String? = null)

/**
 * Where an agent looks for skills (folders holding a SKILL.md) and custom commands, and how its
 * plugins are managed. Folders are relative to the project root or the home folder.
 */
data class ExtLayout(
    val skillsProject: List<String> = emptyList(),
    val skillsUser: List<String> = emptyList(),
    val commandsProject: List<String> = emptyList(),
    val commandsUser: List<String> = emptyList(),
    val commandFormat: CommandFormat = CommandFormat.Markdown,
    val plugins: PluginCli? = null,
)

data class Skill(val name: String, val description: String, val dir: File, val scope: ExtScope, val where: String) {
    val file: File get() = File(dir, "SKILL.md")
}

data class CommandFile(val name: String, val file: File, val scope: ExtScope, val where: String)

object AgentExtensions {
    private const val SHARED = ".agents/skills"

    /** The layout for [agent], or null when the app doesn't manage its skills yet. */
    fun layout(agent: Agent): ExtLayout? = when (agent) {
        Agent.Claude -> ExtLayout(
            skillsProject = listOf(".claude/skills"), skillsUser = listOf(".claude/skills"),
            commandsProject = listOf(".claude/commands"), commandsUser = listOf(".claude/commands"),
            plugins = PluginCli("claude", "claude plugin list", "claude plugin install %s", "claude plugin uninstall %s", "claude plugin marketplace add %s"),
        )
        Agent.Codex -> ExtLayout(
            skillsProject = listOf(SHARED), skillsUser = listOf(".codex/skills"),
            plugins = PluginCli("codex", "codex plugin list", "codex plugin add %s", "codex plugin remove %s"),
        )
        Agent.Gemini -> ExtLayout(
            skillsProject = listOf(".gemini/skills"), skillsUser = listOf(".gemini/skills"),
            commandsProject = listOf(".gemini/commands"), commandsUser = listOf(".gemini/commands"), commandFormat = CommandFormat.Toml,
            plugins = PluginCli("gemini", "gemini extensions list", "gemini extensions install %s", "gemini extensions uninstall %s"),
        )
        Agent.Qwen -> ExtLayout(
            skillsProject = listOf(".qwen/skills", SHARED), skillsUser = listOf(".qwen/skills"),
            commandsProject = listOf(".qwen/commands"), commandsUser = listOf(".qwen/commands"), commandFormat = CommandFormat.Both,
            plugins = PluginCli("qwen", install = "qwen extensions install %s"),
        )
        Agent.CommandCode -> ExtLayout(
            skillsProject = listOf(".commandcode/skills", SHARED), skillsUser = listOf(".commandcode/skills"),
            commandsProject = listOf(".commandcode/commands"), commandsUser = listOf(".commandcode/commands"),
        )
        Agent.Auggie -> ExtLayout(
            skillsProject = listOf(".augment/skills"), skillsUser = listOf(".augment/skills"),
            commandsProject = listOf(".augment/commands"), commandsUser = listOf(".augment/commands"),
            plugins = PluginCli("auggie", install = "auggie plugin install %s"),
        )
        Agent.MiniMax -> ExtLayout(
            skillsProject = listOf(".minimax/skills"), skillsUser = listOf(".minimax/skills"),
            commandsProject = listOf(".mcode/commands"), commandsUser = listOf(".mcode/commands"),
            plugins = PluginCli("mcode", "mcode plugin list", "mcode plugin add %s"),
        )
        Agent.Qoder -> ExtLayout(
            skillsProject = listOf(".qoder/skills", SHARED), skillsUser = listOf(".qoder/skills"),
            commandsProject = listOf(".qoder/commands"), commandsUser = listOf(".qoder/commands"),
            plugins = PluginCli("qodercli", install = "qodercli plugins install %s"),
        )
        Agent.Kimi -> ExtLayout(skillsProject = listOf(".kimi-code/skills", SHARED), skillsUser = listOf(".kimi-code/skills"))
        Agent.Pi -> ExtLayout(
            skillsProject = listOf(".pi/skills", SHARED),
            plugins = PluginCli("pi", install = "pi install %s", remove = "pi remove %s"),
        )
        Agent.OpenCode -> ExtLayout(
            skillsProject = listOf(".opencode/skills"), skillsUser = listOf(".config/opencode/skills"),
            commandsProject = listOf(".opencode/commands"), commandsUser = listOf(".config/opencode/commands"),
        )
        Agent.Cline -> ExtLayout(
            skillsProject = listOf(".cline/skills", SHARED), skillsUser = listOf(".cline/skills"),
            commandsProject = listOf(".cline/workflows"), commandsUser = listOf(".cline/workflows"),
        )
        Agent.Kilo -> ExtLayout(
            skillsProject = listOf(".kilo/skills", SHARED), skillsUser = listOf(".kilo/skills"),
            commandsProject = listOf(".kilo/commands"), commandsUser = listOf(".config/kilo/commands"),
        )
        Agent.Kiro -> ExtLayout(skillsProject = listOf(".kiro/skills"), skillsUser = listOf(".kiro/skills"))
        Agent.Amp -> ExtLayout(skillsProject = listOf(SHARED), skillsUser = listOf(".config/agents/skills"))
        else -> null
    }

    private fun scopeOf(dir: String, user: Boolean) = if (dir == SHARED) ExtScope.Shared else if (user) ExtScope.User else ExtScope.Project

    /** Skills found for [agent]: project folders under [project] (if any) and user folders under [home]. */
    fun skills(agent: Agent, project: File?, home: File): List<Skill> {
        val layout = layout(agent) ?: return emptyList()
        val roots = buildList {
            if (project != null) layout.skillsProject.forEach { add(Triple(File(project, it), scopeOf(it, false), it)) }
            layout.skillsUser.forEach { add(Triple(File(home, it), scopeOf(it, true), "~/$it")) }
        }
        return roots.flatMap { (root, scope, label) -> scanSkills(root, scope, label) }
    }

    /** Skill folders in [root]: `root/<name>/SKILL.md`, and one level deeper for repos that hold several. */
    internal fun scanSkills(root: File, scope: ExtScope, where: String): List<Skill> {
        fun dirs(d: File) = d.listFiles { f -> f.isDirectory && !f.name.startsWith(".") }.orEmpty().sortedBy { it.name }
        val found = ArrayList<Skill>()
        fun read(dir: File) {
            val file = File(dir, "SKILL.md")
            if (!file.isFile) return
            val meta = parseFrontmatter(runCatching { file.readText().take(4000) }.getOrDefault(""))
            found += Skill(meta["name"]?.takeIf { it.isNotBlank() } ?: dir.name, meta["description"].orEmpty(), dir, scope, where)
        }
        for (d in dirs(root)) {
            if (File(d, "SKILL.md").isFile) read(d) else dirs(d).forEach(::read)
        }
        return found
    }

    /** `name:` and `description:` from a leading `---` block (quotes removed; a folded `>`/`|` value takes its first line). */
    internal fun parseFrontmatter(text: String): Map<String, String> {
        val lines = text.lines()
        if (lines.firstOrNull()?.trim() != "---") return emptyMap()
        val end = lines.drop(1).indexOfFirst { it.trim() == "---" }.takeIf { it >= 0 } ?: return emptyMap()
        val block = lines.subList(1, end + 1)
        val out = HashMap<String, String>()
        for ((i, line) in block.withIndex()) {
            val m = Regex("^(name|description):\\s*(.*)$").find(line) ?: continue
            var value = m.groupValues[2].trim()
            if (value == ">" || value == "|" || value == ">-" || value == "|-") value = block.getOrNull(i + 1)?.trim().orEmpty()
            out[m.groupValues[1]] = value.removeSurrounding("\"").removeSurrounding("'")
        }
        return out
    }

    /** Custom command files for [agent]; `a/b.md` is `a:b`. */
    fun commands(agent: Agent, project: File?, home: File): List<CommandFile> {
        val layout = layout(agent) ?: return emptyList()
        val roots = buildList {
            if (project != null) layout.commandsProject.forEach { add(Triple(File(project, it), ExtScope.Project, it)) }
            layout.commandsUser.forEach { add(Triple(File(home, it), ExtScope.User, "~/$it")) }
        }
        return roots.flatMap { (root, scope, label) -> scanCommands(root, layout.commandFormat, scope, label) }
    }

    internal fun scanCommands(root: File, format: CommandFormat, scope: ExtScope, where: String): List<CommandFile> {
        if (!root.isDirectory) return emptyList()
        return root.walkTopDown().maxDepth(3).filter { it.isFile && it.extension.lowercase() in format.extensions && !it.name.startsWith(".") }
            .sortedBy { it.path }.map { f -> CommandFile(f.relativeTo(root).invariantSeparatorsPath.removeSuffix("." + f.extension).replace('/', ':'), f, scope, where) }.toList()
    }

    private val NAME = Regex("^[a-z0-9][a-z0-9_-]{0,62}$")
    fun isValidName(name: String) = NAME.matches(name)

    /** The folder new skills of [scope] go in, or null if the agent has none there. */
    fun skillsRoot(agent: Agent, scope: ExtScope, project: File?, home: File): File? {
        val layout = layout(agent) ?: return null
        return when (scope) {
            ExtScope.Project -> project?.let { p -> layout.skillsProject.firstOrNull { it != SHARED }?.let { File(p, it) } }
            ExtScope.Shared -> project?.let { p -> layout.skillsProject.firstOrNull { it == SHARED }?.let { File(p, it) } }
            ExtScope.User -> layout.skillsUser.firstOrNull()?.let { File(home, it) }
        }
    }

    fun commandsRoot(agent: Agent, scope: ExtScope, project: File?, home: File): File? {
        val layout = layout(agent) ?: return null
        return when (scope) {
            ExtScope.Project -> project?.let { p -> layout.commandsProject.firstOrNull()?.let { File(p, it) } }
            ExtScope.User -> layout.commandsUser.firstOrNull()?.let { File(home, it) }
            ExtScope.Shared -> null
        }
    }

    fun skillTemplate(name: String, description: String) =
        "---\nname: $name\ndescription: ${description.replace('\n', ' ').trim()}\n---\n\n# $name\n\nSay when this skill applies and the steps to follow.\n"

    fun commandTemplate(format: CommandFormat, description: String): Pair<String, String> {
        val d = description.replace('\n', ' ').trim()
        return if (format == CommandFormat.Toml)
            "toml" to "description = \"${d.replace("\\", "\\\\").replace("\"", "\\\"")}\"\nprompt = \"\"\"\nWrite the prompt here. {{args}} is replaced by what you type after the command.\n\"\"\"\n"
        else "md" to "---\ndescription: $d\n---\n\nWrite the prompt here. \$ARGUMENTS is replaced by what you type after the command.\n"
    }

    /** Creates `root/<name>/SKILL.md`; fails if the name is invalid or the skill exists. */
    fun createSkill(root: File, name: String, description: String): Result<File> = runCatching {
        require(isValidName(name)) { "Use lowercase letters, digits, - or _ (up to 63)." }
        val dir = File(root, name)
        check(!dir.exists()) { "A skill named $name already exists here." }
        check(dir.mkdirs()) { "Couldn't create ${dir.path}." }
        File(dir, "SKILL.md").also { it.writeText(skillTemplate(name, description)) }
    }

    fun createCommand(root: File, name: String, format: CommandFormat, description: String): Result<File> = runCatching {
        require(isValidName(name)) { "Use lowercase letters, digits, - or _ (up to 63)." }
        val (ext, body) = commandTemplate(format, description)
        val file = File(root, "$name.$ext")
        check(!file.exists()) { "A command named $name already exists here." }
        root.mkdirs()
        file.also { it.writeText(body) }
    }

    /**
     * Deletes a skill folder or command file, only when it sits inside one of [roots] (never a root itself).
     * Links are removed, never followed: a cloned skill holding `data -> ~` must not take the home folder with it.
     */
    fun delete(target: File, roots: List<File>): Result<Unit> = runCatching {
        // Where the entry itself is (its folder resolved, its own name kept), so a linked skill folder is judged by its link.
        val parent = target.absoluteFile.parentFile?.canonicalFile ?: error("Refusing to delete outside the skills folders.")
        val entry = File(parent, target.name)
        val inside = roots.any { r -> r.exists() && entry.path.startsWith(r.canonicalPath + File.separator) }
        check(inside) { "Refusing to delete outside the skills folders." }
        check(deleteNoFollow(entry)) { "Couldn't delete ${target.name}." }
    }

    /** True when [f] is a symbolic link. java.nio where it exists (API 26+); below that, a link's canonical path isn't where it sits. */
    // On API 24-25 java.nio.file is missing: the NoClassDefFoundError lands in runCatching and the fallback runs.
    @android.annotation.SuppressLint("NewApi")
    internal fun isLink(f: File): Boolean = runCatching { java.nio.file.Files.isSymbolicLink(f.toPath()) }.getOrElse {
        val parent = f.absoluteFile.parentFile?.canonicalFile ?: return false
        runCatching { f.canonicalFile != File(parent, f.name) }.getOrDefault(true)
    }

    /** Like deleteRecursively, but a link is unlinked rather than descended into. */
    internal fun deleteNoFollow(f: File): Boolean {
        if (!isLink(f) && f.isDirectory) f.listFiles()?.forEach { deleteNoFollow(it) }
        return f.delete() || !f.exists() && !isLink(f)
    }

    // No leading '-': the CLI would read it as an option rather than a plugin name.
    private val SAFE_ARG = Regex("^[A-Za-z0-9@._:/#~+=][A-Za-z0-9@._:/#~+=-]{0,199}\$")

    /** [template] with its `%s` replaced by the quoted [arg]; null if [arg] has anything beyond plain name/URL/path characters. */
    fun pluginCommand(template: String, arg: String?): String? {
        if (!template.contains("%s")) return template
        val a = arg?.trim().orEmpty()
        if (!SAFE_ARG.matches(a)) return null
        return template.replace("%s", Git.shellQuote(a))
    }

    private val GIT_URL = Regex("^(https://[A-Za-z0-9._~:/?#@!$&'()*+,;=%-]+|git@[A-Za-z0-9.-]+:[A-Za-z0-9._/-]+)$")

    /** `git clone` of a skills repository into `root/<repo>`; null if [url] isn't a plain https/ssh git address. */
    fun cloneCommand(url: String, root: File): String? {
        val u = url.trim()
        if (!GIT_URL.matches(u) || u.any { it == '\'' || it == '`' || it == '$' || it == ';' || it == '&' || it == '|' || it == '<' || it == '>' || it == '"' }) return null
        val repo = u.trimEnd('/').removeSuffix(".git").substringAfterLast('/').substringAfterLast(':').replace(Regex("[^A-Za-z0-9._-]"), "-").trim('.', '-')
        if (repo.isEmpty()) return null
        val dest = File(root, repo)
        return "mkdir -p ${Git.shellQuote(root.invariantSeparatorsPath)} && git clone --depth 1 ${Git.shellQuote(u)} ${Git.shellQuote(dest.invariantSeparatorsPath)} && echo && echo 'Cloned to ${dest.invariantSeparatorsPath.replace("'", "")}' "
    }
}
