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
    data class Text(val text: String) : MergePart
    data class Conflict(val ours: String, val base: String?, val theirs: String, val oursLabel: String, val theirsLabel: String) : MergePart
}

object Merge {
    fun hasConflicts(text: String) = Regex("(?m)^<{7}( |$)").containsMatchIn(text) && Regex("(?m)^>{7}( |$)").containsMatchIn(text)

    /** Splits [text] at `<<<<<<<` / `|||||||` / `=======` / `>>>>>>>` markers (diff3 bases included). */
    fun parse(text: String): List<MergePart> {
        val out = ArrayList<MergePart>()
        val plain = StringBuilder()
        val lines = text.split('\n')
        var i = 0
        while (i < lines.size) {
            val l = lines[i]
            if (l.startsWith("<<<<<<<")) {
                val start = i
                val ours = ArrayList<String>(); val base = ArrayList<String>(); val theirs = ArrayList<String>()
                var section = 0 // 0 ours, 1 base, 2 theirs
                var hasBase = false
                var end = -1
                var j = i + 1
                while (j < lines.size) {
                    val m = lines[j]
                    when {
                        m.startsWith("|||||||") && section == 0 -> { section = 1; hasBase = true }
                        m.startsWith("=======") && section != 2 -> section = 2
                        m.startsWith(">>>>>>>") && section == 2 -> { end = j; break }
                        else -> (when (section) { 0 -> ours; 1 -> base; else -> theirs }).add(m)
                    }
                    j++
                }
                if (end < 0) { plain.append(l).append('\n'); i++; continue } // unterminated: leave as text
                if (plain.isNotEmpty()) { out += MergePart.Text(plain.toString()); plain.clear() }
                out += MergePart.Conflict(ours.joinToString("\n"), if (hasBase) base.joinToString("\n") else null, theirs.joinToString("\n"),
                    lines[start].removePrefix("<<<<<<<").trim().ifEmpty { "ours" }, lines[end].removePrefix(">>>>>>>").trim().ifEmpty { "theirs" })
                i = end + 1
                // The conflict's last line had its own newline (unless it ended the file).
                if (i < lines.size) plain.append("")
                continue
            }
            plain.append(l)
            if (i < lines.lastIndex) plain.append('\n')
            i++
        }
        if (plain.isNotEmpty()) out += MergePart.Text(plain.toString())
        return out
    }

    /** The file with each conflict replaced by its resolution (null keeps the markers). */
    fun render(parts: List<MergePart>, resolutions: List<String?>): String {
        val sb = StringBuilder()
        var c = 0
        for (p in parts) when (p) {
            is MergePart.Text -> sb.append(p.text)
            is MergePart.Conflict -> {
                val r = resolutions.getOrNull(c++)
                if (r != null) sb.append(r).append(if (r.isEmpty()) "" else "\n")
                else {
                    sb.append("<<<<<<< ${p.oursLabel}\n").append(p.ours).append('\n')
                    p.base?.let { sb.append("|||||||\n").append(it).append('\n') }
                    sb.append("=======\n").append(p.theirs).append('\n').append(">>>>>>> ${p.theirsLabel}\n")
                }
            }
        }
        return sb.toString()
    }
}

/** Resolve a conflicted file conflict by conflict: ours, theirs, both, or your own edit. */
@Composable
fun MergeScreen(file: File, onBack: () -> Unit, onOpenEditor: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var parts by remember { mutableStateOf<List<MergePart>?>(null) }
    val resolutions = remember { mutableStateListOf<String?>() }
    var editing by remember { mutableStateOf<Int?>(null) }
    // The file's line endings, written back as they were (not every line rewritten to LF).
    var crlf by remember { mutableStateOf(false) }
    // Resolutions picked since the last save; Back asks before dropping them.
    var unsaved by remember { mutableStateOf(false) }
    var confirmBack by remember { mutableStateOf(false) }
    fun tryBack() { if (unsaved) confirmBack = true else onBack() }
    BackHandler(onBack = ::tryBack)
    LaunchedEffect(file) {
        val text = withContext(Dispatchers.IO) { file.readText() }
        crlf = text.contains("\r\n")
        val p = Merge.parse(text.replace("\r\n", "\n"))
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
                val rendered = Merge.render(p, resolutions)
                dev.yurlungr.app.editor.FileSave.writeAtomically(file, if (crlf) rendered.replace("\n", "\r\n") else rendered)
                if (markResolved) {
                    val paths = RuntimePaths(context)
                    val root = EditorTools.rootOf(file)
                    check(CommandRunner.run(paths, listOf(File(paths.bin, "git").path, "-C", root.path, "add", "--", file.relativeTo(root).path), cwd = root).ok) { "git add failed" }
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
                        OutlinedButton(onClick = { resolutions[i] = c.ours + "\n" + c.theirs; unsaved = true }) { Text("Both") }
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
