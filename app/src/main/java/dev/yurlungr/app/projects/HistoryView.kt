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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
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
import kotlinx.coroutines.launch

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
private val ROW = 64.dp
private const val MAX_LANES = 8

private sealed interface HistoryItem {
    val key: String
    data class Day(val label: String, val lanes: List<Int>, val count: Int) : HistoryItem { override val key get() = "day:$label" }
    data class Entry(val commit: GitCommit, val row: GraphRow) : HistoryItem { override val key get() = commit.hash }
}

/**
 * History as a commit graph: a search bar and a filter sheet (author, date), this branch or all,
 * coloured lanes for branches and merges, day headings with their commit counts, author avatars,
 * branch and tag labels, and a mark on commits not pushed yet. Tap a commit for its details.
 */
@Composable
fun HistoryView(dir: java.io.File, git: Git, status: GitStatus?, refreshKey: Int) {
    var allBranches by rememberSaveable { mutableStateOf(false) }
    var commits by remember { mutableStateOf<List<GitCommit>?>(null) }
    var unpushed by remember { mutableStateOf(emptySet<String>()) }
    var open by remember { mutableStateOf<GitCommit?>(null) }
    // Why the log couldn't be read; distinct from a repository that has no commits.
    var logError by remember { mutableStateOf<String?>(null) }
    var filterAuthor by rememberSaveable(dir.path) { mutableStateOf<String?>(null) }
    var filterPeriod by rememberSaveable(dir.path) { mutableStateOf(HistoryPeriod.Any) }
    var query by rememberSaveable(dir.path) { mutableStateOf("") }
    var showFilters by remember { mutableStateOf(false) }
    // Typing shouldn't run git for every letter.
    var debouncedQuery by remember { mutableStateOf(query) }
    LaunchedEffect(query) { kotlinx.coroutines.delay(350); debouncedQuery = query }
    val filter = HistoryFilter(filterAuthor, filterPeriod, debouncedQuery)
    var authors by remember(dir.path) { mutableStateOf(emptyList<String>()) }
    /** How many commits git has given so far (before the hash filter), and whether there are more. */
    var loaded by remember(dir.path) { mutableStateOf(0) }
    var hasMore by remember(dir.path) { mutableStateOf(false) }
    var loadingMore by remember { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    LaunchedEffect(dir, refreshKey, allBranches, status?.isRepo, status?.unborn, filter) {
        if (status?.isRepo != true || status.unborn) { commits = emptyList(); logError = null; hasMore = false; return@LaunchedEffect }
        // A refresh keeps what "Load more" had shown.
        val count = maxOf(Git.HISTORY_PAGE, loaded)
        git.log(dir, count = count, allBranches = allBranches, filter = filter).fold(
            onSuccess = { list ->
                loaded = list.size
                hasMore = list.size >= count
                var shown = list.filter(filter::matchesHash)
                // A hash older than what's loaded: ask git for that commit directly.
                val hash = filter.hashQuery
                if (hash != null && shown.none { it.hash.startsWith(hash) }) {
                    git.findCommit(dir, hash)?.let { found -> shown = listOf(found) + shown.filter { it.hash != found.hash } }
                }
                commits = shown
                logError = null
            },
            onFailure = { commits = emptyList(); logError = it.message; hasMore = false },
        )
        unpushed = if (status.hasOrigin) git.unpushed(dir) else emptySet()
    }
    fun loadMore() {
        if (loadingMore || !hasMore) return
        loadingMore = true
        scope.launch {
            git.log(dir, allBranches = allBranches, filter = filter, skip = loaded).onSuccess { more ->
                loaded += more.size
                hasMore = more.size >= Git.HISTORY_PAGE
                val known = commits.orEmpty().map { it.hash }.toHashSet()
                commits = commits.orEmpty() + more.filter(filter::matchesHash).filter { it.hash !in known }
            }
            loadingMore = false
        }
    }
    LaunchedEffect(dir, refreshKey, allBranches, status?.isRepo) {
        if (status?.isRepo == true && !status.unborn) authors = git.authors(dir, allBranches)
    }

    // Filtered lists leave out commits the graph would connect through: show them without lanes.
    val items = remember(commits, filter.isActive) {
        commits?.let { c -> buildItems(if (filter.isActive) c.map { it.copy(parents = emptyList()) } else c) }
    }
    val laneCount = remember(items) { items.orEmpty().filterIsInstance<HistoryItem.Entry>().maxOfOrNull { it.row.width }?.coerceIn(1, MAX_LANES) ?: 1 }
    val colors = MaterialTheme.colorScheme

    LazyColumn(contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 16.dp), modifier = Modifier.fillMaxSize()) {
        item(key = "toolbar") {
            Column(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SearchPill(query, { query = it }, "Search messages or a hash", Modifier.weight(1f))
                    val active = (if (filterAuthor != null) 1 else 0) + (if (filterPeriod != HistoryPeriod.Any) 1 else 0)
                    Box(
                        Modifier.size(44.dp).clip(CircleShape)
                            .background(if (active > 0) colors.primaryContainer else colors.surfaceContainer)
                            .clickable { showFilters = true },
                        contentAlignment = Alignment.Center,
                    ) {
                        PhIcon(Ph.ListChecks, "Filters", size = 19.dp, tint = if (active > 0) colors.onPrimaryContainer else colors.onSurface)
                        if (active > 0) {
                            Text("$active", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = colors.onPrimary,
                                modifier = Modifier.align(Alignment.TopEnd).size(16.dp).clip(CircleShape).background(colors.primary)
                                    .wrapContentSize(Alignment.Center))
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Which history: the branch you're on, or every branch.
                    Row(Modifier.clip(RoundedCornerShape(12.dp)).background(colors.surfaceContainer).padding(3.dp)) {
                        for ((label, value) in listOf("This branch" to false, "All branches" to true)) {
                            val on = allBranches == value
                            Text(
                                label, style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (on) colors.onSurface else colors.onSurfaceVariant,
                                modifier = Modifier.clip(RoundedCornerShape(9.dp))
                                    .background(if (on) colors.surfaceContainerHighest else Color.Transparent)
                                    .clickable { allBranches = value }.padding(horizontal = 12.dp, vertical = 6.dp),
                            )
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    commits?.let { c ->
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                "${c.size}${if (hasMore) "+" else ""} ${if (filter.isActive) "matching" else "commits"}",
                                style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant,
                            )
                            if (unpushed.isNotEmpty() && !filter.isActive) {
                                Text("${unpushed.size} not pushed", style = MaterialTheme.typography.labelSmall, color = AppColors.status.warning)
                            }
                        }
                    }
                }
                if (filterAuthor != null || filterPeriod != HistoryPeriod.Any) {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        filterAuthor?.let { a -> RemovableChip("By $a") { filterAuthor = null } }
                        if (filterPeriod != HistoryPeriod.Any) RemovableChip(filterPeriod.label) { filterPeriod = HistoryPeriod.Any }
                    }
                }
            }
        }
        when {
            status == null || items == null -> items(6) { SkeletonListItem() }
            !status.isRepo -> item { EmptyNote(Ph.GitBranch, "Not a git repository", status.error?.message ?: "Initialize git from the Changes tab.") }
            logError != null -> item { EmptyNote(Ph.Warning, "Couldn't read the history", logError.orEmpty()) }
            items.isEmpty() && filter.isActive -> item {
                EmptyNote(Ph.MagnifyingGlass, "No matching commits", "Try another search, or clear the filters.") {
                    TextButton(onClick = { filterAuthor = null; filterPeriod = HistoryPeriod.Any; query = "" }) { Text("Clear filters") }
                }
            }
            items.isEmpty() -> item { EmptyNote(Ph.GitCommit, "No commits yet", "Commit from the Changes tab and it shows up here.") }
            else -> {
                items(items, key = { it.key }) { item ->
                    when (item) {
                        is HistoryItem.Day -> DayHeader(item, laneCount)
                        is HistoryItem.Entry -> CommitRow(item, laneCount, item.commit.hash in unpushed) { open = item.commit }
                    }
                }
                if (hasMore) item(key = "more") { LoadMore(loadingMore, ::loadMore) }
            }
        }
    }

    if (showFilters) {
        FilterSheet(
            authors, filterAuthor, filterPeriod,
            onApply = { a, p -> filterAuthor = a; filterPeriod = p; showFilters = false },
            onDismiss = { showFilters = false },
        )
    }
    open?.let { c -> CommitSheet(dir, git, c, c.hash in unpushed) { open = null } }
}

