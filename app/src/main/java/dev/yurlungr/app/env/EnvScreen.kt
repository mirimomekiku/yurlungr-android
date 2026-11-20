package dev.yurlungr.app.env

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import dev.yurlungr.app.editor.FileSave
import dev.yurlungr.app.runtime.CommandRunner
import dev.yurlungr.app.runtime.RuntimePaths
import dev.yurlungr.app.ui.AppColors
import dev.yurlungr.app.ui.AppSheet
import dev.yurlungr.app.ui.Chip
import dev.yurlungr.app.ui.Group
import dev.yurlungr.app.ui.GroupRow
import dev.yurlungr.app.ui.Keyboards
import dev.yurlungr.app.ui.Ph
import dev.yurlungr.app.ui.PhIcon
import dev.yurlungr.app.ui.ScreenHeader
import dev.yurlungr.app.ui.SectionLabel
import dev.yurlungr.app.ui.WarningBanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Whether git would commit an env file. */
enum class GitExposure { Ignored, NotIgnored, Tracked, Unknown }

/** File access for the editor and for "Add to .env" elsewhere. Everything here blocks: call it off the main thread. */
object EnvStore {
    private const val MAX_BYTES = 1L shl 20

    fun read(file: File): String {
        if (!file.isFile) return ""
        require(file.length() <= MAX_BYTES) { "${file.name} is too large to edit here" }
        return file.readText()
    }

    /**
     * Sets [key] in [file] (created if missing), keeping everything else in it as it was.
     * Returns whether a value was replaced (false: added).
     */
    @Synchronized
    fun upsert(file: File, key: String, value: String): Boolean {
        val doc = EnvFormat.parse(read(file))
        val existed = key in doc
        FileSave.writeAtomically(file, doc.set(key, value).render())
        return existed
    }

    /** Asks git (off the main thread); falls back to reading `.gitignore` when git can't answer. */
    suspend fun exposure(paths: RuntimePaths, dir: File, name: String): GitExposure = withContext(Dispatchers.IO) {
        if (!File(dir, ".git").exists()) return@withContext GitExposure.Unknown
        val git = File(paths.bin, "git")
        if (git.canExecute()) {
            val tracked = CommandRunner.run(paths, listOf(git.path, "ls-files", "--error-unmatch", "--", name), cwd = dir, timeoutMs = 15_000)
            if (tracked.ok) return@withContext GitExposure.Tracked
            val r = CommandRunner.run(paths, listOf(git.path, "check-ignore", "-q", "--", name), cwd = dir, timeoutMs = 15_000)
            when (r.exitCode) {
                0 -> return@withContext GitExposure.Ignored
                1 -> if (!r.timedOut) return@withContext GitExposure.NotIgnored
            }
        }
        val ignore = runCatching { File(dir, ".gitignore").readText() }.getOrDefault("")
        if (EnvGitignore.ignores(ignore, name)) GitExposure.Ignored else GitExposure.NotIgnored
    }

    /**
     * Stops git tracking [name] (the file stays) and, unless something already ignores it, adds it to
     * `.gitignore` so the next `git add .` doesn't track it again.
     */
    suspend fun untrack(paths: RuntimePaths, dir: File, name: String): Result<Unit> = withContext(Dispatchers.IO) {
        val git = File(paths.bin, "git").path
        val r = CommandRunner.run(paths, listOf(git, "rm", "--cached", "--quiet", "--", name), cwd = dir, timeoutMs = 30_000)
        if (!r.ok) return@withContext Result.failure(IllegalStateException(r.describe("git rm --cached")))
        if (exposure(paths, dir, name) == GitExposure.NotIgnored) runCatching { ignore(dir, name) }
        Result.success(Unit)
    }

    /** Adds `/name` to the project's `.gitignore`. */
    fun ignore(dir: File, name: String) {
        val file = File(dir, ".gitignore")
        val text = if (file.isFile) file.readText() else ""
        FileSave.writeAtomically(file, EnvGitignore.withEntry(text, name))
    }
}

/**
 * A project's `.env` (or `.env.local`, `.env.development`): variables with their values hidden
 * until tapped, add/edit/delete that keep the rest of the file as it was, variables the example
 * file lists but this one lacks, and a warning when git would commit it.
 */
