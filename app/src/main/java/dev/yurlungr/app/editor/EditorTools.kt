package dev.yurlungr.app.editor

import android.content.Context
import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import dev.yurlungr.app.projects.ProjectSearch
import dev.yurlungr.app.projects.SearchHit
import dev.yurlungr.app.runtime.CommandRunner
import dev.yurlungr.app.runtime.RuntimePaths
import dev.yurlungr.app.ui.AppColors
import dev.yurlungr.app.ui.AppSheet
import dev.yurlungr.app.ui.Ph
import dev.yurlungr.app.ui.SkeletonListItem
import java.io.File

/**
 * The editor's open files (tabs) and their unsaved text: switching tabs keeps edits in memory
 * instead of asking to save. Lost when the app's process ends, like any unsaved edit.
 */
object OpenFiles {
    val tabs = androidx.compose.runtime.mutableStateListOf<String>()
    val dirty = androidx.compose.runtime.mutableStateMapOf<String, Boolean>()
    private val buffers = HashMap<String, String>()

    fun open(path: String) { if (path !in tabs) tabs += path }
    fun close(path: String) { tabs.remove(path); buffers.remove(path); dirty.remove(path) }
    fun buffer(path: String): String? = buffers[path]

    /** Unsaved [text] for [path], or null once it's saved or discarded. */
    fun keep(path: String, text: String?) {
        if (text == null) buffers.remove(path) else buffers[path] = text
        dirty[path] = text != null
    }
}

/** Files opened recently, newest first (for the command palette). */
object RecentFiles {
    private const val PREFS = "recent_files"
    private const val MAX = 30

    fun record(context: Context, file: File) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val list = (listOf(file.path) + prefs.getString("list", "").orEmpty().split('\n').filter { it.isNotBlank() && it != file.path }).take(MAX)
        prefs.edit { putString("list", list.joinToString("\n")) }
    }

    fun list(context: Context): List<File> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("list", "").orEmpty()
            .split('\n').filter { it.isNotBlank() }.map(::File).filter { it.isFile }
}

/** A run of lines last changed by one commit. */
data class BlameRange(val first: Int, val last: Int, val hash: String, val author: String, val time: Long, val summary: String) {
    val uncommitted: Boolean get() = hash.all { it == '0' }
}

object EditorTools {
    /** The identifier around [offset] ("fooBar" in "x = fooBar()"), or null. */
    fun wordAt(text: String, offset: Int): String? {
        fun part(c: Char) = c.isLetterOrDigit() || c == '_' || c == '$'
        var start = offset.coerceIn(0, text.length)
        var end = start
        while (start > 0 && part(text[start - 1])) start--
        while (end < text.length && part(text[end])) end++
        return text.substring(start, end).takeIf { it.isNotEmpty() && !it[0].isDigit() }
    }

    /** Where [word] is probably defined, across languages: `fun word`, `class word`, `def word`, `word = (…) =>`. */
    fun definitionPattern(word: String): String {
        // Identifiers are letters, digits, _ and $; only $ means something in a regex.
        val w = word.replace("$", "\\$")
        return "\\b(fun|class|interface|object|enum|def|function|const|let|var|val|type|struct|fn|func|trait|impl|record|module|typealias)\\s+$w\\b" +
            "|\\b$w\\s*[:=]\\s*(async\\s*)?(function\\b|\\([^)]*\\)\\s*=>)"
    }

    /** `git blame --line-porcelain` into ranges of consecutive lines from the same commit. */
    fun parseBlame(output: String): List<BlameRange> {
        val out = ArrayList<BlameRange>()
        var hash = ""; var line = 0; var author = ""; var time = 0L; var summary = ""
        for (l in output.lineSequence()) {
            when {
                l.startsWith("\t") -> {
                    val prev = out.lastOrNull()
                    if (prev != null && prev.hash == hash && prev.last == line - 1) out[out.lastIndex] = prev.copy(last = line)
                    else out += BlameRange(line, line, hash, author, time, summary)
                }
                Regex("^[0-9a-f]{40} \\d+ \\d+").containsMatchIn(l) -> { val p = l.split(' '); hash = p[0]; line = p[2].toInt() }
                l.startsWith("author ") -> author = l.removePrefix("author ")
                l.startsWith("author-time ") -> time = l.removePrefix("author-time ").toLongOrNull() ?: 0
                l.startsWith("summary ") -> summary = l.removePrefix("summary ")
            }
        }
        return out
    }

