package dev.yurlungr.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.yurlungr.app.chat.ChatSessions
import dev.yurlungr.app.editor.RecentFiles
import dev.yurlungr.app.projects.LastProject
import dev.yurlungr.app.projects.ProjectTasks
import dev.yurlungr.app.projects.StackDetector
import dev.yurlungr.app.runtime.CommandRunner
import dev.yurlungr.app.runtime.RuntimePaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Opens the command palette from anywhere (long-press ☰ / ‹, or the sidebar). */
val LocalOpenPalette = compositionLocalOf<(() -> Unit)?> { null }

/** Something the palette can do. */
class PaletteItem(
    val group: String,
    val title: String,
    val detail: String? = null,
    val icon: ImageVector,
    val run: () -> Unit,
)

object Palette {
    /**
     * How well [query] matches [text]: contains beats a scattered match of the letters in order
     * ("prjscr" → "ProjectScreen"); 0 is no match.
     */
    fun score(query: String, text: String): Int {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return 1
        val t = text.lowercase()
        val at = t.indexOf(q)
        if (at >= 0) return 1000 - at - (t.length - q.length).coerceAtMost(200) / 4 + if (at == 0 || !t[at - 1].isLetterOrDigit()) 300 else 0
        var i = 0
        var gaps = 0
        for (c in t) {
            if (i < q.length && c == q[i]) i++ else if (i > 0) gaps++
        }
        return if (i == q.length) (400 - gaps).coerceAtLeast(1) else 0
    }
}

/**
 * Search everything: screens and actions, projects, recently opened files, chats, and the last
 * project's scripts and files. Typing filters; Enter runs the top result.
 */
@Composable
fun CommandPalette(
    actions: List<PaletteItem>,
    onOpenProject: (File) -> Unit,
    onOpenFile: (File) -> Unit,
    onOpenChat: (String) -> Unit,
    runInTerminal: (String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val paths = remember { RuntimePaths(context) }
    var query by remember { mutableStateOf("") }
    val project = remember { LastProject.get(context) }
    // Slower sources, loaded once when the palette opens.
    val sources by produceState<Pair<List<PaletteItem>, List<String>>>(emptyList<PaletteItem>() to emptyList(), project) {
        value = withContext(Dispatchers.IO) {
            val projects = paths.projects.listFiles { f -> f.isDirectory && !f.name.startsWith(".") }.orEmpty()
                .sortedByDescending { it.lastModified() }
                .map { p -> PaletteItem("Projects", p.name, null, Ph.FolderSimple) { onOpenProject(p) } }
            val recent = RecentFiles.list(context).map { f ->
                PaletteItem("Recent files", f.name, f.parentFile?.path?.replace(paths.home.path, "~"), Ph.FileText) { onOpenFile(f) }
            }
            val tasks = project?.let { p ->
                runCatching { ProjectTasks.detect(p, StackDetector.detect(p)) }.getOrDefault(emptyList()).map { t ->
                    PaletteItem("Scripts · ${p.name}", t.name, t.command, Ph.Play) {
                        runInTerminal("cd ${dev.yurlungr.app.projects.Git.shellQuote(t.dir.path)} && ${t.command}", "${t.name} · ${p.name}")
                    }
                }
            }.orEmpty()
            // The project's files as git and ripgrep see them (ignored ones left out).
            val files = project?.let { p ->
                File(paths.bin, "rg").takeIf { it.canExecute() }?.let { rg ->
                    CommandRunner.run(paths, listOf(rg.path, "--files", "--hidden", "--glob", "!.git"), cwd = p, timeoutMs = 10_000, forParsing = true)
                        .output.lineSequence().filter { it.isNotBlank() }.take(20_000).toList()
                }
            }.orEmpty()
            (projects + recent + tasks) to files
        }
    }
    val chats = ChatSessions.chats.map { c -> PaletteItem("Chats", c.title, c.agent.displayName, Ph.ChatCircle) { onOpenChat(c.id) } }
    val shown = remember(query, sources, chats.size) {
        val (static, files) = sources
        val all = actions + static + chats
        val scored = all.map { it to Palette.score(query, it.title + " " + it.group) }.filter { it.second > 0 }
        val fileHits = if (query.isBlank() || project == null) emptyList() else files.asSequence()
            .map { it to Palette.score(query, it) }.filter { it.second > 0 }.sortedByDescending { it.second }.take(25)
            .map { (path, s) -> PaletteItem("Files · ${project.name}", path.substringAfterLast('/'), path, Ph.FileText) { onOpenFile(File(project, path)) } to s }
            .toList()
        val ranked = if (query.isBlank()) scored else (scored + fileHits).sortedByDescending { it.second }
        ranked.map { it.first }.take(80)
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val colors = MaterialTheme.colorScheme
    AppSheet("Search everything", onDismiss = onDismiss, icon = Ph.MagnifyingGlass, tile = AppColors.tiles.sky) {
        OutlinedTextField(
            query, { query = it },
            placeholder = { Text("Screens, projects, files, chats, scripts") },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { shown.firstOrNull()?.let { onDismiss(); it.run() } }),
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
        )
        if (shown.isEmpty()) Text("Nothing matches “$query”.", color = colors.onSurfaceVariant)
        val grouped = if (query.isBlank()) shown.groupBy { it.group }.entries.toList() else listOf(java.util.AbstractMap.SimpleEntry("Best matches", shown))
        for ((group, items) in grouped) {
            Text(group, style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            for (item in items.take(if (query.isBlank()) 6 else 80)) {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { onDismiss(); item.run() }.padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(32.dp).clip(RoundedCornerShape(10.dp)).background(colors.surfaceContainerHigh), contentAlignment = Alignment.Center) {
                        PhIcon(item.icon, null, size = 16.dp, tint = colors.onSurfaceVariant)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(item.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val detail = item.detail ?: if (query.isNotBlank()) item.group else null
                        detail?.let { Text(it, fontFamily = if (item.icon == Ph.Play || item.icon == Ph.FileText) FontFamily.Monospace else null,
                            fontSize = 11.sp, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                }
            }
        }
        Spacer(Modifier.size(4.dp))
        Text("Tip: long-press ☰ or ‹ on any screen to open this.", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp))
    }
}
