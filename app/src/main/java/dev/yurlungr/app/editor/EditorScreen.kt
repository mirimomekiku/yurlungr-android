package dev.yurlungr.app.editor

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.yurlungr.app.projects.Git
import dev.yurlungr.app.projects.humanSize
import dev.yurlungr.app.runtime.RuntimePaths
import dev.yurlungr.app.ui.NavButton
import dev.yurlungr.app.ui.Ph
import dev.yurlungr.app.ui.PhIcon
import dev.yurlungr.app.ui.SkeletonCode
import dev.yurlungr.app.ui.WaveDots
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** The chosen editor theme (Settings → Editor); reading it subscribes to changes. */
private val Mono: EditorTheme get() = EditorPrefs.theme

private const val MAX_EDIT_BYTES = 1L * 1024 * 1024
private const val MAX_HIGHLIGHT_CHARS = 300_000

private sealed interface Load {
    data object Loading : Load
    data class Ready(val text: String, val crlf: Boolean) : Load
    data class Unavailable(val reason: String) : Load
}

/** Undo/redo snapshots; consecutive typing is grouped into one step. */
private class History {
    val undo = ArrayDeque<TextFieldValue>()
    val redo = ArrayDeque<TextFieldValue>()
    var version by mutableIntStateOf(0) // recompose when stacks change
    private var lastPush = 0L

    fun record(before: TextFieldValue, boundary: Boolean) {
        val now = System.currentTimeMillis()
        if (boundary || now - lastPush > 800 || undo.isEmpty()) {
            undo.addLast(before)
            if (undo.size > 300) undo.removeFirst()
            lastPush = now
        }
        redo.clear()
        version++
    }

    fun breakGroup() { lastPush = 0 }
}

