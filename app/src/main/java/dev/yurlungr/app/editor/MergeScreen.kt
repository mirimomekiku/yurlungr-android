package dev.yurlungr.app.editor

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.yurlungr.app.projects.Git
import dev.yurlungr.app.runtime.CommandRunner
import dev.yurlungr.app.runtime.RuntimePaths
import dev.yurlungr.app.ui.AppColors
import dev.yurlungr.app.ui.ScreenHeader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** A file with git conflict markers, as plain text and conflicts in order. */
sealed interface MergePart {
    /** Text outside conflicts, exactly as in the file (line endings included). */
    data class Text(val text: String) : MergePart

    /**
     * One conflict. [ours], [base] and [theirs] are for showing and editing: lines joined with "\n", no
     * final newline. The `…Raw` forms and [raw] are the file's own bytes, so what's picked is written back
     * with the line endings it had.
     */
    data class Conflict(
        val ours: String, val base: String?, val theirs: String, val oursLabel: String, val theirsLabel: String,
        /** The whole conflict as in the file, markers included: written back while it's unresolved. */
        val raw: String = "",
        val oursRaw: String = if (ours.isEmpty()) "" else "$ours\n",
        val theirsRaw: String = if (theirs.isEmpty()) "" else "$theirs\n",
        /** The ending of the conflict's first marker line: an edited resolution's lines get it. */
        val eol: String = "\n",
        /** False when the closing marker was the file's last line, without a newline. */
        val endsWithNewline: Boolean = true,
    ) : MergePart
}

object Merge {
    /** A line without its ending, and the ending ("\n", "\r\n", or "" for a last line without one). */
    internal data class Line(val text: String, val eol: String) { val raw get() = text + eol }

    internal fun lines(text: String): List<Line> {
        val out = ArrayList<Line>()
        var start = 0
        while (start < text.length) {
            val nl = text.indexOf('\n', start)
            if (nl < 0) { out += Line(text.substring(start), ""); break }
            val crlf = nl > start && text[nl - 1] == '\r'
            out += Line(text.substring(start, if (crlf) nl - 1 else nl), if (crlf) "\r\n" else "\n")
            start = nl + 1
        }
        return out
    }

    /**
     * Git's marker: exactly seven [c] followed by a space (and a label) or the end of the line. Not
     * `========` (a Markdown heading underline) or `>>>>>>>>` (deeply quoted mail).
     */
    internal fun isMarker(line: String, c: Char): Boolean =
        line.length >= 7 && (0 until 7).all { line[it] == c } && (line.length == 7 || line[7] == ' ')

    fun hasConflicts(text: String): Boolean {
        val all = lines(text)
        return all.any { isMarker(it.text, '<') } && all.any { isMarker(it.text, '>') }
    }

    /** Ours then theirs, as the "Both" resolution. */
    fun both(c: MergePart.Conflict): String = when {
        c.ours.isEmpty() -> c.theirs
        c.theirs.isEmpty() -> c.ours
        else -> c.ours + "\n" + c.theirs
    }

    /** Splits [text] at `<<<<<<<` / `|||||||` / `=======` / `>>>>>>>` markers (diff3 bases included). */
    fun parse(text: String): List<MergePart> {
        val out = ArrayList<MergePart>()
        val plain = StringBuilder()
        val lines = lines(text)
        var i = 0
        while (i < lines.size) {
            val l = lines[i]
            if (isMarker(l.text, '<')) {
                val ours = ArrayList<Line>(); val base = ArrayList<Line>(); val theirs = ArrayList<Line>()
                var section = 0 // 0 ours, 1 base, 2 theirs
                var hasBase = false
                var end = -1
                var j = i + 1
                while (j < lines.size) {
                    val m = lines[j].text
                    when {
                        // Another opening marker first: this one isn't a conflict git wrote; leave it as text.
                        isMarker(m, '<') && section == 0 -> break
                        isMarker(m, '|') && section == 0 -> { section = 1; hasBase = true }
                        isMarker(m, '=') && section != 2 -> section = 2
                        isMarker(m, '>') && section == 2 -> { end = j; break }
                        else -> (when (section) { 0 -> ours; 1 -> base; else -> theirs }).add(lines[j])
                    }
                    j++
                }
                if (end < 0) { plain.append(l.raw); i++; continue } // unterminated: leave as text
                if (plain.isNotEmpty()) { out += MergePart.Text(plain.toString()); plain.clear() }
                fun shown(side: List<Line>) = side.joinToString("\n") { it.text }
                fun raw(side: List<Line>) = side.joinToString("") { it.raw }
                out += MergePart.Conflict(
                    ours = shown(ours), base = if (hasBase) shown(base) else null, theirs = shown(theirs),
                    oursLabel = l.text.substring(7).trim().ifEmpty { "ours" },
                    theirsLabel = lines[end].text.substring(7).trim().ifEmpty { "theirs" },
                    raw = (i..end).joinToString("") { lines[it].raw },
                    oursRaw = raw(ours), theirsRaw = raw(theirs),
                    eol = l.eol.ifEmpty { "\n" },
                    endsWithNewline = lines[end].eol.isNotEmpty(),
                )
                i = end + 1
                continue
            }
            plain.append(l.raw)
            i++
        }
        if (plain.isNotEmpty()) out += MergePart.Text(plain.toString())
        return out
    }