@Composable
fun EnvScreen(project: File, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val paths = remember { RuntimePaths(context) }
    val scope = rememberCoroutineScope()
    val colors = MaterialTheme.colorScheme

    var name by rememberSaveable(project.path) { mutableStateOf(EnvFiles.VARIANTS.first()) }
    var variants by remember { mutableStateOf(listOf(EnvFiles.VARIANTS.first())) }
    var loadedText by remember { mutableStateOf<String?>(null) }
    var doc by remember { mutableStateOf<EnvDocument?>(null) }
    var example by remember { mutableStateOf<Pair<String, EnvDocument>?>(null) }
    var exposure by remember { mutableStateOf(GitExposure.Unknown) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    val revealed = remember { mutableStateListOf<String>() }
    /** The variable being edited: its key ("" for a new one) and starting value. */
    var editing by remember { mutableStateOf<Pair<String, String>?>(null) }
    val file = File(project, name)

    LaunchedEffect(project, name, reload) {
        val loaded = withContext(Dispatchers.IO) {
            runCatching {
                val present = EnvFiles.VARIANTS.filter { it == EnvFiles.VARIANTS.first() || File(project, it).isFile }
                val text = EnvStore.read(File(project, name))
                val ex = EnvFiles.EXAMPLES.map { File(project, it) }.firstOrNull { it.isFile }
                    ?.let { f -> runCatching { f.name to EnvFormat.parse(EnvStore.read(f)) }.getOrNull() }
                Triple(present, text, ex)
            }
        }
        loaded.onSuccess { (present, text, ex) ->
            variants = present; loadedText = text; doc = EnvFormat.parse(text); example = ex; error = null
        }.onFailure { error = it.message ?: it.toString(); doc = null }
        // Only worth asking git when the file exists (or is about to).
        exposure = if (File(project, name).exists()) EnvStore.exposure(paths, project, name) else GitExposure.Unknown
    }

    /** Applies [change] and writes the file, unless it changed on disk since it was read. */
    fun save(done: String, change: (EnvDocument) -> EnvDocument) {
        val current = doc ?: return
        val before = loadedText ?: ""
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val onDisk = EnvStore.read(file)
                    check(onDisk == before) { "$name changed on disk; reloaded it. Try again." }
                    val text = change(current).render()
                    FileSave.writeAtomically(file, text)
                    text
                }
            }
            result.onSuccess { text ->
                loadedText = text; doc = EnvFormat.parse(text); error = null
                Toast.makeText(context, done, Toast.LENGTH_SHORT).show()
                if (exposure == GitExposure.Unknown) exposure = EnvStore.exposure(paths, project, name)
            }.onFailure {
                error = it.message ?: it.toString()
                reload++
            }
        }
    }

    Column(Modifier.fillMaxSize().background(colors.background).systemBarsPadding().imePadding()) {
        ScreenHeader("Environment", onBack) {
            IconButton(onClick = { editing = "" to "" }, enabled = doc != null) { PhIcon(Ph.Plus, "Add variable", size = 20.dp) }
        }
        LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (variants.size > 1) item {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (v in variants) Chip(v, onClick = { if (v != name) { name = v; revealed.clear() } }, selected = v == name)
                }
            }
            error?.let { e -> item { WarningBanner(e) } }
            when (exposure) {
                GitExposure.NotIgnored -> item {
                    WarningBanner("$name isn't ignored by git, so its secrets could be committed.") {
                        TextButton(onClick = {
                            scope.launch {
                                val ok = withContext(Dispatchers.IO) { runCatching { EnvStore.ignore(project, name) }.isSuccess }
                                if (!ok) error = "Couldn't update .gitignore"
                                exposure = EnvStore.exposure(paths, project, name)
                            }
                        }) { Text("Ignore it") }
                    }
                }
                GitExposure.Tracked -> item {
                    WarningBanner("$name is already committed. Stop tracking it (the file stays here).") {
                        TextButton(onClick = {
                            scope.launch {
                                EnvStore.untrack(paths, project, name).onFailure { error = it.message }
                                exposure = EnvStore.exposure(paths, project, name)
                            }
                        }) { Text("Untrack") }
                    }
                }
                else -> {}
            }

            val d = doc
            item { SectionLabel(if (d == null) name else "$name · ${d.keys.size} variables") }
            item {
                Group {
                    when {
                        d == null -> GroupRow("Reading $name…", trailing = {})
                        d.entries.isEmpty() -> GroupRow(
                            if (file.exists()) "No variables yet" else "$name doesn't exist yet",
                            subtitle = "Add one with +; it's saved to $name.", icon = Ph.LockSimple, trailing = {},
                        )
                        else -> for (key in d.keys) {
                            val value = d.value(key).orEmpty()
                            val shown = key in revealed
                            GroupRow(
                                key,
                                subtitle = if (shown) value.ifEmpty { "(empty)" } else EnvFiles.masked(value),
                                subtitleLines = if (shown) 4 else 1,
                                onClick = { editing = key to value },
                                trailing = {
                                    IconButton(onClick = { if (shown) revealed.remove(key) else revealed.add(key) }) {
                                        PhIcon(Ph.Eye, if (shown) "Hide value" else "Show value", size = 18.dp,
                                            tint = if (shown) colors.primary else colors.onSurfaceVariant)
                                    }
                                },
                            )
                        }
                    }
                }
            }

            val ex = example
            val missing = if (d != null && ex != null) EnvFiles.missing(d, ex.second) else emptyList()
            if (ex != null && missing.isNotEmpty()) {
                item { SectionLabel("In ${ex.first}, missing here · ${missing.size}") }
                item {
                    Group {
                        for (entry in missing) GroupRow(
                            entry.key,
                            subtitle = if (entry.value.isEmpty()) "Empty in the example" else "Added with the example's value; edit it after",
                            trailing = {
                                TextButton(onClick = { save("Added ${entry.key}") { it.set(entry.key, entry.value) } }) { Text("Add") }
                            },
                        )
                        if (missing.size > 1) GroupRow("Add all ${missing.size}", icon = Ph.Plus, onClick = {
                            save("Added ${missing.size} variables") { missing.fold(it) { acc, e -> acc.set(e.key, e.value) } }
                        })
                    }
                }
            }
            item {
                Text(
                    "Comments, blank lines, order, quotes and export prefixes stay as they are; only the lines you change are rewritten.",
                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, modifier = Modifier.padding(horizontal = 6.dp),
                )
            }
        }
    }

    editing?.let { (oldKey, oldValue) ->
        EditVariableSheet(
            oldKey, oldValue, existing = doc?.keys.orEmpty(),
            onDismiss = { editing = null },
            onSave = { key, value ->
                editing = null
                save(if (oldKey.isEmpty()) "Added $key" else "Saved $key") {
                    if (oldKey.isEmpty()) it.set(key, value) else it.replace(oldKey, key, value)
                }
            },
            onDelete = if (oldKey.isEmpty()) null else ({
                editing = null
                revealed.remove(oldKey)
                save("Deleted $oldKey") { it.remove(oldKey) }
            }),
        )
    }
}