/**
 * Code editor (Sublime-style): Monokai syntax colors for ~25 languages, line numbers, current
 * line, undo/redo, find & replace, auto-indent and bracket closing, a symbol row above the
 * keyboard, Prettier formatting, and a status bar. Edits are saved explicitly.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EditorScreen(file: File, onBack: () -> Unit) {
    val context = LocalContext.current
    val paths = remember { RuntimePaths(context) }
    val scope = rememberCoroutineScope()
    val lang = remember(file) { Languages.forFile(file.name) }
    LaunchedEffect(context) { EditorPrefs.load(context) }

    var load by remember(file) { mutableStateOf<Load>(Load.Loading) }
    LaunchedEffect(file) {
        load = withContext(Dispatchers.IO) {
            when {
                !file.exists() -> Load.Ready("", crlf = false) // new file
                !file.isFile -> Load.Unavailable("Not a file")
                file.length() > MAX_EDIT_BYTES -> Load.Unavailable("Too large to edit here (${humanSize(file.length())})")
                Git.isBinary(file) -> Load.Unavailable("Binary file (${humanSize(file.length())})")
                else -> {
                    val raw = file.readText()
                    val crlf = raw.contains("\r\n")
                    Load.Ready(if (crlf) raw.replace("\r\n", "\n") else raw, crlf)
                }
            }
        }
    }

    when (val l = load) {
        Load.Loading -> Column(Modifier.fillMaxSize().background(Mono.Bg).systemBarsPadding()) {
            EditorTopBar(file, dirty = false, onBack = onBack)
            SkeletonCode()
        }
        is Load.Unavailable -> Column(Modifier.fillMaxSize().background(Mono.Bg).systemBarsPadding()) {
            BackHandler(onBack = onBack)
            EditorTopBar(file, dirty = false, onBack = onBack)
            Text(l.reason, color = Mono.Dim, modifier = Modifier.padding(24.dp))
        }
        is Load.Ready -> Editor(file, l, lang, paths, onBack, scope)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Editor(file: File, initial: Load.Ready, lang: Language, paths: RuntimePaths, onBack: () -> Unit, scope: kotlinx.coroutines.CoroutineScope) {
    val context = LocalContext.current
    val density = LocalDensity.current
    var value by remember { mutableStateOf(TextFieldValue(initial.text)) }
    var saved by remember { mutableStateOf(initial.text) }
    val history = remember { History() }
    val indentUnit = remember { detectIndent(initial.text, lang) }
    var readOnly by remember { mutableStateOf(false) }
    var wrap by remember { mutableStateOf(lang.kind == Language.Kind.Markdown || lang.kind == Language.Kind.Plain) }
    val fontSp = EditorPrefs.fontSize
    var formatting by remember { mutableStateOf(false) }
    var confirmLeave by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }

    // Find & replace
    var findOpen by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var replacement by remember { mutableStateOf("") }
    var matchCase by remember { mutableStateOf(false) }
    var currentMatch by remember { mutableIntStateOf(0) }
    val matches = remember(value.text, query, matchCase) { findAll(value.text, query, matchCase) }

    var tokens by remember { mutableStateOf(emptyList<Token>()) }
    LaunchedEffect(value.text) {
        if (value.text.length > MAX_HIGHLIGHT_CHARS) { tokens = emptyList(); return@LaunchedEffect }
        delay(40) // coalesce fast typing
        val text = value.text
        tokens = withContext(Dispatchers.Default) { Highlighter.tokenize(text, lang) }
    }

    val dirty = value.text != saved
    val vScroll = rememberScrollState()
    val hScroll = rememberScrollState()
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    var viewportPx by remember { mutableIntStateOf(0) }

    fun set(new: TextFieldValue, boundary: Boolean = true) {
        if (new.text != value.text) history.record(value, boundary)
        value = new
    }

    fun save(then: () -> Unit = {}) = scope.launch {
        val text = value.text
        val ok = withContext(Dispatchers.IO) {
            runCatching {
                file.parentFile?.mkdirs()
                file.writeText(if (initial.crlf) text.replace("\n", "\r\n") else text)
            }.isSuccess
        }
        if (ok) {
            saved = text
            history.breakGroup()
            Toast.makeText(context, "Saved", Toast.LENGTH_SHORT).show()
            then()
        } else {
            Toast.makeText(context, "Couldn't save ${file.name}", Toast.LENGTH_LONG).show()
        }
    }

    fun format() {
        if (formatting) return
        formatting = true
        scope.launch {
            when (val r = Formatter.prettier(paths, file, value.text)) {
                is Formatter.Result.Formatted -> if (r.text != value.text) {
                    val caret = value.selection.start.coerceAtMost(r.text.length)
                    set(TextFieldValue(r.text, TextRange(caret)))
                    Toast.makeText(context, "Formatted with Prettier", Toast.LENGTH_SHORT).show()
                } else Toast.makeText(context, "Already formatted", Toast.LENGTH_SHORT).show()
                is Formatter.Result.Failed -> Toast.makeText(context, r.message, Toast.LENGTH_LONG).show()
            }
            formatting = false
        }
    }

    fun reveal(offset: Int) {
        val lay = layout ?: return
        val line = lay.getLineForOffset(offset.coerceIn(0, value.text.length))
        val top = lay.getLineTop(line)
        val x = lay.getHorizontalPosition(offset.coerceIn(0, value.text.length), true)
        scope.launch { vScroll.animateScrollTo((top - viewportPx / 3f).toInt().coerceAtLeast(0)) }
        if (!wrap) scope.launch { hScroll.animateScrollTo((x - 120f).toInt().coerceAtLeast(0)) }
    }

    fun goToMatch(i: Int) {
        if (matches.isEmpty()) return
        currentMatch = ((i % matches.size) + matches.size) % matches.size
        val m = matches[currentMatch]
        value = value.copy(selection = TextRange(m.first, m.last + 1))
        reveal(m.first)
    }

    fun undo() {
        val prev = history.undo.removeLastOrNull() ?: return
        history.redo.addLast(value)
        history.version++
        history.breakGroup()
        value = prev
    }

    fun redo() {
        val next = history.redo.removeLastOrNull() ?: return
        history.undo.addLast(value)
        history.version++
        history.breakGroup()
        value = next
    }

    fun tryBack() {
        when {
            findOpen -> findOpen = false
            dirty -> confirmLeave = true
            else -> onBack()
        }
    }
    BackHandler(onBack = ::tryBack)

    Column(
        Modifier
            .fillMaxSize()
            .background(Mono.Bg)
            .systemBarsPadding()
            .imePadding(),
    ) {
        @Suppress("UNUSED_EXPRESSION") history.version
        EditorTopBar(file, dirty, onBack = ::tryBack) {
            if (formatting) Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) { WaveDots(dotSize = 4.dp, color = Mono.Accent) }
            BarButton(Ph.ArrowUUpLeft, "Undo", enabled = history.undo.isNotEmpty() && !readOnly, onClick = ::undo)
            BarButton(Ph.ArrowUUpRight, "Redo", enabled = history.redo.isNotEmpty() && !readOnly, onClick = ::redo)
            BarButton(Ph.MagnifyingGlass, "Find", onClick = { findOpen = !findOpen })
            BarButton(Ph.FloppyDisk, "Save", enabled = dirty, tint = if (dirty) Mono.Accent else Mono.Fg, onClick = { save() })
            Box {
                BarButton(Ph.DotsThreeVertical, "More", onClick = { menu = true })
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        leadingIcon = { PhIcon(Ph.MagicWand, null, size = 18.dp) },
                        text = { Text(if (Formatter.supports(lang)) "Format with Prettier" else "Prettier doesn't format ${lang.name}") },
                        enabled = Formatter.supports(lang) && !readOnly && !formatting,
                        onClick = { menu = false; format() },
                    )
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text((if (wrap) "✓ " else "") + "Word wrap") }, onClick = { menu = false; wrap = !wrap })
                    DropdownMenuItem(
                        leadingIcon = { PhIcon(Ph.LockSimple, null, size = 18.dp) },
                        text = { Text((if (readOnly) "✓ " else "") + "Read only") },
                        onClick = { menu = false; readOnly = !readOnly },
                    )
                    DropdownMenuItem(text = { Text("Larger text") }, onClick = { EditorPrefs.setFontSize(context, fontSp + 1) })
                    DropdownMenuItem(text = { Text("Smaller text") }, onClick = { EditorPrefs.setFontSize(context, fontSp - 1) })
                    DropdownMenuItem(
                        text = { Text("Theme: ${Mono.name}") },
                        onClick = {
                            val all = EditorThemes.all
                            EditorPrefs.setTheme(context, all[(all.indexOf(Mono) + 1) % all.size])
                        },
                    )
                    HorizontalDivider()
                    DropdownMenuItem(
                        enabled = dirty,
                        text = { Text("Revert to saved") },
                        onClick = { menu = false; set(TextFieldValue(saved, TextRange(value.selection.start.coerceAtMost(saved.length)))) },
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = findOpen,
            enter = fadeIn(tween(150)) + expandVertically(tween(200)),
            exit = fadeOut(tween(120)) + shrinkVertically(tween(150)),
        ) {
            FindBar(
                query = query,
                onQuery = { query = it; currentMatch = 0 },
                replacement = replacement,
                onReplacement = { replacement = it },
                matchCase = matchCase,
                onMatchCase = { matchCase = !matchCase },
                count = matches.size,
                current = if (matches.isEmpty()) 0 else currentMatch + 1,
                readOnly = readOnly,
                onPrev = { goToMatch(currentMatch - 1) },
                onNext = { goToMatch(currentMatch + 1) },
                onReplace = {
                    matches.getOrNull(currentMatch)?.let { m ->
                        val t = value.text.replaceRange(m.first, m.last + 1, replacement)
                        set(TextFieldValue(t, TextRange(m.first + replacement.length)))
                    }
                },
                onReplaceAll = {
                    if (matches.isNotEmpty()) {
                        val sb = StringBuilder()
                        var last = 0
                        for (m in matches) {
                            sb.append(value.text, last, m.first).append(replacement)
                            last = m.last + 1
                        }
                        sb.append(value.text, last, value.text.length)
                        set(TextFieldValue(sb.toString(), TextRange(0)))
                        Toast.makeText(context, "Replaced ${matches.size}", Toast.LENGTH_SHORT).show()
                    }
                },
            )
        }

        val textStyle = TextStyle(
            fontFamily = FontFamily.Monospace,
            fontSize = fontSp.sp,
            lineHeight = (fontSp * 1.5f).sp,
            color = Mono.Fg,
        )
        val theme = Mono
        val transformation = remember(tokens, matches, currentMatch, findOpen, theme) {
            HighlightTransformation(theme, tokens, if (findOpen) matches else emptyList(), currentMatch)
        }
        val measurer = rememberTextMeasurer()
        val lineStarts = remember(value.text) { lineStarts(value.text) }
        val gutterChars = maxOf(2, lineStarts.size.toString().length)
        val gutterWidth = with(density) { (fontSp.sp.toPx() * 0.62f * gutterChars).toDp() } + 22.dp
        val cursorLine = remember(value.selection.start, lineStarts) { lineIndexOf(lineStarts, value.selection.start) }

        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val viewportH = maxHeight
            val viewportW = maxWidth
            viewportPx = with(density) { viewportH.roundToPx() }
            Row(Modifier.fillMaxSize().verticalScroll(vScroll).padding(top = 6.dp)) {
                // Line numbers, drawn only for what's on screen; they follow wrapped lines too.
                val gutterStyle = textStyle.copy(color = Mono.Dim)
                Box(
                    Modifier
                        .width(gutterWidth)
                        .height(with(density) { (layout?.size?.height ?: 0).toDp() }.coerceAtLeast(viewportH))
                        .drawBehind {
                            val lay = layout ?: return@drawBehind
                            val top = vScroll.value - 40f
                            val bottom = vScroll.value + viewportPx + 40f
                            for (i in lineStarts.indices) {
                                val start = lineStarts[i].coerceAtMost(lay.layoutInput.text.length)
                                val visual = lay.getLineForOffset(start)
                                val y = lay.getLineTop(visual)
                                if (y < top) continue
                                if (y > bottom) break
                                val label = (i + 1).toString()
                                val measured = measurer.measure(label, gutterStyle.copy(color = if (i == cursorLine) Mono.Fg else Mono.Dim))
                                // Baseline-aligned with the code line, whatever the line-height padding.
                                val labelTop = lay.getLineBaseline(visual) - measured.firstBaseline
                                drawText(measured, topLeft = Offset(size.width - measured.size.width - 12.dp.toPx(), labelTop))
                            }
                        },
                )
                val textModifier = Modifier
                    .then(if (wrap) Modifier.fillMaxWidth() else Modifier.widthIn(min = viewportW - gutterWidth))
                    .heightIn(min = viewportH)
                    .drawBehind {
                        val lay = layout ?: return@drawBehind
                        val line = lay.getLineForOffset(value.selection.start.coerceIn(0, lay.layoutInput.text.length))
                        val y = lay.getLineTop(line)
                        drawRect(Mono.CurrentLine, topLeft = Offset(0f, y), size = Size(size.width, lay.getLineBottom(line) - y))
                    }
                    .padding(end = 40.dp, bottom = 240.dp)
                Box(Modifier.weight(1f).then(if (wrap) Modifier else Modifier.horizontalScroll(hScroll))) {
                    CompositionLocalProvider(LocalTextSelectionColors provides TextSelectionColors(handleColor = Mono.Accent, backgroundColor = Mono.Selection)) {
                        BasicTextField(
                            value = value,
                            onValueChange = { new ->
                                if (readOnly && new.text != value.text) return@BasicTextField
                                if (new.text == value.text) {
                                    value = new // selection/composition only
                                } else {
                                    val smart = smartEdit(value, new, indentUnit, lang)
                                    val inserted = smart.text.length - value.text.length
                                    val boundary = inserted != 1 || smart.text.getOrNull(smart.selection.start - 1)?.isWhitespace() == true
                                    set(smart, boundary)
                                }
                            },
                            readOnly = readOnly,
                            textStyle = textStyle,
                            cursorBrush = SolidColor(Mono.Accent),
                            visualTransformation = transformation,
                            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, capitalization = KeyboardCapitalization.None),
                            onTextLayout = { layout = it },
                            modifier = textModifier,
                        )
                    }
                }
            }
        }

        if (WindowInsets.isImeVisible && !readOnly) {
            SymbolBar(onInsert = { s ->
                val sel = value.selection
                val text = value.text.replaceRange(sel.min, sel.max, if (s == "⇥") indentUnit else s)
                val caret = sel.min + if (s == "⇥") indentUnit.length else s.length
                set(TextFieldValue(text, TextRange(caret)))
            }, onMove = { delta ->
                val c = (value.selection.start + delta).coerceIn(0, value.text.length)
                value = value.copy(selection = TextRange(c))
            })
        }
        StatusBar(
            line = cursorLine + 1,
            col = value.selection.start - (lineStarts.getOrNull(cursorLine) ?: 0) + 1,
            selected = value.selection.length,
            lines = lineStarts.size,
            lang = lang.name,
            indent = if (indentUnit == "\t") "Tabs" else "Spaces: ${indentUnit.length}",
            eol = if (initial.crlf) "CRLF" else "LF",
            readOnly = readOnly,
            highlightOff = value.text.length > MAX_HIGHLIGHT_CHARS,
        )
    }

    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text("Save changes to ${file.name}?") },
            text = { Text("Your edits will be lost if you leave without saving.") },
            confirmButton = { Button(onClick = { confirmLeave = false; save(onBack) }) { Text("Save") } },
            dismissButton = {
                Row {
                    TextButton(onClick = { confirmLeave = false }) { Text("Cancel") }
                    TextButton(onClick = { confirmLeave = false; onBack() }) { Text("Discard", color = MaterialTheme.colorScheme.error) }
                }
            },
        )
    }
}

/** Colors tokens and search matches; the text itself is unchanged (identity offsets). */
private class HighlightTransformation(
    private val theme: EditorTheme,
    private val tokens: List<Token>,
    private val matches: List<IntRange>,
    private val current: Int,
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val n = text.length
        val out = AnnotatedString.Builder(text)
        for (t in tokens) {
            if (t.start >= n) break
            out.addStyle(theme.style(t.kind), t.start, minOf(t.end, n))
        }
        matches.forEachIndexed { i, m ->
            if (m.last < n) out.addStyle(SpanStyle(background = if (i == current) Mono.MatchCurrent else Mono.Match), m.first, m.last + 1)
        }
        return TransformedText(out.toAnnotatedString(), OffsetMapping.Identity)
    }

    // Recreated whenever its inputs change, so identity equality is right.
    override fun equals(other: Any?) = this === other
    override fun hashCode() = System.identityHashCode(this)
}

