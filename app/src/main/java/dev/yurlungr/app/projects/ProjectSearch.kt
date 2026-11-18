package dev.yurlungr.app.projects

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.yurlungr.app.chat.highlight
import dev.yurlungr.app.runtime.CommandRunner
import dev.yurlungr.app.runtime.RuntimePaths
import dev.yurlungr.app.ui.AppColors
import dev.yurlungr.app.ui.AppSheet
import dev.yurlungr.app.ui.Chip
import dev.yurlungr.app.ui.Ph
import dev.yurlungr.app.ui.WaveDots
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/** One matching line. */
data class SearchHit(val path: String, val line: Int, val text: String)

/** Searching a project with ripgrep (respects .gitignore, skips .git). */
object ProjectSearch {
    const val MAX_HITS = 300

    fun args(rg: File, query: String, regex: Boolean): List<String> = buildList {
        add(rg.path)
        addAll(listOf("--no-heading", "--line-number", "--null", "--color", "never", "--smart-case"))
        addAll(listOf("--max-columns", "300", "--max-columns-preview", "--max-count", "40", "--hidden", "--glob", "!.git"))
        if (!regex) add("--fixed-strings")
        addAll(listOf("-e", query, "."))
    }

    /** `path\0line:text` lines (rg --null) into hits, at most [MAX_HITS]. */
    fun parse(output: String): List<SearchHit> = output.lineSequence().mapNotNull { line ->
        val nul = line.indexOf('\u0000')
        if (nul < 0) return@mapNotNull null
        val rest = line.substring(nul + 1)
        val colon = rest.indexOf(':')
        val n = rest.substring(0, colon.coerceAtLeast(0)).toIntOrNull() ?: return@mapNotNull null
        SearchHit(line.substring(0, nul).removePrefix("./"), n, rest.substring(colon + 1))
    }.take(MAX_HITS).toList()
}

/** Find text across the project; tap a result to open the file. */
@Composable
fun ProjectSearchSheet(root: File, onDismiss: () -> Unit, onOpenFile: (File, Int?) -> Unit) {
    val context = LocalContext.current
    val paths = remember { RuntimePaths(context) }
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var regex by remember { mutableStateOf(false) }
    var hits by remember { mutableStateOf<List<SearchHit>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var running by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }
    fun search(now: Boolean) {
        job?.cancel()
        val q = query
        if (q.isBlank()) { hits = null; error = null; running = false; return }
        job = scope.launch {
            if (!now) delay(400) // typing: wait for a pause
            running = true
            try {
                val rg = File(paths.bin, "rg")
                if (!rg.canExecute()) {
                    error = "ripgrep isn't installed. Install the developer tools from System checks."
                    return@launch
                }
                val r = CommandRunner.run(paths, ProjectSearch.args(rg, q, regex), cwd = root, timeoutMs = 20_000, forParsing = true)
                // rg exits 1 when nothing matched, 2 on an error (a bad regex).
                error = if (r.exitCode == 2 || r.timedOut) r.errorOutput.lines().firstOrNull { it.isNotBlank() } ?: r.describe("Searching") else null
                hits = ProjectSearch.parse(r.output)
            } finally {
                // Cancelled by a newer search: that one owns the spinner now.
                if (job == null || job === coroutineContext[Job]) running = false
            }
        }
    }
    LaunchedEffect(query, regex) { search(now = false) }
    val colors = MaterialTheme.colorScheme
    AppSheet("Search ${root.name}", onDismiss = onDismiss, icon = Ph.MagnifyingGlass, tile = AppColors.tiles.sky) {
        OutlinedTextField(
            query, { query = it },
            placeholder = { Text("Text in any file") },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { search(now = true) }),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("Regular expression", selected = regex, onClick = { regex = !regex })
            LocalFindCode.current?.let { find -> Chip("All projects", onClick = { onDismiss(); find(null) }) }
            if (running) WaveDots(dotSize = 4.dp)
        }
        error?.let { Text(it, color = colors.error, style = MaterialTheme.typography.bodySmall) }
        val list = hits
        when {
            list == null -> Text("Matches show as you type. Ignored files (.gitignore) and .git are skipped; uppercase letters make it case-sensitive.",
                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            list.isEmpty() && error == null -> Text("No matches.", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            else -> {
                val byFile = list.groupBy { it.path }
                Text(
                    "${list.size}${if (list.size >= ProjectSearch.MAX_HITS) "+" else ""} matches in ${byFile.size} file${if (byFile.size == 1) "" else "s"}",
                    style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant,
                )
                for ((path, fileHits) in byFile) {
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(colors.surfaceContainerHigh)
                            .clickable { onOpenFile(File(root, path), fileHits.firstOrNull()?.line) }.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Text(path, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        for (h in fileHits.take(8)) {
                            Row(Modifier.clickable { onOpenFile(File(root, path), h.line) }) {
                                Text("${h.line}", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = colors.onSurfaceVariant, modifier = Modifier.widthIn(min = 34.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    if (regex) AnnotatedString(h.text.trim()) else highlight(AnnotatedString(h.text.trim()), query, colors.tertiaryContainer),
                                    fontFamily = FontFamily.Monospace, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        if (fileHits.size > 8) Text("+${fileHits.size - 8} more", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
