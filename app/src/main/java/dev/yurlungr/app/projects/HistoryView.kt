package dev.yurlungr.app.projects

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.text.format.DateFormat
import android.widget.Toast
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.yurlungr.app.ui.AppColors
import dev.yurlungr.app.ui.Chip
import dev.yurlungr.app.ui.Ph
import dev.yurlungr.app.ui.PhIcon
import dev.yurlungr.app.ui.SkeletonCode
import dev.yurlungr.app.ui.SkeletonListItem
import java.util.Calendar
import java.util.Date

/** Lane colors from the Bento tiles; the first (main) lane is lime in dark, ink in light. */
@Composable
private fun lanePalette(): List<Color> {
    val t = AppColors.tiles
    return if (isSystemInDarkTheme()) {
        listOf(t.lime.fill, t.lilac.fill, t.peach.fill, t.sky.fill, Color(0xFFF472B6), Color(0xFF7EE0B5))
    } else {
        listOf(Color(0xFF0F172A), Color(0xFF7C6AE6), Color(0xFFE07B39), Color(0xFF2B8AC8), Color(0xFFDB2777), Color(0xFF0F9F6E))
    }
}

private fun List<Color>.lane(i: Int) = this[i % size]

private val LANE = 14.dp
private val ROW = 58.dp
private const val MAX_LANES = 8

private sealed interface HistoryItem {
    val key: String
    data class Day(val label: String, val lanes: List<Int>) : HistoryItem { override val key get() = "day:$label" }
    data class Entry(val commit: GitCommit, val row: GraphRow) : HistoryItem { override val key get() = commit.hash }
}

/**
 * History as a commit graph: colored lanes for branches and merges, grouped under day headings
 * (Today, Yesterday, weekday, date), branch and tag labels on their commits, and a "not pushed"
 * mark on local-only commits. Tap a commit for its message, files and diffs.
 */