    /**
     * The file with each conflict replaced by its resolution (null keeps the conflict as it was). Ours,
     * theirs and both are written from the file's own lines; an edited resolution uses the conflict's
     * line ending. A conflict that ended the file without a newline still does.
     */
    fun render(parts: List<MergePart>, resolutions: List<String?>): String {
        val sb = StringBuilder()
        var c = 0
        for (p in parts) when (p) {
            is MergePart.Text -> sb.append(p.text)
            is MergePart.Conflict -> {
                val r = resolutions.getOrNull(c++)
                if (r == null) {
                    sb.append(p.raw.ifEmpty { markers(p) })
                    continue
                }
                var out = when (r) {
                    p.ours -> p.oursRaw
                    p.theirs -> p.theirsRaw
                    both(p) -> p.oursRaw + p.theirsRaw
                    "" -> ""
                    else -> r.split('\n').joinToString(p.eol) + p.eol
                }
                if (!p.endsWithNewline) out = out.removeSuffix("\n").removeSuffix("\r")
                sb.append(out)
            }
        }
        return sb.toString()
    }

    /** Markers for a conflict made without its original text (not from [parse]). */
    private fun markers(p: MergePart.Conflict): String = buildString {
        append("<<<<<<< ${p.oursLabel}\n").append(p.oursRaw)
        p.base?.let { append("|||||||\n").append(it).append(if (it.isEmpty()) "" else "\n") }
        append("=======\n").append(p.theirsRaw).append(">>>>>>> ${p.theirsLabel}\n")
    }
}

private const val MAX_MERGE_BYTES = 4L * 1024 * 1024