@Composable
private fun EditorTopBar(file: File, dirty: Boolean, onBack: () -> Unit, actions: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().background(Mono.Bar).padding(end = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides Mono.Fg) { NavButton(onBack) }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(file.name, color = Mono.Fg, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (dirty) {
                    Spacer(Modifier.width(6.dp))
                    Box(Modifier.size(7.dp).clip(RoundedCornerShape(4.dp)).background(Mono.Modified))
                }
            }
            Text(
                file.parent?.substringAfter("/files/home")?.let { "~$it" }.orEmpty(),
                color = Mono.Dim,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        actions()
    }
}

@Composable
private fun BarButton(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, enabled: Boolean = true, tint: Color = Mono.Fg, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(40.dp)) {
        PhIcon(icon, description, size = 19.dp, tint = if (enabled) tint else Mono.Dim.copy(alpha = 0.6f))
    }
}

@Composable
private fun FindBar(
    query: String,
    onQuery: (String) -> Unit,
    replacement: String,
    onReplacement: (String) -> Unit,
    matchCase: Boolean,
    onMatchCase: () -> Unit,
    count: Int,
    current: Int,
    readOnly: Boolean,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onReplace: () -> Unit,
    onReplaceAll: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().background(Mono.Bar).padding(horizontal = 10.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MiniField(query, onQuery, "Find", Modifier.weight(1f))
            Text(if (query.isEmpty()) "" else "$current/$count", color = Mono.Dim, fontSize = 12.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(horizontal = 6.dp))
            Text(
                "Aa",
                color = if (matchCase) Mono.Bg else Mono.Dim,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (matchCase) Mono.Accent else Color.Transparent)
                    .clickable(onClick = onMatchCase)
                    .padding(horizontal = 6.dp, vertical = 4.dp),
            )
            BarButton(Ph.CaretUp, "Previous match", enabled = count > 0, onClick = onPrev)
            BarButton(Ph.CaretDown, "Next match", enabled = count > 0, onClick = onNext)
        }
        if (!readOnly) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MiniField(replacement, onReplacement, "Replace", Modifier.weight(1f))
                TextButton(onClick = onReplace, enabled = count > 0) { Text("Replace", color = if (count > 0) Mono.Accent else Mono.Dim, fontSize = 13.sp) }
                TextButton(onClick = onReplaceAll, enabled = count > 0) { Text("All", color = if (count > 0) Mono.Accent else Mono.Dim, fontSize = 13.sp) }
            }
        }
    }
}

