package dev.yurlungr.app.projects

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.yurlungr.app.ui.Chip
import dev.yurlungr.app.ui.ThemePrefs

/** One line of a parsed diff, with its line numbers in the old and new file (null where absent). */
data class DiffLine(val kind: Kind, val text: String, val oldNo: Int? = null, val newNo: Int? = null) {
    enum class Kind { Context, Added, Removed, Hunk, Meta }
}

/** A parsed unified diff: its lines (headers dropped) and how much it adds and removes. */
data class ParsedDiff(val lines: List<DiffLine>, val added: Int, val removed: Int, val notes: List<String>) {
    val isEmpty: Boolean get() = lines.none { it.kind == DiffLine.Kind.Added || it.kind == DiffLine.Kind.Removed }

    companion object {
        private val HUNK = Regex("""^@@ -(\d+)(?:,\d+)? \+(\d+)(?:,\d+)? @@(.*)$""")

        /**
         * Parses `git diff` / `git diff-tree -p` output, or the app's synthetic "@@ new file: x @@"
         * listing of an untracked file. File headers become [notes] (renames, modes, binary).
         */
        fun parse(text: String): ParsedDiff {
            val out = ArrayList<DiffLine>()
            val notes = ArrayList<String>()
            var old = 0
            var new = 0
            var added = 0
            var removed = 0
            var inHunk = false
            for (raw in text.split('\n')) {
                val line = raw.removeSuffix("\r")
                when {
                    // A new file's header ends the previous hunk. "--- "/"+++ " only count outside a hunk:
                    // inside one they are a removed "-- …" or added "++ …" line.
                    line.startsWith("diff --git") -> inHunk = false
                    !inHunk && (line.startsWith("index ") || line.startsWith("--- ") || line.startsWith("+++ ")) -> Unit
                    line.startsWith("new file mode") -> notes += "New file"
                    line.startsWith("deleted file mode") -> notes += "Deleted file"
                    line.startsWith("rename from ") -> notes += "Renamed from " + line.removePrefix("rename from ")
                    line.startsWith("old mode ") || line.startsWith("new mode ") -> notes += line.replaceFirstChar { it.uppercase() }
                    line.startsWith("similarity index") -> Unit
                    line.startsWith("rename to ") -> Unit
                    line.startsWith("Binary files") -> notes += "Binary file changed (no text diff)"
                    line.startsWith("@@ new file:") -> {
                        // The app's own listing of an untracked file: everything is added, from line 1.
                        out += DiffLine(DiffLine.Kind.Hunk, "New file")
                        old = 0; new = 1; inHunk = true
                    }
                    HUNK.matches(line) -> {
                        val m = HUNK.find(line)!!
                        old = m.groupValues[1].toInt()
                        new = m.groupValues[2].toInt()
                        out += DiffLine(DiffLine.Kind.Hunk, m.groupValues[3].trim().ifEmpty { "Lines ${m.groupValues[2]}+" }, old, new)
                        inHunk = true
                    }
                    !inHunk -> if (line.isNotBlank() && !line.matches(Regex("^[0-9a-f]{40}$"))) out += DiffLine(DiffLine.Kind.Meta, line)
                    line.startsWith("+") -> { out += DiffLine(DiffLine.Kind.Added, line.substring(1), newNo = new++); added++ }
                    line.startsWith("-") -> { out += DiffLine(DiffLine.Kind.Removed, line.substring(1), oldNo = old++); removed++ }
                    line.startsWith("\\") -> out += DiffLine(DiffLine.Kind.Meta, line.removePrefix("\\ ").trim())
                    line.startsWith("…") -> out += DiffLine(DiffLine.Kind.Meta, line)
                    line.isEmpty() -> Unit // trailing newline of the output
                    else -> out += DiffLine(DiffLine.Kind.Context, line.removePrefix(" "), oldNo = old++, newNo = new++)
                }
            }
            return ParsedDiff(out, added, removed, notes.distinct())
        }

        /**
         * For a removed line directly replaced by an added one: the changed middle of each (start,
         * end exclusive), after the common prefix and suffix. Null when the lines share too little
         * for highlighting to help.
         */
        fun changedSpan(a: String, b: String): Pair<IntRange, IntRange>? {
            var p = 0
            while (p < a.length && p < b.length && a[p] == b[p]) p++
            var s = 0
            while (s < a.length - p && s < b.length - p && a[a.length - 1 - s] == b[b.length - 1 - s]) s++
            val common = p + s
            if (common < minOf(a.length, b.length) / 3) return null
            return (p until a.length - s) to (p until b.length - s)
        }
    }
}

private data class DiffPalette(
    val bg: Color, val fg: Color, val muted: Color, val gutter: Color,
    val addBg: Color, val addFg: Color, val addWord: Color,
    val delBg: Color, val delFg: Color, val delWord: Color,
    val hunkBg: Color, val hunkFg: Color,
)