@Composable
fun HistoryView(dir: java.io.File, git: Git, status: GitStatus?, refreshKey: Int) {
    var allBranches by rememberSaveable { mutableStateOf(false) }
    var commits by remember { mutableStateOf<List<GitCommit>?>(null) }
    var unpushed by remember { mutableStateOf(emptySet<String>()) }
    var open by remember { mutableStateOf<GitCommit?>(null) }

    LaunchedEffect(dir, refreshKey, allBranches, status?.isRepo, status?.unborn) {
        if (status?.isRepo != true || status.unborn) { commits = emptyList(); return@LaunchedEffect }
        commits = git.log(dir, allBranches = allBranches)
        unpushed = if (status.hasOrigin) git.unpushed(dir) else emptySet()
    }

    val items = remember(commits) { commits?.let(::buildItems) }
    val laneCount = remember(items) { items.orEmpty().filterIsInstance<HistoryItem.Entry>().maxOfOrNull { it.row.width }?.coerceIn(1, MAX_LANES) ?: 1 }

    LazyColumn(contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 16.dp), modifier = Modifier.fillMaxSize()) {
        item(key = "filters") {
            Row(Modifier.fillMaxWidth().padding(start = 4.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("This branch", selected = !allBranches, onClick = { allBranches = false })
                Chip("All branches", selected = allBranches, onClick = { allBranches = true })
                Spacer(Modifier.weight(1f))
                commits?.let { c ->
                    Text(
                        "${c.size}${if (c.size >= 300) "+" else ""} commits" + if (unpushed.isNotEmpty()) " · ${unpushed.size} not pushed" else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        when {
            status == null || items == null -> items(6) { SkeletonListItem() }
            !status.isRepo -> item { Note("Not a git repository.") }
            items.isEmpty() -> item { Note("No commits yet. Commit from the Changes tab.") }
            else -> items(items, key = { it.key }) { item ->
                when (item) {
                    is HistoryItem.Day -> DayHeader(item, laneCount)
                    is HistoryItem.Entry -> CommitRow(item, laneCount, item.commit.hash in unpushed) { open = item.commit }
                }
            }
        }
    }

    open?.let { c -> CommitSheet(dir, git, c, c.hash in unpushed) { open = null } }
}

private fun buildItems(commits: List<GitCommit>): List<HistoryItem> {
    val rows = GitGraph.layout(commits)
    val out = ArrayList<HistoryItem>()
    var lastDay: String? = null
    var active = emptyList<Int>() // lanes alive between the previous row and this one
    commits.forEachIndexed { i, c ->
        val day = dayLabel(c.time)
        if (day != lastDay) {
            out += HistoryItem.Day(day, active)
            lastDay = day
        }
        out += HistoryItem.Entry(c, rows[i])
        active = (rows[i].through + rows[i].outgoing + rows[i].shifts.map { it.second }).distinct()
    }
    return out
}

@Composable
private fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(8.dp))
}

@Composable
private fun DayHeader(day: HistoryItem.Day, laneCount: Int) {
    Row(Modifier.fillMaxWidth().height(34.dp), verticalAlignment = Alignment.CenterVertically) {
        val palette = lanePalette()
        Canvas(Modifier.width(graphWidth(laneCount)).fillMaxHeight()) {
            for (l in day.lanes) if (l < MAX_LANES) {
                val x = laneX(l)
                drawLine(palette.lane(l), Offset(x, 0f), Offset(x, size.height), strokeWidth = 2.dp.toPx())
            }
        }
        Text(day.label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun graphWidth(lanes: Int): Dp = LANE * lanes + 14.dp
private fun DrawScope.laneX(lane: Int) = (LANE.toPx() * lane) + LANE.toPx() / 2 + 4.dp.toPx()

@Composable
private fun CommitRow(entry: HistoryItem.Entry, laneCount: Int, notPushed: Boolean, onClick: () -> Unit) {
    val c = entry.commit
    val r = entry.row
    val bg = MaterialTheme.colorScheme.background
    Row(
        Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .heightIn(min = ROW)
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Stretches with the row (branch labels make it taller); the dot stays level with the title.
        val palette = lanePalette()
        Canvas(Modifier.width(graphWidth(laneCount)).fillMaxHeight()) {
            val mid = size.height - ROW.toPx() / 2
            val w = 2.dp.toPx()
            val node = Offset(laneX(minOf(r.lane, MAX_LANES - 1)), mid)
            for (l in r.through) if (l < MAX_LANES) {
                drawLine(palette.lane(l), Offset(laneX(l), 0f), Offset(laneX(l), size.height), strokeWidth = w)
            }
            for (l in r.incoming) if (l < MAX_LANES) curve(palette.lane(l), Offset(laneX(l), 0f), node, w)
            for ((from, to) in r.shifts) if (from < MAX_LANES && to < MAX_LANES) {
                curve(palette.lane(from), Offset(laneX(from), 0f), Offset(laneX(to), size.height), w)
            }
            for (l in r.outgoing) if (l < MAX_LANES) curve(palette.lane(l), node, Offset(laneX(l), size.height), w)
            val color = palette.lane(r.lane)
            val radius = if (c.parents.size > 1) 4.5.dp.toPx() else 5.5.dp.toPx()
            if (notPushed) {
                drawCircle(bg, radius, node)
                drawCircle(color, radius, node, style = Stroke(2.dp.toPx()))
            } else {
                drawCircle(color, radius, node)
            }
        }
        Column(Modifier.weight(1f).padding(vertical = 6.dp, horizontal = 4.dp)) {
            if (c.refs.isNotEmpty()) {
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 3.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (ref in c.refs) RefChip(ref)
                }
            }
            Text(c.subject, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${c.short} · ${c.author} · ${timeLabel(LocalContext.current, c.time)}" + if (c.parents.size > 1) " · merge" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (notPushed) {
                    Spacer(Modifier.width(6.dp))
                    Text("not pushed", style = MaterialTheme.typography.labelSmall, color = AppColors.status.warning)
                }
            }
        }
    }
}

/** A smooth S-curve between lanes (straight when they're the same). */
private fun DrawScope.curve(color: Color, from: Offset, to: Offset, width: Float) {
    if (from.x == to.x) {
        drawLine(color, from, to, strokeWidth = width)
        return
    }
    val midY = (from.y + to.y) / 2
    val path = Path().apply {
        moveTo(from.x, from.y)
        cubicTo(from.x, midY, to.x, midY, to.x, to.y)
    }
    drawPath(path, color, style = Stroke(width, cap = StrokeCap.Round))
}

@Composable
private fun RefChip(ref: String) {
    val colors = MaterialTheme.colorScheme
    val (label, bg, fg, outline) = when {
        ref.startsWith("HEAD -> ") -> Quad(ref.removePrefix("HEAD -> "), colors.primary, colors.onPrimary, false)
        ref == "HEAD" -> Quad("HEAD", colors.primary, colors.onPrimary, false)
        ref.startsWith("tag: ") -> Quad("🏷 " + ref.removePrefix("tag: "), AppColors.status.warningContainer, AppColors.status.onWarningContainer, false)
        ref.contains('/') -> Quad(ref, Color.Transparent, colors.onSurfaceVariant, true)
        else -> Quad(ref, colors.secondaryContainer, colors.onSecondaryContainer, false)
    }
    Text(
        label,
        fontSize = 10.5.sp,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.SemiBold,
        color = fg,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .then(if (outline) Modifier.border(1.dp, colors.outlineVariant, RoundedCornerShape(6.dp)) else Modifier)
            .padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

private data class Quad(val a: String, val b: Color, val c: Color, val d: Boolean)

// ---- Commit details ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CommitSheet(dir: java.io.File, git: Git, c: GitCommit, notPushed: Boolean, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var body by remember { mutableStateOf<String?>(null) }
    var files by remember { mutableStateOf<List<CommitFile>?>(null) }
    val diffs = remember { mutableStateMapOf<String, String?>() } // path -> diff (null = loading)
    var openFile by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(c.hash) {
        body = git.commitBody(dir, c.hash)
        files = git.commitFiles(dir, c.hash)
    }
    LaunchedEffect(openFile) {
        val p = openFile ?: return@LaunchedEffect
        if (diffs[p] == null) diffs[p] = git.commitDiff(dir, c.hash, p)
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LazyColumn(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp)) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (c.refs.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { for (ref in c.refs) RefChip(ref) }
                    Text(c.subject, style = MaterialTheme.typography.titleMedium)
                    body?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    Text(
                        "${c.author} · ${fullTime(context, c.time)}" + if (notPushed) " · not pushed" else "",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(c.hash, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        TextButton(onClick = { copy(context, c.hash) }) { Text("Copy hash") }
                    }
                    if (c.parents.size > 1) {
                        Text("Merge of ${c.parents.joinToString(" + ") { it.take(7) }} · files shown against the first parent", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            item {
                Text(
                    files?.let { "${it.size} file" + (if (it.size == 1) "" else "s") + " changed" } ?: "Files",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 14.dp, bottom = 6.dp),
                )
            }
            val list = files
            if (list == null) items(3) { SkeletonListItem() }
            else items(list, key = { it.path }) { f ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainer)
                        .animateContentSize()
                        .clickable { openFile = if (openFile == f.path) null else f.path },
                ) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        val (letter, color) = when (f.status) {
                            'A' -> "A" to AppColors.status.success
                            'D' -> "D" to MaterialTheme.colorScheme.error
                            'R' -> "R" to MaterialTheme.colorScheme.primary
                            else -> f.status.toString() to AppColors.status.warning
                        }
                        Box(Modifier.width(22.dp)) { Text(letter, fontWeight = FontWeight.Bold, color = color, fontFamily = FontFamily.Monospace) }
                        Text(f.path, fontFamily = FontFamily.Monospace, fontSize = 13.sp, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        PhIcon(if (openFile == f.path) Ph.CaretDown else Ph.CaretRight, null, size = 12.dp, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (openFile == f.path) {
                        Box(Modifier.padding(start = 8.dp, end = 8.dp, bottom = 8.dp)) {
                            val d = diffs[f.path]
                            if (d == null) SkeletonCode(lines = 6) else UnifiedDiff(d)
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(20.dp)) }
        }
    }
}

// ---- Time --------------------------------------------------------------------------------------

private fun startOfDay(millis: Long): Long = Calendar.getInstance().apply {
    timeInMillis = millis
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis

/** "Today", "Yesterday", a weekday within the last week, else "Mon, Nov 3" (with the year if not this year). */
internal fun dayLabel(millis: Long, now: Long = System.currentTimeMillis()): String {
    val days = ((startOfDay(now) - startOfDay(millis)) / 86_400_000L).toInt()
    val date = Date(millis)
    val sameYear = Calendar.getInstance().apply { timeInMillis = now }.get(Calendar.YEAR) ==
        Calendar.getInstance().apply { timeInMillis = millis }.get(Calendar.YEAR)
    return when {
        days <= 0 -> "Today"
        days == 1 -> "Yesterday"
        days < 7 -> java.text.SimpleDateFormat("EEEE", java.util.Locale.getDefault()).format(date)
        sameYear -> java.text.SimpleDateFormat("EEE, MMM d", java.util.Locale.getDefault()).format(date)
        else -> java.text.SimpleDateFormat("MMM d, yyyy", java.util.Locale.getDefault()).format(date)
    }
}

/** Within the hour: "just now" / "12 min ago"; otherwise the clock time (the day is in the heading). */
private fun timeLabel(context: Context, millis: Long, now: Long = System.currentTimeMillis()): String {
    val mins = (now - millis) / 60_000
    return when {
        mins < 1 -> "just now"
        mins < 60 -> "$mins min ago"
        else -> DateFormat.getTimeFormat(context).format(Date(millis))
    }
}

private fun fullTime(context: Context, millis: Long): String =
    dayLabel(millis) + ", " + DateFormat.getTimeFormat(context).format(Date(millis))

private fun copy(context: Context, text: String) {
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("commit", text))
    Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
}