@Composable
private fun MiniField(value: String, onChange: (String) -> Unit, hint: String, modifier: Modifier) {
    BasicTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        textStyle = TextStyle(color = Mono.Fg, fontFamily = FontFamily.Monospace, fontSize = 13.sp),
        cursorBrush = SolidColor(Mono.Accent),
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, capitalization = KeyboardCapitalization.None),
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Mono.Bg)
            .padding(horizontal = 10.dp, vertical = 9.dp),
        decorationBox = { inner ->
            Box {
                if (value.isEmpty()) Text(hint, color = Mono.Dim, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                inner()
            }
        },
    )
}

private val SYMBOLS = listOf("⇥", "←", "→", "{", "}", "(", ")", "[", "]", "<", ">", ";", ":", "=", "\"", "'", "`", "/", "\\", "|", "&", "!", "?", "$", "_", "-", "+", "*", "#", "@", "%", "~")

@Composable
private fun SymbolBar(onInsert: (String) -> Unit, onMove: (Int) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Mono.Bar)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 4.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for (s in SYMBOLS) {
            Box(
                Modifier
                    .heightIn(min = 36.dp)
                    .widthIn(min = 36.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Mono.Bg)
                    .clickable {
                        when (s) {
                            "←" -> onMove(-1)
                            "→" -> onMove(1)
                            else -> onInsert(s)
                        }
                    }
                    .padding(horizontal = 8.dp),
                contentAlignment = Alignment.Center,
            ) { Text(s, color = Mono.Fg, fontFamily = FontFamily.Monospace, fontSize = 15.sp) }
        }
    }
}

