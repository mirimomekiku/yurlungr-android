package dev.yurlungr.app.projects

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.yurlungr.app.runtime.CommandRunner
import dev.yurlungr.app.runtime.RuntimePaths
import dev.yurlungr.app.ui.Chip
import dev.yurlungr.app.ui.Ph
import dev.yurlungr.app.ui.ScreenHeader
import dev.yurlungr.app.ui.WaveDots
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Opens Find code across projects, optionally limited to one project (by folder name). Null outside the app shell. */
val LocalFindCode = compositionLocalOf<((project: String?) -> Unit)?> { null }

sealed interface CodeSearchEvent {
    data class Found(val matches: List<CodeMatch>) : CodeSearchEvent
    data class Finished(val error: String? = null, val capped: Boolean = false, val builtIn: Boolean = false) : CodeSearchEvent
}

/** Runs a search, streaming matches in batches; cancelling the collector stops ripgrep. */
fun codeSearch(paths: RuntimePaths, q: CodeQuery): Flow<CodeSearchEvent> = channelFlow {
    val roots = paths.projects.listFiles { f -> f.isDirectory && !f.name.startsWith(".") }.orEmpty()
        .filter { q.project == null || it.name == q.project }.sortedBy { it.name.lowercase() }
    if (roots.isEmpty()) { send(CodeSearchEvent.Finished()); return@channelFlow }
    val rg = File(paths.bin, "rg")
    if (!rg.canExecute()) {
        // Plain file reads: slower, and .gitignore isn't read, but the same folders are skipped.
        val matcher = runCatching { CodeMatcher(q) }
        if (matcher.isFailure) { send(CodeSearchEvent.Finished(error = matcher.exceptionOrNull()?.message?.lineSequence()?.firstOrNull(), builtIn = true)); return@channelFlow }
        var count = 0
        val buf = ArrayList<CodeMatch>()
        var last = System.currentTimeMillis()
        CodeWalker.search(roots, q, isActive = { isActive }) { m ->
            buf += m; count++
            if (buf.size >= 50 || System.currentTimeMillis() - last > 150) {
                trySendBlocking(CodeSearchEvent.Found(buf.toList())); buf.clear(); last = System.currentTimeMillis()
            }
            true
        }
        if (buf.isNotEmpty()) send(CodeSearchEvent.Found(buf.toList()))
        send(CodeSearchEvent.Finished(capped = count >= CodeSearch.MAX_MATCHES, builtIn = true))
        return@channelFlow
    }

    val lines = Channel<String>(Channel.UNLIMITED)
    var result: dev.yurlungr.app.runtime.CommandResult? = null
    val proc = launch {
        try {
            result = CommandRunner.run(paths, CodeSearch.rgArgs(rg.path, q, roots.map { it.name }), cwd = paths.projects,
                timeoutMs = 120_000, onLine = { lines.trySend(it) })
        } finally {
            lines.close()
        }
    }
    val parser = RgJsonParser()
    var count = 0
    var capped = false
    val buf = ArrayList<CodeMatch>()
    fun take(ms: List<CodeMatch>) {
        for (m in ms) {
            if (count >= CodeSearch.MAX_MATCHES) { capped = true; return }
            buf += m; count++
        }
    }
    for (line in lines) {
        take(parser.feed(line))
        // Everything that's already arrived goes in one batch, so the list doesn't redraw per line.
        while (!capped) take(parser.feed(lines.tryReceive().getOrNull() ?: break))
        if (buf.isNotEmpty()) { send(CodeSearchEvent.Found(buf.toList())); buf.clear() }
        if (capped) { proc.cancel(); break }
    }
    if (!capped) take(parser.finish())
    if (buf.isNotEmpty()) send(CodeSearchEvent.Found(buf.toList()))
    proc.join()
    val r = result
    // rg exits 1 when nothing matched; 2 on an error (also for one unreadable file, so only shown with no results).
    val error = when {
        capped || r == null -> null
        r.timedOut -> "Stopped after 2 minutes. Try one project or a file filter."
        r.exitCode == 2 && count == 0 -> parser.errors.take(4).joinToString("\n").ifBlank { r.describe("Searching") }
        r.launchError != null -> r.describe("Searching")
        else -> null
    }
    send(CodeSearchEvent.Finished(error = error, capped = capped))
}.flowOn(Dispatchers.IO)

/**
 * Find code across every project: plain text or a regular expression, case, a file filter and one
 * project or all. Matches stream in grouped by project and file; tap one to open it at that line.
 */