    /** The folder git and searches work from: the repository's top, else the file's folder. */
    fun rootOf(file: File): File {
        var dir: File? = file.parentFile
        while (dir != null) {
            if (File(dir, ".git").exists()) return dir
            dir = dir.parentFile
        }
        return file.parentFile ?: file
    }
}

/** Who last changed each part of [file]; tap a range to jump there. */
@Composable
fun BlameSheet(file: File, onDismiss: () -> Unit, onLine: (Int) -> Unit) {
    val context = LocalContext.current
    val paths = remember { RuntimePaths(context) }
    val root = remember(file) { EditorTools.rootOf(file) }
    val result by produceState<Result<List<BlameRange>>?>(null, file) {
        val r = CommandRunner.run(paths, listOf(File(paths.bin, "git").path, "-C", root.path, "blame", "--line-porcelain", "--", file.relativeTo(root).path),
            cwd = root, timeoutMs = 30_000, forParsing = true)
        value = if (r.ok) Result.success(EditorTools.parseBlame(r.output))
        else Result.failure(IllegalStateException(r.errorOutput.lines().firstOrNull { it.isNotBlank() } ?: r.describe("git blame")))
    }
    val colors = MaterialTheme.colorScheme
    AppSheet("Blame", onDismiss = onDismiss, subtitle = file.name, icon = Ph.GitCommit, tile = AppColors.tiles.lilac) {
        val r = result
        when {
            r == null -> repeat(4) { SkeletonListItem() }
            r.isFailure -> Text(r.exceptionOrNull()?.message ?: "Couldn't blame this file", color = colors.error)
            else -> for (b in r.getOrThrow()) {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(colors.surfaceContainerHigh)
                        .clickable { onLine(b.first) }.padding(10.dp),
                ) {
                    Text(if (b.first == b.last) "${b.first}" else "${b.first}–${b.last}", fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                        color = colors.onSurfaceVariant, modifier = Modifier.width(64.dp))
                    Column(Modifier.weight(1f)) {
                        Text(if (b.uncommitted) "Not committed yet" else b.summary, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (!b.uncommitted) Text(
                            "${b.author} · ${DateUtils.getRelativeTimeSpanString(b.time * 1000)} · ${b.hash.take(7)}",
                            style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant, maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

/** Likely definitions of [word] in the project (a ripgrep search, not a language server). */
@Composable
fun DefinitionSheet(root: File, word: String, onDismiss: () -> Unit, onOpen: (File, Int) -> Unit) {
    val context = LocalContext.current
    val paths = remember { RuntimePaths(context) }
    val hits by produceState<List<SearchHit>?>(null, root, word) {
        val rg = File(paths.bin, "rg")
        if (!rg.canExecute()) { value = emptyList(); return@produceState }
        val r = CommandRunner.run(paths, ProjectSearch.args(rg, EditorTools.definitionPattern(word), regex = true), cwd = root, timeoutMs = 20_000, forParsing = true)
        value = ProjectSearch.parse(r.output)
    }
    val colors = MaterialTheme.colorScheme
    AppSheet("Definition of $word", onDismiss = onDismiss, subtitle = "Lines that look like a definition, found by searching ${root.name}.", icon = Ph.MagnifyingGlass) {
        val list = hits
        when {
            list == null -> repeat(3) { SkeletonListItem() }
            list.isEmpty() -> Text("Nothing that looks like a definition of $word.", color = colors.onSurfaceVariant)
            else -> for (h in list.take(60)) {
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(colors.surfaceContainerHigh)
                        .clickable { onOpen(File(root, h.path), h.line) }.padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Row {
                        Text(h.path, fontFamily = FontFamily.Monospace, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Spacer(Modifier.width(6.dp))
                        Text(":${h.line}", fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = colors.onSurfaceVariant)
                    }
                    Text(h.text.trim(), fontFamily = FontFamily.Monospace, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
