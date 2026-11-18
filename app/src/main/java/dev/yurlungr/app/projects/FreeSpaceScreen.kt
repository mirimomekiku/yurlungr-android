package dev.yurlungr.app.projects

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.yurlungr.app.runtime.RuntimePaths
import dev.yurlungr.app.ui.AppColors
import dev.yurlungr.app.ui.AppSheet
import dev.yurlungr.app.ui.Ph
import dev.yurlungr.app.ui.PhIcon
import dev.yurlungr.app.ui.ScreenHeader
import dev.yurlungr.app.ui.SectionLabel
import dev.yurlungr.app.ui.StatTile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Free up space: regenerable folders in each project (node_modules, build output, virtualenvs…) and the
 * shared package caches, measured on the spot, deleted only after a confirmation that shows the total.
 */
@Composable
fun FreeSpaceScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val paths = remember { RuntimePaths(context) }
    val scope = rememberCoroutineScope()
    val fmt = SpaceCleaner::formatBytes
    val colors = MaterialTheme.colorScheme

    val items = remember { mutableStateListOf<SpaceItem>() }
    val selected = remember { mutableStateListOf<String>() }
    var scanTick by remember { mutableIntStateOf(0) }
    var progress by remember { mutableStateOf<String?>("Looking for projects…") }
    var freeBefore by remember { mutableStateOf<Long?>(null) }
    var freeNow by remember { mutableStateOf<Long?>(null) }
    var confirm by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(scanTick) {
        items.clear(); selected.clear()
        progress = "Looking for projects…"
        freeNow = withContext(Dispatchers.IO) { paths.home.usableSpace }
        if (freeBefore == null) freeBefore = freeNow
        val projectsRoot = paths.projects
        val projects = withContext(Dispatchers.IO) {
            projectsRoot.listFiles()?.filter { it.isDirectory && !dev.yurlungr.app.agents.AgentExtensions.isLink(it) }?.sortedBy { it.name.lowercase() }.orEmpty()
        }
        val found = ArrayList<SpaceItem>()
        for ((i, p) in projects.withIndex()) {
            progress = "Scanning ${p.name} (${i + 1} of ${projects.size})…"
            found += withContext(Dispatchers.IO) { runCatching { SpaceCleaner.items(p, SpaceCleaner.find(p)) }.getOrDefault(emptyList()) }
        }
        found += withContext(Dispatchers.IO) { SpaceCleaner.sharedItems(paths.home) }
        for ((i, item) in found.withIndex()) {
            progress = "Measuring ${item.project ?: "caches"} · ${item.title} (${i + 1} of ${found.size})…"
            val measured = withContext(Dispatchers.IO) {
                var total = 0L
                var complete = true
                for (d in item.dirs) {
                    val (b, c) = runCatching { SpaceCleaner.sizeOf(d) }.getOrDefault(0L to false)
                    total += b; complete = complete && c
                }
                item.copy(bytes = total, complete = complete)
            }
            if (measured.bytes > 0) items += measured
        }
        progress = null
    }

    val projectItems = items.filter { it.project != null }.groupBy { it.project!! }.toList().sortedByDescending { (_, list) -> list.sumOf { it.bytes } }
    val caches = items.filter { it.project == null }.sortedByDescending { it.bytes }
    val chosen = items.filter { it.id in selected }
    val total = chosen.sumOf { it.bytes }

    Column(Modifier.fillMaxSize().background(colors.background).systemBarsPadding()) {
        ScreenHeader("Free up space", onBack) {
            IconButton(onClick = { result = null; scanTick++ }, enabled = progress == null && deleting == null) {
                PhIcon(Ph.ArrowClockwise, "Scan again", size = 20.dp)
            }
        }
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatTile("Free now", freeNow?.let(fmt) ?: "…", AppColors.tiles.lime, Modifier.weight(1f), valueSize = 20.sp)
                    StatTile("Can free", if (progress != null && items.isEmpty()) "…" else fmt(items.sumOf { it.bytes }), AppColors.tiles.sky, Modifier.weight(1f), valueSize = 20.sp)
                }
            }
            result?.let { item { Text(it, style = MaterialTheme.typography.bodyMedium, color = AppColors.status.success) } }
            progress?.let { p ->
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(p, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    }
                }
            }
            item {
                Text("Only folders that tools make again are listed. Your source files and .git are never touched.",
                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
            if (progress == null && items.isEmpty()) item { Text("Nothing to clean up right now.", style = MaterialTheme.typography.bodyMedium) }
            for ((project, list) in projectItems) {
                item(key = "p:$project") { SectionLabel("$project · ${fmt(list.sumOf { it.bytes })}") }
                items(list.sortedByDescending { it.bytes }, key = { it.id }) { item ->
                    SpaceRow(item, item.id in selected, enabled = deleting == null) { on -> if (on) selected += item.id else selected -= item.id }
                }
            }
            if (caches.isNotEmpty()) {
                item(key = "caches") { SectionLabel("Shared caches · ${fmt(caches.sumOf { it.bytes })}") }
                items(caches, key = { it.id }) { item ->
                    SpaceRow(item, item.id in selected, enabled = deleting == null) { on -> if (on) selected += item.id else selected -= item.id }
                }
            }
        }
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
            deleting?.let {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(it, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, modifier = Modifier.padding(vertical = 6.dp))
            }
            Button(
                onClick = { confirm = true },
                enabled = chosen.isNotEmpty() && deleting == null && progress == null,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (chosen.isEmpty()) "Select folders to delete" else "Delete ${chosen.size} · ${fmt(total)}") }
        }
    }

    if (confirm) {
        val running = remember(chosen) {
            dev.yurlungr.app.web.WebRunSessions.all().filter { it.state.active }.map { it.root.path }
                .filter { root -> chosen.any { c -> c.dirs.any { it.path.startsWith(root + File.separator) } } }
                .map { File(it).name }
        }
        AppSheet(
            "Delete ${fmt(total)}?",
            onDismiss = { confirm = false },
            subtitle = "${chosen.size} folder${if (chosen.size == 1) "" else "s"}. They come back when you install or build again.",
            icon = Ph.Trash,
            tile = AppColors.tiles.peach,
            actions = {
                OutlinedButton(onClick = { confirm = false }, modifier = Modifier.weight(1f)) { Text("Cancel") }
                Button(onClick = {
                    confirm = false
                    val list = chosen
                    scope.launch {
                        var freed = 0L
                        val failed = ArrayList<String>()
                        for ((i, item) in list.withIndex()) {
                            deleting = "Deleting ${item.title} (${i + 1} of ${list.size})…"
                            val ok = withContext(Dispatchers.IO) {
                                item.dirs.map { SpaceCleaner.delete(it, paths.projects, paths.home) }.all { it.isSuccess }
                            }
                            if (ok) { freed += item.bytes; items.remove(item); selected.remove(item.id) } else failed += item.title
                        }
                        freeNow = withContext(Dispatchers.IO) { paths.home.usableSpace }
                        deleting = null
                        result = "Freed about ${fmt(freed)}. Free space: ${freeBefore?.let(fmt) ?: "?"} before, ${freeNow?.let(fmt) ?: "?"} now." +
                            if (failed.isEmpty()) "" else " Couldn't fully delete: ${failed.take(3).joinToString()}${if (failed.size > 3) "…" else ""}."
                    }
                }, modifier = Modifier.weight(1f)) { Text("Delete") }
            },
        ) {
            for (item in chosen.take(8)) {
                Text("${item.project?.let { "$it · " } ?: ""}${item.title} · ${fmt(item.bytes)}", style = MaterialTheme.typography.bodyMedium)
            }
            if (chosen.size > 8) Text("and ${chosen.size - 8} more", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            if (running.isNotEmpty()) Text("Running now: ${running.joinToString()}. Stop those servers first, or they may break.",
                style = MaterialTheme.typography.bodySmall, color = AppColors.status.warning)
            if (chosen.any { it.caution != null }) Text("Some folders aren't in .gitignore. Make sure they're build output.",
                style = MaterialTheme.typography.bodySmall, color = AppColors.status.warning)
        }
    }
}


@Composable
private fun SpaceRow(item: SpaceItem, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(colors.surfaceContainer)
            .clickable(enabled = enabled) { onChange(!checked) }.padding(start = 4.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked, onChange, enabled = enabled)
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(item.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text((if (item.complete) "" else "≥ ") + SpaceCleaner.formatBytes(item.bytes), style = MaterialTheme.typography.labelLarge)
            }
            Text("${item.what}. ${item.ifDeleted}", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            item.caution?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = AppColors.status.warning) }
        }
    }
}
