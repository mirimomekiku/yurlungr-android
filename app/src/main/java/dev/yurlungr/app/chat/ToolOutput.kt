package dev.yurlungr.app.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.yurlungr.app.ui.Ph
import dev.yurlungr.app.ui.PhIcon

/** Long tool output folded to its first and last lines, with the first error easy to reach. */
object OutputFold {
    /** A line that reads like a failure: compiler errors, stack traces, failed tests, npm/gradle errors. */
    private val ERROR = Regex(
        "(?i)(^|[^a-z])(error|exception|traceback|fatal|failed|failure|panic|segmentation fault)([^a-z]|$)|^\\s*E\\s|✗|✘|FAILED",
    )
    /** Lines that mention errors without being one (summaries of zero failures). */
    private val NOT_ERROR = Regex("(?i)\\b0 (errors?|failed|failures?)\\b|\\bno errors?\\b|errors?: 0\\b|failures?: 0\\b")

    fun isError(line: String): Boolean = ERROR.containsMatchIn(line) && !NOT_ERROR.containsMatchIn(line)

    /** Index of the first line that reads like an error, or null. */
    fun firstError(lines: List<String>): Int? = lines.indexOfFirst(::isError).takeIf { it >= 0 }

    /** Lines to show while folded: the first [head], a gap, the last [tail]; null when it all fits. */
    fun fold(lines: List<String>, head: Int = 4, tail: Int = 10): Pair<IntRange, IntRange>? =
        if (lines.size <= head + tail + 2) null else (0 until head) to (lines.size - tail until lines.size)

    /** A window of lines around [line], for jumping to an error. */
    fun around(line: Int, size: Int, before: Int = 3, after: Int = 12): IntRange =
        (line - before).coerceAtLeast(0) until (line + after + 1).coerceAtMost(size)
}

private val Bg = Color(0xFF0D1117)
private val Fg = Color(0xFFE6EDF3)
private val Dim = Color(0xFF8B949E)
private val ErrBg = Color(0x40F85149)
private val ErrFg = Color(0xFFFF7B72)

/**
 * A tool's text output: long output shows its first and last lines with the middle folded away;
 * "Jump to error" opens the lines around the first error; Copy takes all of it.
 */
@Composable
fun ToolOutputBlock(text: String) {
    val context = LocalContext.current
    val lines = remember(text) { text.trimEnd('\n').lines() }
    val error = remember(lines) { OutputFold.firstError(lines) }
    val folded = remember(lines) { OutputFold.fold(lines) }
    // null: folded (or all of it, when short); a range: that window; ALL: everything.
    var view by remember(text) { mutableStateOf<IntRange?>(null) }
    val showAll = folded == null || view == ALL

    Column(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).background(Bg)) {
        Row(Modifier.fillMaxWidth().padding(start = 10.dp, end = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${lines.size} line${if (lines.size == 1) "" else "s"}", color = Dim, fontSize = 11.sp, modifier = Modifier.weight(1f))
            if (error != null && folded != null && view == null) Action("Jump to error", ErrFg) { view = OutputFold.around(error, lines.size) }
            if (folded != null) Action(if (showAll) "Collapse" else "Show all", Dim) { view = if (showAll) null else ALL }
            Action("Copy", Dim) {
                context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Output", text))
                Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
            }
        }
        val body = Modifier
            .fillMaxWidth()
            .heightIn(max = 420.dp)
            .verticalScroll(rememberScrollState())
            .horizontalScroll(rememberScrollState())
            .padding(start = 10.dp, end = 10.dp, bottom = 8.dp)
        Column(body) {
            val window = view
            when {
                // Every line: one Text per chunk of lines, not one per line, so huge output stays responsive.
                showAll -> for (chunk in lines.indices.chunked(CHUNK_LINES)) {
                    key(chunk.first()) { OutputChunk(lines, chunk.first()..chunk.last(), error) }
                }
                window != null -> {
                    if (window.first > 0) Gap(window.first) { view = ALL }
                    for (i in window) OutputLine(lines[i], i == error)
                    val after = lines.size - 1 - window.last
                    if (after > 0) Gap(after) { view = ALL }
                }
                else -> {
                    val (head, tail) = folded!!
                    for (i in head) OutputLine(lines[i], i == error)
                    Gap(tail.first - head.last - 1) { view = ALL }
                    for (i in tail) OutputLine(lines[i], i == error)
                }
            }
        }
    }
}

/** Marker for "show every line". */
private val ALL = IntRange(-1, -1)

/** Lines drawn by one Text when showing everything. */
private const val CHUNK_LINES = 200

/** [range] of [lines] as one text, error lines coloured and the first error ([error]) highlighted. */
@Composable
private fun OutputChunk(lines: List<String>, range: IntRange, error: Int?) {
    val text = remember(lines, range, error) {
        androidx.compose.ui.text.buildAnnotatedString {
            for (i in range) {
                val l = lines[i]
                val start = length
                append(l)
                val isError = i == error || OutputFold.isError(l)
                if (isError) addStyle(androidx.compose.ui.text.SpanStyle(color = ErrFg, background = if (i == error) ErrBg else Color.Transparent), start, length)
                if (i < range.last) append('\n')
            }
        }
    }
    Text(text, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp, softWrap = false, color = Fg)
}

@Composable
private fun Action(label: String, color: Color, onClick: () -> Unit) {
    Text(
        label,
        color = color,
        fontSize = 11.sp,
        modifier = Modifier.clip(MaterialTheme.shapes.small).clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 6.dp),
    )
}

@Composable
private fun OutputLine(line: String, highlighted: Boolean) {
    val isError = highlighted || OutputFold.isError(line)
    Text(
        line.ifEmpty { " " },
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
        lineHeight = 15.sp,
        softWrap = false,
        color = if (isError) ErrFg else Fg,
        modifier = if (highlighted) Modifier.background(ErrBg) else Modifier,
    )
}

@Composable
private fun Gap(hidden: Int, onClick: () -> Unit) {
    Row(Modifier.clickable(onClick = onClick).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        PhIcon(Ph.CaretDown, null, size = 12.dp, tint = Dim)
        Spacer(Modifier.width(6.dp))
        Text("$hidden more line${if (hidden == 1) "" else "s"}", color = Dim, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
    }
}

/** The first error line of a tool call's output, for its collapsed card. */
fun firstErrorLine(item: ChatItem.ToolCall): String? =
    item.content.asSequence().filterIsInstance<ToolContent.Text>()
        .mapNotNull { c -> c.text.lineSequence().firstOrNull(OutputFold::isError)?.trim() }
        .firstOrNull()
