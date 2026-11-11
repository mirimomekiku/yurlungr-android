package dev.yurlungr.app.projects

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.yurlungr.app.ui.AppColors
import dev.yurlungr.app.ui.AppSheet
import dev.yurlungr.app.ui.Ph
import dev.yurlungr.app.ui.PhIcon
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.File

/** Something to run in a project: a package.json script, a make target, a Gradle task… */
data class ProjectTask(val group: String, val name: String, val command: String, val dir: File)

/**
 * The project's runnable tasks, read from its files (never by running anything): package.json
 * scripts (with the project's package manager), deno tasks, composer scripts, make targets, just
 * recipes, and the usual Gradle, Cargo, Go, Python, Elixir and .NET commands for detected stacks.
 */
object ProjectTasks {
    private fun read(f: File) = runCatching { if (f.isFile && f.length() < 1_000_000) f.readText() else "" }.getOrDefault("")
    private fun json(f: File): JsonObject? = runCatching { Json.parseToJsonElement(read(f)) as? JsonObject }.getOrNull()
    private fun keys(obj: JsonObject?, key: String): List<String> = (obj?.get(key) as? JsonObject)?.keys?.toList().orEmpty()

    fun detect(root: File, stacks: List<DetectedStack>): List<ProjectTask> = buildList {
        val label = { dir: File -> dir.relativeTo(root).invariantSeparatorsPath.ifEmpty { root.name } }
        for (s in stacks) {
            val where = label(s.dir)
            when (s.id) {
                "node" -> {
                    val runner = when {
                        s.name.contains("pnpm") -> "pnpm run"
                        s.name.contains("yarn") -> "yarn run"
                        s.name.contains("bun") -> "bun run"
                        else -> "npm run"
                    }
                    for (script in keys(json(File(s.dir, "package.json")), "scripts")) add(ProjectTask("$where · package.json", script, "$runner $script", s.dir))
                }
                "deno" -> for (task in keys(json(listOf("deno.json", "deno.jsonc").map { File(s.dir, it) }.first { it.isFile }), "tasks")) {
                    add(ProjectTask("$where · deno", task, "deno task $task", s.dir))
                }
                "php" -> for (script in keys(json(File(s.dir, "composer.json")), "scripts")) add(ProjectTask("$where · composer", script, "composer run $script", s.dir))
                "android", "gradle" -> {
                    val gradle = if (File(s.dir, "gradlew").isFile) "./gradlew" else "gradle"
                    val tasks = if (s.id == "android") listOf("assembleDebug", "installDebug", "test", "lint", "clean") else listOf("build", "test", "run", "clean")
                    for (t in tasks) add(ProjectTask("$where · Gradle", t, "$gradle $t", s.dir))
                }
                "rust" -> for (t in listOf("build", "run", "test", "clippy")) add(ProjectTask("$where · Cargo", t, "cargo $t", s.dir))
                "go" -> listOf("build" to "go build ./...", "test" to "go test ./...", "run" to "go run .", "vet" to "go vet ./...")
                    .forEach { (n, c) -> add(ProjectTask("$where · Go", n, c, s.dir)) }
                "python" -> {
                    val py = if (File(s.dir, ".venv/bin/python").exists()) ".venv/bin/python" else "python"
                    val texts = read(File(s.dir, "pyproject.toml")) + read(File(s.dir, "requirements.txt"))
                    if (File(s.dir, "tests").isDirectory || texts.contains("pytest")) add(ProjectTask("$where · Python", "pytest", "$py -m pytest", s.dir))
                    if (File(s.dir, "manage.py").isFile) add(ProjectTask("$where · Django", "runserver", "$py manage.py runserver 127.0.0.1:8000", s.dir))
                }
                "elixir" -> listOf("test" to "mix test", "compile" to "mix compile").forEach { (n, c) -> add(ProjectTask("$where · mix", n, c, s.dir)) }
                "dotnet" -> listOf("build", "test", "run").forEach { add(ProjectTask("$where · .NET", it, "dotnet $it", s.dir)) }
                "zig" -> listOf("build", "test").forEach { add(ProjectTask("$where · Zig", it, "zig build" + if (it == "test") " test" else "", s.dir)) }
            }
        }
        // Make and just, wherever the stacks are (and at the root).
        for (dir in (listOf(root) + stacks.map { it.dir }).distinctBy { it.canonicalPath }) {
            val where = label(dir)
            makeTargets(read(File(dir, "Makefile")).ifEmpty { read(File(dir, "makefile")) }).forEach { add(ProjectTask("$where · make", it, "make $it", dir)) }
            justRecipes(read(listOf("justfile", "Justfile", ".justfile").map { File(dir, it) }.firstOrNull { it.isFile } ?: File(""))).forEach {
                add(ProjectTask("$where · just", it, "just $it", dir))
            }
        }
    }.distinctBy { it.dir.path + "|" + it.command }

    /** Explicit targets ("build:", "test: deps"), not variables, pattern rules or special targets. */
    internal fun makeTargets(text: String): List<String> =
        Regex("^([A-Za-z0-9][A-Za-z0-9_./-]*)\\s*:(?![=:])", RegexOption.MULTILINE).findAll(text)
            // Not files ("main.o", "out/app") or pattern rules: those aren't commands people run.
            .map { it.groupValues[1] }.filterNot { '%' in it || '.' in it || '/' in it }
            .distinct().take(40).toList()

    /** Recipes at the start of a line ("test:", "deploy env:"), not assignments or private ones. */
    internal fun justRecipes(text: String): List<String> =
        Regex("^@?([A-Za-z0-9][A-Za-z0-9_-]*)(?:\\s+[^:=\\n]*)?:(?!=)", RegexOption.MULTILINE).findAll(text)
            .map { it.groupValues[1] }.filterNot { it == "set" || it == "alias" || it == "export" || it == "import" || it == "mod" }
            .distinct().take(40).toList()
}

/** The project's tasks, grouped, each run with one tap in a terminal. */
@Composable
fun ProjectTasksSheet(root: File, tasks: List<ProjectTask>, onDismiss: () -> Unit, onRun: (ProjectTask) -> Unit) {
    var filter by remember { mutableStateOf("") }
    val colors = MaterialTheme.colorScheme
    AppSheet(
        "Scripts",
        onDismiss = onDismiss,
        subtitle = if (tasks.isEmpty()) "Nothing to run found in ${root.name}." else "${tasks.size} found. Each runs in its own terminal.",
        icon = Ph.Play,
        tile = AppColors.tiles.lime,
    ) {
        if (tasks.isEmpty()) {
            Text(
                "Found from package.json scripts, deno tasks, composer scripts, Makefile targets and justfile recipes, plus the usual Gradle, Cargo, Go, pytest, mix and .NET commands.",
                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
            )
        }
        if (tasks.size > 10) {
            OutlinedTextField(filter, { filter = it }, placeholder = { Text("Filter") }, singleLine = true,
                shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth())
        }
        val shown = tasks.filter { filter.isBlank() || it.name.contains(filter.trim(), true) || it.command.contains(filter.trim(), true) }
        for ((group, list) in shown.groupBy { it.group }) {
            Text(group, style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
            for (task in list) {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(colors.surfaceContainerHigh)
                        .clickable { onRun(task) }.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(task.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(task.command, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = colors.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.width(8.dp))
                    PhIcon(Ph.Play, "Run ${task.name}", Modifier.size(18.dp), size = 16.dp, tint = colors.primary)
                }
            }
        }
    }
}