@Composable
private fun LoadMore(loading: Boolean, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
        if (loading) SkeletonListItem()
        else androidx.compose.material3.FilledTonalButton(onClick = onClick) { Text("Load ${Git.HISTORY_PAGE} more") }
    }
}

/**
 * One file's history (`git log --follow`, so renames don't cut it short), newest first, a page at
 * a time. Tap a commit for its details and this file's diff in it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileHistorySheet(dir: java.io.File, git: Git, path: String, onDismiss: () -> Unit) {
    var commits by remember(path) { mutableStateOf<List<GitCommit>?>(null) }
    var error by remember(path) { mutableStateOf<String?>(null) }
    var hasMore by remember(path) { mutableStateOf(false) }
    var loadingMore by remember { mutableStateOf(false) }
    var open by remember { mutableStateOf<GitCommit?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    LaunchedEffect(path) {
        git.log(dir, path = path).fold(
            onSuccess = { commits = it; hasMore = it.size >= Git.HISTORY_PAGE },
            onFailure = { commits = emptyList(); error = it.message },
        )
    }
    fun loadMore() {
        val shown = commits ?: return
        if (loadingMore) return
        loadingMore = true
        scope.launch {
            git.log(dir, path = path, skip = shown.size).onSuccess { more ->
                hasMore = more.size >= Git.HISTORY_PAGE
                commits = shown + more
            }
            loadingMore = false
        }
    }
    // Without lanes: a file's history skips the commits that don't touch it, so a graph would mislead.
    val items = remember(commits) { commits?.let { c -> buildItems(c.map { it.copy(parents = emptyList()) }) } }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LazyColumn(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp)) {
            item {
                Column(Modifier.padding(start = 4.dp, bottom = 8.dp)) {
                    Text("History of ${path.substringAfterLast('/')}", style = MaterialTheme.typography.titleLarge)
                    Text(
                        listOfNotNull(path.substringBeforeLast('/', "").ifEmpty { null }, commits?.let { "${it.size}${if (hasMore) "+" else ""} commits" })
                            .joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            val list = items
            when {
                list == null -> items(5) { SkeletonListItem() }
                error != null -> item { EmptyNote(Ph.Warning, "Couldn't read the history", error.orEmpty()) }
                list.isEmpty() -> item { EmptyNote(Ph.GitCommit, "No commits yet", "This file hasn't been committed.") }
                else -> {
                    items(list, key = { it.key }) { item ->
                        when (item) {
                            is HistoryItem.Day -> DayHeader(item, 1)
                            is HistoryItem.Entry -> CommitRow(item, 1, false) { open = item.commit }
                        }
                    }
                    if (hasMore) item(key = "more") { LoadMore(loadingMore, ::loadMore) }
                }
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }
    open?.let { c -> CommitSheet(dir, git, c, notPushed = false, focusPath = path) { open = null } }
}

@Composable
private fun SearchPill(value: String, onChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    androidx.compose.foundation.text.BasicTextField(
        value, onChange,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.onSurface),
        cursorBrush = androidx.compose.ui.graphics.SolidColor(colors.primary),
        modifier = modifier,
        decorationBox = { field ->
            Row(
                Modifier.height(44.dp).clip(RoundedCornerShape(22.dp)).background(colors.surfaceContainer).padding(start = 14.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PhIcon(Ph.MagnifyingGlass, null, size = 17.dp, tint = colors.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty()) Text(placeholder, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, maxLines = 1)
                    field()
                }
                if (value.isNotEmpty()) {
                    androidx.compose.material3.IconButton(onClick = { onChange("") }) { PhIcon(Ph.XCircle, "Clear search", size = 18.dp, tint = colors.onSurfaceVariant) }
                } else Spacer(Modifier.width(10.dp))
            }
        },
    )
}

@Composable
private fun RemovableChip(label: String, onRemove: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.height(30.dp).clip(CircleShape).background(colors.secondaryContainer).clickable(onClick = onRemove).padding(start = 12.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = colors.onSecondaryContainer, maxLines = 1)
        Spacer(Modifier.width(4.dp))
        PhIcon(Ph.XCircle, "Remove", size = 14.dp, tint = colors.onSecondaryContainer)
    }
}

@Composable
private fun EmptyNote(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, text: String, action: (@Composable () -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val lilac = AppColors.tiles.lilac
        Box(Modifier.size(56.dp).clip(RoundedCornerShape(20.dp)).background(lilac.fill), contentAlignment = Alignment.Center) {
            PhIcon(icon, null, size = 26.dp, tint = lilac.on)
        }
        Text(title, style = MaterialTheme.typography.titleMedium)
        if (text.isNotBlank()) Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        action?.invoke()
    }
}

/** Author and date, chosen together and applied with Done. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun FilterSheet(authors: List<String>, author: String?, period: HistoryPeriod, onApply: (String?, HistoryPeriod) -> Unit, onDismiss: () -> Unit) {
    var a by remember { mutableStateOf(author) }
    var p by remember { mutableStateOf(period) }
    val colors = MaterialTheme.colorScheme
    dev.yurlungr.app.ui.AppSheet(
        "Filter history", onDismiss = onDismiss, subtitle = "By author and date. Search works with them.", icon = Ph.ListChecks,
        actions = {
            androidx.compose.material3.OutlinedButton(onClick = { a = null; p = HistoryPeriod.Any }, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) { Text("Reset") }
            androidx.compose.material3.Button(onClick = { onApply(a, p) }, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) { Text("Show commits") }
        },
    ) {
        Text("When", style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (option in HistoryPeriod.entries) Chip(option.label, selected = p == option, onClick = { p = option })
        }
        Text("Author", style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
        for (name in listOf<String?>(null) + authors.take(30)) {
            val on = a == name
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (on) colors.secondaryContainer else Color.Transparent)
                    .clickable { a = name }.padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (name == null) Box(Modifier.size(28.dp).clip(CircleShape).background(colors.surfaceContainerHighest), contentAlignment = Alignment.Center) {
                    PhIcon(Ph.User, null, size = 15.dp, tint = colors.onSurfaceVariant)
                } else Avatar(name, 28.dp)
                Spacer(Modifier.width(10.dp))
                Text(name ?: "Anyone", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (on) PhIcon(Ph.CheckCircle, null, size = 18.dp, tint = colors.primary)
            }
        }
    }
}

/** A round badge with the author's initials, coloured by name so each person stays recognisable. */
@Composable
private fun Avatar(name: String, size: Dp) {
    val t = AppColors.tiles
    val tile = listOf(t.lime, t.lilac, t.peach, t.sky)[(name.lowercase().hashCode() and 0x7fffffff) % 4]
    val initials = name.split(' ', '.', '_', '-').filter { it.isNotBlank() }.take(2).joinToString("") { it.take(1).uppercase() }.ifEmpty { "?" }
    Box(Modifier.size(size).clip(CircleShape).background(tile.fill), contentAlignment = Alignment.Center) {
        Text(initials, color = tile.on, fontSize = (size.value * 0.38f).sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

private fun buildItems(commits: List<GitCommit>): List<HistoryItem> {
    val rows = GitGraph.layout(commits)
    val perDay = commits.groupingBy { dayLabel(it.time) }.eachCount()
    val out = ArrayList<HistoryItem>()
    var lastDay: String? = null
    var active = emptyList<Int>() // lanes alive between the previous row and this one
    commits.forEachIndexed { i, c ->
        val day = dayLabel(c.time)
        if (day != lastDay) {
            out += HistoryItem.Day(day, active, perDay[day] ?: 0)
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
    Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
        val palette = lanePalette()
        Canvas(Modifier.width(graphWidth(laneCount)).fillMaxHeight()) {
            for (l in day.lanes) if (l < MAX_LANES) {
                val x = laneX(l)
                drawLine(palette.lane(l), Offset(x, 0f), Offset(x, size.height), strokeWidth = 2.dp.toPx())
            }
        }
        Text(day.label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainer).padding(horizontal = 10.dp, vertical = 3.dp))
        Spacer(Modifier.width(8.dp))
        Text("${day.count} commit${if (day.count == 1) "" else "s"}", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun graphWidth(lanes: Int): Dp = LANE * lanes + 14.dp
private fun DrawScope.laneX(lane: Int) = (LANE.toPx() * lane) + LANE.toPx() / 2 + 4.dp.toPx()

@Composable
private fun CommitRow(entry: HistoryItem.Entry, laneCount: Int, notPushed: Boolean, onClick: () -> Unit) {
    val c = entry.commit
    val r = entry.row
    val colors = MaterialTheme.colorScheme
    val bg = colors.background
    Row(
        Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .heightIn(min = ROW)
            .clip(RoundedCornerShape(16.dp))
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
        Column(Modifier.weight(1f).padding(vertical = 8.dp, horizontal = 4.dp)) {
            if (c.refs.isNotEmpty()) {
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (ref in c.refs) RefChip(ref)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(c.author, 30.dp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(c.subject, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${c.author} · ${timeLabel(LocalContext.current, c.time)}" + if (c.parents.size > 1) " · merge" else "",
                        style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Column(horizontalAlignment = Alignment.End) {
                    Text(c.short, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = colors.onSurfaceVariant)
                    if (notPushed) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            PhIcon(Ph.CloudArrowUp, null, size = 12.dp, tint = AppColors.status.warning)
                            Spacer(Modifier.width(3.dp))
                            Text("to push", style = MaterialTheme.typography.labelSmall, color = AppColors.status.warning)
                        }
                    }
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
private fun CommitSheet(
    dir: java.io.File, git: Git, c: GitCommit, notPushed: Boolean,
    /** From a file's history: that file's diff opens straight away. */
    focusPath: String? = null,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var body by remember { mutableStateOf<String?>(null) }
    var files by remember { mutableStateOf<List<CommitFile>?>(null) }
    var filesError by remember { mutableStateOf<String?>(null) }
    val diffs = remember { mutableStateMapOf<String, String?>() } // path -> diff (null = loading)
    var openFile by remember { mutableStateOf(focusPath) }
    var fullScreen by remember { mutableStateOf<String?>(null) }
    fullScreen?.let { path ->
        diffs[path]?.let { d -> DiffFullScreen(path, "${c.short} · ${c.subject}", d) { fullScreen = null } }
    }
    LaunchedEffect(c.hash) {
        body = git.commitBody(dir, c.hash)
        files = git.commitFiles(dir, c.hash).getOrElse { filesError = it.message; emptyList() }
        // Followed through a rename: in older commits the file had another name.
        if (focusPath != null && files?.none { it.path == focusPath } == true) openFile = files?.singleOrNull()?.path
    }
    LaunchedEffect(openFile) {
        val p = openFile ?: return@LaunchedEffect
        if (diffs[p] == null) diffs[p] = git.commitDiff(dir, c.hash, p)
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LazyColumn(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp)) {
            item {
                val colors = MaterialTheme.colorScheme
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Avatar(c.author, 44.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(c.author, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(fullTime(context, c.time), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                        }
                        if (notPushed) {
                            Row(
                                Modifier.clip(CircleShape).background(AppColors.status.warningContainer).padding(horizontal = 10.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                PhIcon(Ph.CloudArrowUp, null, size = 13.dp, tint = AppColors.status.onWarningContainer)
                                Spacer(Modifier.width(4.dp))
                                Text("Not pushed", style = MaterialTheme.typography.labelSmall, color = AppColors.status.onWarningContainer)
                            }
                        }
                    }
                    if (c.refs.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) { for (ref in c.refs) RefChip(ref) }
                    Text(c.subject, style = MaterialTheme.typography.titleLarge)
                    body?.takeIf { it.isNotBlank() }?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(colors.surfaceContainer).padding(12.dp))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        // The hash: tap to copy.
                        Row(
                            Modifier.clip(CircleShape).background(colors.surfaceContainer).clickable { copy(context, c.hash) }
                                .padding(horizontal = 12.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            PhIcon(Ph.GitCommit, null, size = 14.dp, tint = colors.onSurfaceVariant)
                            Spacer(Modifier.width(6.dp))
                            Text(c.hash.take(12), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                            Spacer(Modifier.width(6.dp))
                            PhIcon(Ph.Copy, "Copy hash", size = 13.dp, tint = colors.onSurfaceVariant)
                        }
                        TextButton(onClick = { copy(context, listOfNotNull(c.subject, body?.takeIf { it.isNotBlank() }).joinToString("\n\n")) }) { Text("Copy message") }
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
            else if (filesError != null) item { Note("Couldn't list the files: $filesError") }
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
                        Box(Modifier.size(22.dp).clip(RoundedCornerShape(7.dp)).background(color.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                            Text(letter, fontWeight = FontWeight.Bold, color = color, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(f.path.substringAfterLast('/'), fontFamily = FontFamily.Monospace, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            f.path.substringBeforeLast('/', "").takeIf { it.isNotEmpty() }?.let {
                                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        PhIcon(if (openFile == f.path) Ph.CaretDown else Ph.CaretRight, null, size = 12.dp, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (openFile == f.path) {
                        Column(Modifier.padding(start = 8.dp, end = 8.dp, bottom = 8.dp)) {
                            val d = diffs[f.path]
                            if (d == null) SkeletonCode(lines = 6) else {
                                UnifiedDiff(d)
                                androidx.compose.material3.TextButton(onClick = { fullScreen = f.path }) {
                                    PhIcon(Ph.ArrowsLeftRight, null, size = 14.dp)
                                    Spacer(Modifier.width(6.dp))
                                    Text("Full screen")
                                }
                            }
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