@Composable
private fun palette(): DiffPalette = if (ThemePrefs.isDark()) {
    DiffPalette(
        bg = Color(0xFF0D1117), fg = Color(0xFFE6EDF3), muted = Color(0xFF8B949E), gutter = Color(0xFF161B22),
        addBg = Color(0xFF12261E), addFg = Color(0xFFAFF5B4), addWord = Color(0xFF1F6F3A),
        delBg = Color(0xFF2D1214), delFg = Color(0xFFFFDCD7), delWord = Color(0xFF8E1519),
        hunkBg = Color(0xFF121D2F), hunkFg = Color(0xFF79C0FF),
    )
} else {
    DiffPalette(
        bg = Color(0xFFFFFFFF), fg = Color(0xFF1F2328), muted = Color(0xFF6E7781), gutter = Color(0xFFF6F8FA),
        addBg = Color(0xFFE6FFEC), addFg = Color(0xFF1A7F37), addWord = Color(0xFFABF2BC),
        delBg = Color(0xFFFFEBE9), delFg = Color(0xFFCF222E), delWord = Color(0xFFFF8182),
        hunkBg = Color(0xFFDDF4FF), hunkFg = Color(0xFF0969DA),
    )
}

private const val PAGE = 600

/**
 * A diff as a reader expects it: +/− counts, line numbers for both sides, added and removed lines
 * tinted with the changed words highlighted, hunk headers, and a wrap toggle. Long diffs show
 * [PAGE] lines at a time. With [hunkAction], each hunk header starts with that button ("Stage
 * hunk"), calling [onHunk] with the hunk's number.
 */
@Composable
fun DiffViewer(diff: String, modifier: Modifier = Modifier, hunkAction: String? = null, onHunk: (Int) -> Unit = {}) {
    val parsed = remember(diff) { ParsedDiff.parse(diff) }
    // Line index → hunk number, only when the hunks shown are exactly the ones a patch can be made of.
    val hunkOf = remember(parsed, hunkAction != null) {
        val starts = parsed.lines.withIndex().filter { it.value.kind == DiffLine.Kind.Hunk }.map { it.index }
        if (hunkAction == null || HunkPatch.parse(diff)?.hunks?.size != starts.size) emptyMap()
        else starts.withIndex().associate { (n, line) -> line to n }
    }
    val c = palette()
    var wrap by remember { mutableStateOf(false) }
    var shown by remember(diff) { mutableIntStateOf(PAGE) }
    // Word highlights for removed/added pairs, computed once per diff.
    val spans = remember(parsed) {
        val map = HashMap<Int, IntRange>()
        val l = parsed.lines
        var i = 0
        while (i < l.size) {
            if (l[i].kind == DiffLine.Kind.Removed) {
                var j = i
                while (j < l.size && l[j].kind == DiffLine.Kind.Removed) j++
                var k = j
                while (k < l.size && l[k].kind == DiffLine.Kind.Added) k++
                // Pair the block's lines one to one, as they're shown.
                for (n in 0 until minOf(j - i, k - j)) {
                    ParsedDiff.changedSpan(l[i + n].text, l[j + n].text)?.let { (a, b) -> map[i + n] = a; map[j + n] = b }
                }
                i = k
            } else i++
        }
        map
    }
    val numberWidth = remember(parsed) {
        val max = parsed.lines.maxOfOrNull { maxOf(it.oldNo ?: 0, it.newNo ?: 0) } ?: 0
        (max.toString().length.coerceAtLeast(2) * 7 + 6).dp
    }

    Column(modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).background(c.bg)) {
        Row(Modifier.fillMaxWidth().background(c.gutter).padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("+${parsed.added}", color = c.addFg, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            Spacer(Modifier.width(8.dp))
            Text("−${parsed.removed}", color = c.delFg, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            if (parsed.notes.isNotEmpty()) {
                Spacer(Modifier.width(10.dp))
                Text(parsed.notes.joinToString(" · "), color = c.muted, fontSize = 11.sp, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
            }
            Spacer(Modifier.weight(1f))
            Chip(if (wrap) "Wrap: on" else "Wrap: off", onClick = { wrap = !wrap }, selected = wrap)
        }
        if (parsed.isEmpty && parsed.lines.none { it.kind == DiffLine.Kind.Meta }) {
            Text(if (parsed.notes.isEmpty()) "No textual changes" else parsed.notes.joinToString(" · "),
                color = c.muted, fontSize = 12.sp, modifier = Modifier.padding(12.dp))
            return@Column
        }
        val visible = parsed.lines.take(shown)
        fun hunk(index: Int): (() -> Unit)? = hunkOf[index]?.let { n -> { onHunk(n) } }
        if (wrap) {
            Column(Modifier.fillMaxWidth()) {
                visible.forEachIndexed { index, line -> DiffRow(line, spans[index], c, numberWidth, true, hunkAction, hunk(index)) }
            }
        } else {
            // Unwrapped: one horizontal scroll for the whole diff, every row as wide as the longest.
            Box(Modifier.horizontalScroll(rememberScrollState())) {
                Column(Modifier.width(IntrinsicSize.Max)) {
                    visible.forEachIndexed { index, line -> DiffRow(line, spans[index], c, numberWidth, false, hunkAction, hunk(index)) }
                }
            }
        }
        if (parsed.lines.size > shown) {
            TextButton(onClick = { shown += PAGE }, modifier = Modifier.padding(start = 4.dp)) {
                Text("Show ${minOf(PAGE, parsed.lines.size - shown)} more of ${parsed.lines.size - shown} lines")
            }
        }
    }
}

/** A file's diff over the whole screen, for reading it properly (Back or ✕ closes it). */
@Composable
fun DiffFullScreen(path: String, subtitle: String?, diff: String, onDismiss: () -> Unit) {
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .systemBarsPadding(),
        ) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(path.substringAfterLast('/'), style = MaterialTheme.typography.titleMedium, fontFamily = FontFamily.Monospace, maxLines = 1)
                    Text(listOfNotNull(path.substringBeforeLast('/', "").ifEmpty { null }, subtitle).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
                androidx.compose.material3.IconButton(onClick = onDismiss) { dev.yurlungr.app.ui.PhIcon(dev.yurlungr.app.ui.Ph.X, "Close", size = 20.dp) }
            }
            Box(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 8.dp).verticalScroll(rememberScrollState())) {
                DiffViewer(diff)
            }
        }
    }
}