@Composable
private fun StatusBar(line: Int, col: Int, selected: Int, lines: Int, lang: String, indent: String, eol: String, readOnly: Boolean, highlightOff: Boolean) {
    Row(
        Modifier.fillMaxWidth().background(Mono.Bar).horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        val style = TextStyle(color = Mono.Dim, fontSize = 11.5.sp, fontFamily = FontFamily.Monospace)
        Text("Ln $line, Col $col" + if (selected > 0) " ($selected selected)" else "", style = style)
        Text("$lines lines", style = style)
        Text(lang + if (highlightOff) " (no colors: large file)" else "", style = style)
        Text(indent, style = style)
        Text(eol, style = style)
        if (readOnly) Text("Read only", style = style.copy(color = Mono.Modified))
    }
}

// ---- Editing helpers (pure) ------------------------------------------------------------------

/** Offsets where each line starts. */
internal fun lineStarts(text: String): IntArray {
    var count = 1
    for (c in text) if (c == '\n') count++
    val out = IntArray(count)
    var k = 1
    for (i in text.indices) if (text[i] == '\n') out[k++] = i + 1
    return out
}

internal fun lineIndexOf(starts: IntArray, offset: Int): Int {
    var lo = 0
    var hi = starts.size - 1
    while (lo < hi) {
        val mid = (lo + hi + 1) / 2
        if (starts[mid] <= offset) lo = mid else hi = mid - 1
    }
    return lo
}