@Composable
fun CodeSearchScreen(initialProject: String?, initialQuery: String, onBack: () -> Unit, onOpenFile: (File, Int) -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val paths = remember { RuntimePaths(context) }
    val colors = MaterialTheme.colorScheme
    var text by rememberSaveable { mutableStateOf(initialQuery) }
    var regex by rememberSaveable { mutableStateOf(false) }
    var caseSensitive by rememberSaveable { mutableStateOf(false) }
    var glob by rememberSaveable { mutableStateOf("") }
    var project by rememberSaveable { mutableStateOf(initialProject) }
    var projectMenu by remember { mutableStateOf(false) }

    val matches = remember { mutableStateListOf<CodeMatch>() }
    var running by remember { mutableStateOf(false) }
    var finished by remember { mutableStateOf<CodeSearchEvent.Finished?>(null) }

    val projects by produceState(emptyList<String>()) {
        value = withContext(Dispatchers.IO) {
            paths.projects.listFiles { f -> f.isDirectory && !f.name.startsWith(".") }.orEmpty().map { it.name }.sortedBy { it.lowercase() }
        }
    }

    val query = CodeQuery(text, regex, caseSensitive, glob, project)
    // A new query cancels the one running (and its ripgrep) when this restarts.
    LaunchedEffect(query) {
        matches.clear()
        finished = null
        if (query.text.isBlank()) { running = false; return@LaunchedEffect }
        delay(350) // typing: wait for a pause
        running = true
        try {
            codeSearch(paths, query).collect { e ->
                when (e) {
                    is CodeSearchEvent.Found -> matches.addAll(e.matches)
                    is CodeSearchEvent.Finished -> finished = e
                }
            }
        } finally {
            running = false
        }
    }

    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    Column(Modifier.fillMaxSize().background(colors.background).systemBarsPadding()) {
        ScreenHeader("Find code", onBack)
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                text, { text = it },
                placeholder = { Text(if (regex) "Regular expression" else "Text in any project") },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
            OutlinedTextField(
                glob, { glob = it },
                placeholder = { Text("Only files like *.kt, *.ts (optional)") },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box {
                    Chip(project ?: "All projects", onClick = { projectMenu = true }, selected = project != null, trailing = Ph.CaretDown)
                    DropdownMenu(expanded = projectMenu, onDismissRequest = { projectMenu = false }) {
                        DropdownMenuItem(text = { Text("All projects") }, onClick = { projectMenu = false; project = null })
                        for (p in projects) DropdownMenuItem(text = { Text(p) }, onClick = { projectMenu = false; project = p })
                    }
                }
                Chip("Regex", selected = regex, onClick = { regex = !regex })
                Chip("Match case", selected = caseSensitive, onClick = { caseSensitive = !caseSensitive })
                if (running) WaveDots(dotSize = 4.dp)
            }
            val done = finished
            val files = remember(matches.size) { matches.distinctBy { it.project to it.path }.size }
            val inProjects = remember(matches.size) { matches.distinctBy { it.project }.size }
            when {
                done?.error != null -> Text(done.error, color = colors.error, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                text.isBlank() -> Text(
                    "Searches every project under ~/projects. Skips .git, node_modules, build output, virtualenvs, binary files and files over 1 MB.",
                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                )
                matches.isEmpty() && done != null -> Text(if (projects.isEmpty()) "No projects yet." else "No matches.", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                matches.isNotEmpty() -> Text(
                    "${matches.size}${if (done?.capped == true) "+" else ""} matches in $files file${if (files == 1) "" else "s"}" +
                        (if (inProjects > 1) " across $inProjects projects" else "") +
                        (if (done?.capped == true) " (stopped at ${CodeSearch.MAX_MATCHES})" else ""),
                    style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant,
                )
            }
            if (done?.builtIn == true) Text(
                "Using the built-in search. Install the developer tools in System checks for faster results that follow .gitignore.",
                style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant,
            )
        }
        val groups = remember(matches.size) { CodeSearch.group(matches.toList()) }
        val mark = colors.tertiaryContainer
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (g in groups) {
                item {
                    Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        dev.yurlungr.app.ui.PhIcon(Ph.FolderSimple, null, size = 16.dp, tint = colors.onSurfaceVariant)
                        Spacer(Modifier.width(6.dp))
                        Text(g.project, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f, fill = false))
                        Spacer(Modifier.width(6.dp))
                        Text("${g.count}", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                    }
                }
                for (f in g.files) item {
                    val file = File(paths.projects, "${g.project}/${f.path}")
                    FileMatches(f, mark) { line -> onOpenFile(file, line) }
                }
            }
        }
    }
}

@Composable
private fun FileMatches(group: CodeFileGroup, mark: Color, onOpen: (Int) -> Unit) {
    val colors = MaterialTheme.colorScheme
    var expanded by remember { mutableStateOf(false) }
    val shown = if (expanded) group.matches else group.matches.take(FEW)
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(colors.surfaceContainerHigh).padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            group.path, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.StartEllipsis,
            modifier = Modifier.fillMaxWidth().clickable { onOpen(group.matches.first().line) }.padding(horizontal = 10.dp, vertical = 2.dp),
        )
        var lastShown = -1
        for (m in shown) {
            Column(Modifier.fillMaxWidth().clickable { onOpen(m.line) }.padding(horizontal = 10.dp, vertical = 3.dp)) {
                // Context lines already shown with the match above aren't repeated.
                for (c in m.before) if (c.number > lastShown) CodeRow(c.number, AnnotatedString(c.text), dim = true)
                CodeRow(m.line, highlighted(m, mark), dim = false)
                for (c in m.after) CodeRow(c.number, AnnotatedString(c.text), dim = true)
            }
            lastShown = m.after.lastOrNull()?.number ?: m.line
        }
        if (group.matches.size > FEW && !expanded) Text(
            "Show ${group.matches.size - FEW} more",
            style = MaterialTheme.typography.labelMedium, color = colors.primary,
            modifier = Modifier.clickable { expanded = true }.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

private const val FEW = 5

@Composable
private fun CodeRow(number: Int, text: AnnotatedString, dim: Boolean) {
    val colors = MaterialTheme.colorScheme
    Row {
        Text("$number", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = colors.onSurfaceVariant, modifier = Modifier.widthIn(min = 38.dp))
        Spacer(Modifier.width(6.dp))
        Text(
            text, fontFamily = FontFamily.Monospace, fontSize = 11.sp, maxLines = if (dim) 1 else 3, overflow = TextOverflow.Ellipsis,
            color = if (dim) colors.onSurfaceVariant else colors.onSurface,
        )
    }
}

private fun highlighted(m: CodeMatch, mark: Color): AnnotatedString = buildAnnotatedString {
    append(m.text)
    for (r in m.ranges) {
        if (r.first < 0 || r.last >= m.text.length) continue
        addStyle(SpanStyle(background = mark, fontWeight = FontWeight.SemiBold), r.first, r.last + 1)
    }
}