@Composable
private fun DiffRow(
    line: DiffLine, span: IntRange?, c: DiffPalette, numberWidth: androidx.compose.ui.unit.Dp, wrap: Boolean,
    actionLabel: String? = null,
    /** On a hunk header: the hunk's action (stage or unstage it). */
    onAction: (() -> Unit)? = null,
) {
    val (bg, fg, mark, word) = when (line.kind) {
        DiffLine.Kind.Added -> RowStyle(c.addBg, c.addFg, "+", c.addWord)
        DiffLine.Kind.Removed -> RowStyle(c.delBg, c.delFg, "−", c.delWord)
        DiffLine.Kind.Hunk -> RowStyle(c.hunkBg, c.hunkFg, "", Color.Transparent)
        DiffLine.Kind.Meta -> RowStyle(c.bg, c.muted, "", Color.Transparent)
        DiffLine.Kind.Context -> RowStyle(c.bg, c.fg, " ", Color.Transparent)
    }
    val rowMod = if (wrap) Modifier.fillMaxWidth() else Modifier.fillMaxWidth().widthIn(min = 0.dp)
    Row(rowMod.background(bg)) {
        if (line.kind == DiffLine.Kind.Hunk) {
            // First in the row, so it's on screen at the start of the horizontal scroll.
            if (onAction != null && actionLabel != null) {
                TextButton(onClick = onAction, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp),
                    modifier = Modifier.padding(start = 2.dp)) {
                    Text(actionLabel, fontSize = 12.sp, color = fg, fontWeight = FontWeight.SemiBold)
                }
            }
            Text("@@ ${line.text}", color = fg, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 16.sp,
                modifier = Modifier.align(Alignment.CenterVertically).padding(horizontal = 8.dp, vertical = 3.dp))
            return@Row
        }
        Number(line.oldNo, numberWidth, c)
        Number(line.newNo, numberWidth, c)
        Text(mark, color = fg, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 16.sp, modifier = Modifier.width(14.dp).padding(start = 4.dp))
        val text: AnnotatedString = if (span != null && !span.isEmpty() && span.last < line.text.length) buildAnnotatedString {
            append(line.text.substring(0, span.first))
            withStyle(SpanStyle(background = word)) { append(line.text.substring(span.first, span.last + 1)) }
            append(line.text.substring(span.last + 1))
        } else AnnotatedString(line.text.replace("\t", "    "))
        Text(
            text, color = fg, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 16.sp, softWrap = wrap,
            modifier = Modifier.padding(end = 10.dp),
        )
    }
}

@Composable
private fun Number(n: Int?, width: androidx.compose.ui.unit.Dp, c: DiffPalette) {
    Text(
        n?.toString().orEmpty(), color = c.muted, fontFamily = FontFamily.Monospace, fontSize = 10.sp, lineHeight = 16.sp,
        textAlign = TextAlign.End, maxLines = 1,
        modifier = Modifier.width(width).background(c.gutter).padding(end = 4.dp),
    )
}

private data class RowStyle(val bg: Color, val fg: Color, val mark: String, val word: Color)