/** Resolve a conflicted file conflict by conflict: ours, theirs, both, or your own edit. */
@Composable
fun MergeScreen(file: File, onBack: () -> Unit, onOpenEditor: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var parts by remember { mutableStateOf<List<MergePart>?>(null) }
    val resolutions = remember { mutableStateListOf<String?>() }
    var editing by remember { mutableStateOf<Int?>(null) }
    // Resolutions picked since the last save; Back asks before dropping them.
    var unsaved by remember { mutableStateOf(false) }
    var confirmBack by remember { mutableStateOf(false) }
    fun tryBack() { if (unsaved) confirmBack = true else onBack() }
    BackHandler(onBack = ::tryBack)
    // What was on disk when the conflicts were read: saving over a newer version would lose it.
    var stamp by remember { mutableStateOf<DiskStamp?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(file) {
        val read = withContext(Dispatchers.IO) {
            runCatching {
                check(file.length() <= MAX_MERGE_BYTES) { "${file.name} is too large to resolve here; use the terminal." }
                DiskStamp.of(file) to file.readBytes()
            }
        }
        val (loadedStamp, bytes) = read.getOrElse { loadError = it.message ?: "Couldn't read ${file.name}"; return@LaunchedEffect }
        // Strict, like the editor: a lenient decode would write U+FFFD over every non-UTF-8 byte on save.
        val text = FileSave.decodeUtf8(bytes) ?: run {
            loadError = "${file.name} isn't UTF-8 text, so resolving it here could damage it. Resolve it in the terminal instead."
            return@LaunchedEffect
        }
        stamp = loadedStamp
        // Line endings are kept per line (Merge keeps each line's own), so nothing is converted here.
        val p = Merge.parse(text)
        parts = p
        resolutions.clear(); repeat(p.count { it is MergePart.Conflict }) { resolutions += null }
    }
    if (confirmBack) AlertDialog(
        onDismissRequest = { confirmBack = false },
        title = { Text("Leave without saving?") },
        text = { Text("The conflicts you resolved here aren't saved to ${file.name} yet.") },
        confirmButton = { TextButton(onClick = { confirmBack = false; onBack() }) { Text("Leave") } },
        dismissButton = { TextButton(onClick = { confirmBack = false }) { Text("Keep resolving") } },
    )
    val conflicts = parts.orEmpty().filterIsInstance<MergePart.Conflict>()
    val done = resolutions.count { it != null }
    val colors = MaterialTheme.colorScheme
    val tiles = AppColors.tiles

    fun save(markResolved: Boolean) = scope.launch {
        val p = parts ?: return@launch
        val ok = withContext(Dispatchers.IO) {
            runCatching {
                check(DiskStamp.of(file) == stamp) { "${file.name} changed since it was opened. Reopen it to resolve the latest version." }
                FileSave.writeAtomically(file, Merge.render(p, resolutions))
                stamp = DiskStamp.of(file)
                if (markResolved) {
                    val paths = RuntimePaths(context)
                    val root = EditorTools.rootOf(file)
                    check(CommandRunner.run(paths, listOf(File(paths.bin, "git").path, "-C", root.path, "add", "--", file.relativeTo(root).path), cwd = root, extraEnv = Git.ENV).ok) { "git add failed" }
                }
            }
        }
        if (ok.isSuccess) unsaved = false
        Toast.makeText(context, ok.fold({ if (markResolved) "Saved and marked resolved" else "Saved" }, { "Couldn't save: ${it.message}" }), Toast.LENGTH_SHORT).show()
        if (ok.isSuccess && markResolved) onBack()
    }

    Column(Modifier.fillMaxSize().background(colors.background).systemBarsPadding()) {
        ScreenHeader("Resolve conflicts", ::tryBack) { TextButton(onClick = onOpenEditor) { Text("Editor") } }
        Text("${file.name} · $done of ${conflicts.size} resolved", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp))
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            loadError?.let { e -> item { Text(e, color = colors.error) } }
            if (parts != null && conflicts.isEmpty()) item { Text("No conflict markers in this file.", color = colors.onSurfaceVariant) }
            itemsIndexed(conflicts) { i, c ->
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(colors.surfaceContainer).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Conflict ${i + 1}" + if (resolutions[i] != null) " · resolved" else "", style = MaterialTheme.typography.titleSmall,
                        color = if (resolutions[i] != null) AppColors.status.success else colors.onSurface)
                    Side("Ours · ${c.oursLabel}", c.ours, tiles.lime.fill.copy(alpha = 0.25f))
                    c.base?.let { Side("Base (before both)", it, colors.surfaceContainerHigh) }
                    Side("Theirs · ${c.theirsLabel}", c.theirs, tiles.sky.fill.copy(alpha = 0.25f))
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(onClick = { resolutions[i] = c.ours; unsaved = true }) { Text("Use ours") }
                        OutlinedButton(onClick = { resolutions[i] = c.theirs; unsaved = true }) { Text("Use theirs") }
                        OutlinedButton(onClick = { resolutions[i] = Merge.both(c); unsaved = true }) { Text("Both") }
                        TextButton(onClick = { if (resolutions[i] == null) { resolutions[i] = c.ours; unsaved = true }; editing = i }) { Text("Edit") }
                    }
                    resolutions[i]?.let { r ->
                        if (editing == i) OutlinedTextField(r, { resolutions[i] = it; unsaved = true }, label = { Text("Result") },
                            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                            modifier = Modifier.fillMaxWidth())
                        else Side("Result", r, AppColors.status.successContainer)
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(enabled = done > 0, onClick = { save(false) }, modifier = Modifier.weight(1f)) { Text("Save") }
            Button(enabled = conflicts.isNotEmpty() && done == conflicts.size, onClick = { save(true) }, modifier = Modifier.weight(1f)) { Text("Save & mark resolved") }
        }
    }
}

@Composable
private fun Side(label: String, text: String, tint: Color) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(tint).padding(10.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text.ifEmpty { "(nothing)" }, fontFamily = FontFamily.Monospace, fontSize = 12.sp,
            modifier = Modifier.padding(top = 4.dp).horizontalScroll(rememberScrollState()))
    }
}