internal fun findAll(text: String, query: String, matchCase: Boolean, cap: Int = 5000): List<IntRange> {
    if (query.isEmpty()) return emptyList()
    val out = ArrayList<IntRange>()
    var i = text.indexOf(query, 0, ignoreCase = !matchCase)
    while (i >= 0 && out.size < cap) {
        out += i until i + query.length
        i = text.indexOf(query, i + query.length, ignoreCase = !matchCase)
    }
    return out
}

/** Tabs if most indented lines use them, else the smallest common space indent (2 or 4). */
internal fun detectIndent(text: String, lang: Language): String {
    var tabs = 0
    var two = 0
    var four = 0
    for (line in text.lineSequence().take(2000)) {
        if (line.startsWith("\t")) tabs++
        else if (line.startsWith("  ") && line.trim().isNotEmpty()) {
            val n = line.length - line.trimStart(' ').length
            if (n % 4 == 0) four++ else if (n % 2 == 0) two++
        }
    }
    return when {
        tabs > two + four -> "\t"
        two > 0 -> "  "
        four > 0 -> "    "
        lang.name in setOf("JavaScript", "TypeScript", "JSON", "YAML", "HTML", "CSS", "SCSS", "Markdown") -> "  "
        else -> "    "
    }
}

private val PAIRS = mapOf('(' to ')', '[' to ']', '{' to '}')