@Composable
private fun EditVariableSheet(
    oldKey: String,
    oldValue: String,
    existing: List<String>,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit,
    onDelete: (() -> Unit)?,
) {
    var key by remember { mutableStateOf(oldKey) }
    var value by remember { mutableStateOf(oldValue) }
    var show by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val trimmed = key.trim()
    val problem = when {
        trimmed.isEmpty() -> null
        !EnvFormat.isValidKey(trimmed) -> "Letters, digits, _ . and -; not starting with a digit."
        trimmed != oldKey && trimmed in existing -> "$trimmed is already set; edit that one instead."
        else -> null
    }
    AppSheet(
        if (oldKey.isEmpty()) "New variable" else oldKey,
        onDismiss = onDismiss,
        icon = Ph.LockSimple,
        tile = AppColors.tiles.peach,
        actions = {
            if (onDelete != null) OutlinedButton(
                onClick = { if (confirmDelete) onDelete() else confirmDelete = true },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Text(if (confirmDelete) "Really delete" else "Delete") }
            Button(
                onClick = { onSave(trimmed, value) },
                enabled = trimmed.isNotEmpty() && problem == null,
                modifier = Modifier.weight(1f),
            ) { Text("Save") }
        },
    ) {
        OutlinedTextField(
            key, { key = it }, label = { Text("Name") }, singleLine = true,
            isError = problem != null, supportingText = problem?.let { { Text(it) } },
            keyboardOptions = Keyboards.Identifier,
            textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value, { value = it }, label = { Text("Value") },
            // Hidden like a password until asked: people edit these with others around.
            visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = Keyboards.Code,
            singleLine = !show, maxLines = 6,
            textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
            trailingIcon = {
                IconButton(onClick = { show = !show }) {
                    PhIcon(Ph.Eye, if (show) "Hide value" else "Show value", size = 18.dp,
                        tint = if (show) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "Quotes are added when the value needs them (spaces, #, quotes, new lines).",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