/**
 * Typing help on a single inserted character: Enter keeps the indent (one level deeper after
 * an opener, and splits `{|}` onto three lines); openers get their closer; typing a closer
 * that's already there steps over it.
 */
internal fun smartEdit(old: TextFieldValue, new: TextFieldValue, indent: String, lang: Language): TextFieldValue {
    val pos = new.selection.start
    val single = old.selection.collapsed && new.selection.collapsed &&
        new.text.length == old.text.length + 1 && pos == old.selection.start + 1 &&
        new.text.regionMatches(0, old.text, 0, pos - 1) &&
        new.text.regionMatches(pos, old.text, pos - 1, old.text.length - (pos - 1))
    if (!single) return new
    val c = new.text[pos - 1]
    val before = old.text
    val next = before.getOrNull(pos - 1)
    when {
        c == '\n' -> {
            val lineStart = before.lastIndexOf('\n', pos - 2) + 1
            val lineIndent = before.substring(lineStart).takeWhile { it == ' ' || it == '\t' }
            val prev = before.substring(lineStart, pos - 1).trimEnd().lastOrNull()
            val opens = prev in PAIRS.keys || (prev == ':' && (lang.name == "Python" || lang.name == "YAML"))
            val deeper = if (opens) lineIndent + indent else lineIndent
            return if (opens && prev != null && next == PAIRS[prev]) {
                val ins = "\n$deeper\n$lineIndent"
                TextFieldValue(before.substring(0, pos - 1) + ins + before.substring(pos - 1), TextRange(pos + deeper.length))
            } else {
                TextFieldValue(before.substring(0, pos - 1) + "\n" + deeper + before.substring(pos - 1), TextRange(pos + deeper.length))
            }
        }
        c in PAIRS.values && next == c -> return TextFieldValue(before, TextRange(pos)) // step over
        c in PAIRS.keys && (next == null || next.isWhitespace() || next in PAIRS.values) && lang.kind == Language.Kind.Code -> {
            val t = before.substring(0, pos - 1) + c + PAIRS.getValue(c) + before.substring(pos - 1)
            return TextFieldValue(t, TextRange(pos))
        }
    }
    return new
}
