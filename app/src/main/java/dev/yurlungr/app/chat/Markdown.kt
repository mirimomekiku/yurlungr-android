package dev.yurlungr.app.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.BitmapFactory
import androidx.core.net.toUri
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import dev.yurlungr.app.editor.EditorPrefs
import dev.yurlungr.app.editor.EditorTheme
import dev.yurlungr.app.editor.EditorThemes
import dev.yurlungr.app.editor.Highlighter
import dev.yurlungr.app.editor.Languages
import dev.yurlungr.app.runtime.RuntimeBridge
import dev.yurlungr.app.ui.AppColors
import dev.yurlungr.app.ui.LocalOpenFile
import dev.yurlungr.app.ui.Ph
import dev.yurlungr.app.ui.PhIcon
import dev.yurlungr.app.ui.ThemePrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.commonmark.ext.footnotes.FootnoteDefinition
import org.commonmark.ext.footnotes.FootnoteReference
import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.ext.task.list.items.TaskListItemMarker
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.HtmlBlock
import org.commonmark.node.HtmlInline
import org.commonmark.node.Image
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.ThematicBreak
import java.io.File

/** What a rendered document needs from its host: links, #anchors, files, and the search to highlight. */
class MdHost(
    val openLink: (String) -> Unit,
    /** `#section` links and footnotes; null where there's nothing to scroll (chat replies). */
    val onAnchor: ((String) -> Unit)? = null,
    val baseDirectory: File? = null,
    val allowLocalFiles: Boolean = true,
    /** Highlighted wherever it occurs (find in document). */
    val query: String = "",
)

/**
 * Inline Markdown as styled text: emphasis, code, links (and bare URLs), footnote references, and
 * the allowlisted inline HTML (<kbd>, <sub>, <br>…). Other HTML is dropped, never executed.
 */
internal fun markdownInline(
    node: Node,
    codeBg: Color,
    linkColor: Color,
    onAnchor: ((String) -> Unit)? = null,
    query: String = "",
    matchBg: Color = Color(0x66FFD54F),
    open: (String) -> Unit,
): AnnotatedString {
    val built = buildAnnotatedString {
        val htmlStack = ArrayList<Pair<String, Int>>() // open inline tag → pushStyle index
        fun linkTo(target: String, label: () -> Unit) {
            val style = TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline))
            pushLink(LinkAnnotation.Clickable(target, style) {
                if (target.startsWith("#") && onAnchor != null) onAnchor(target) else open(target)
            })
            label()
            pop()
        }
        fun render(n: Node) {
            when (n) {
                is org.commonmark.node.Text -> append(n.literal)
                is Code -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg)) { append(n.literal) }
                is SoftLineBreak -> append(' ')
                is HardLineBreak -> append('\n')
                is StrongEmphasis -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { n.children().forEach(::render) }
                is Emphasis -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { n.children().forEach(::render) }
                is Strikethrough -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { n.children().forEach(::render) }
                is Link -> linkTo(n.destination) { n.children().forEach(::render) }
                is Image -> { append("[Image: "); n.children().forEach(::render); append(']') }
                is FootnoteReference -> withStyle(SpanStyle(baselineShift = BaselineShift.Superscript, fontSize = 0.75.em)) {
                    linkTo("#fn-${n.label}") { append("[${n.label}]") }
                }
                is TaskListItemMarker -> Unit // drawn as a checkbox by the list
                is HtmlInline -> {
                    val tag = HtmlLite.inlineTag(n.literal)
                    when {
                        tag == null -> Unit // unknown HTML: dropped
                        tag.first == "br" -> append('\n')
                        !tag.second -> htmlStack += tag.first to pushStyle(inlineStyle(tag.first, codeBg))
                        else -> {
                            val i = htmlStack.indexOfLast { it.first == tag.first }
                            if (i >= 0) {
                                // Closing a tag also closes anything opened after it (lenient, like browsers).
                                while (htmlStack.size > i) pop(htmlStack.removeAt(htmlStack.lastIndex).second)
                            }
                        }
                    }
                }
                else -> n.children().forEach(::render)
            }
        }
        render(node)
        while (htmlStack.isNotEmpty()) pop(htmlStack.removeAt(htmlStack.lastIndex).second)
    }
    return highlight(built, query, matchBg)
}

private fun inlineStyle(tag: String, codeBg: Color): SpanStyle = when (tag) {
    "b", "strong" -> SpanStyle(fontWeight = FontWeight.Bold)
    "i", "em" -> SpanStyle(fontStyle = FontStyle.Italic)
    "code", "kbd" -> SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg)
    "sub" -> SpanStyle(baselineShift = BaselineShift.Subscript, fontSize = 0.75.em)
    "sup" -> SpanStyle(baselineShift = BaselineShift.Superscript, fontSize = 0.75.em)
    "s", "del" -> SpanStyle(textDecoration = TextDecoration.LineThrough)
    "u" -> SpanStyle(textDecoration = TextDecoration.Underline)
    "mark" -> SpanStyle(background = Color(0x55FFEB3B))
    else -> SpanStyle()
}

/** [text] with every case-insensitive occurrence of [query] marked. */
internal fun highlight(text: AnnotatedString, query: String, color: Color): AnnotatedString {
    val q = query.trim()
    if (q.isEmpty()) return text
    val starts = ArrayList<Int>()
    var i = text.text.indexOf(q, ignoreCase = true)
    while (i >= 0) {
        starts += i
        i = text.text.indexOf(q, i + q.length, ignoreCase = true)
    }
    if (starts.isEmpty()) return text
    return buildAnnotatedString {
        append(text)
        for (s in starts) addStyle(SpanStyle(background = color), s, s + q.length)
    }
}

/** A chat reply (or any short Markdown): every block in order. */
@Composable
fun MarkdownText(text: String, modifier: Modifier = Modifier, allowLocalFiles: Boolean = true, baseDirectory: File? = null) {
    val doc = remember(text) { MarkdownDoc.parse(text) }
    val host = rememberMdHost(allowLocalFiles, baseDirectory)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        doc.blocks.forEach { MdBlockView(it, host) }
    }
}

/** Opens links: http(s) in the browser, local files (relative to [baseDirectory]) in the app. */
@Composable
fun rememberMdHost(
    allowLocalFiles: Boolean,
    baseDirectory: File?,
    onAnchor: ((String) -> Unit)? = null,
    query: String = "",
): MdHost {
    val context = LocalContext.current
    val openFile = LocalOpenFile.current
    val open: (String) -> Unit = { destination ->
        val uri = destination.toUri()
        when (uri.scheme?.lowercase()) {
            "http", "https" -> RuntimeBridge.open(context, destination)
            "mailto" -> RuntimeBridge.open(context, destination)
            null -> if (allowLocalFiles && baseDirectory != null && !uri.path.isNullOrEmpty()) {
                val path = File(uri.path!!)
                val target = if (path.isAbsolute) path else File(baseDirectory, uri.path!!)
                when {
                    target.isFile -> openFile?.invoke(target)
                    else -> Toast.makeText(context, "Not found: ${uri.path}", Toast.LENGTH_SHORT).show()
                }
            }
            "file" -> if (allowLocalFiles && uri.authority.isNullOrEmpty()) {
                uri.path?.let(::File)?.takeIf { it.isFile }?.let { openFile?.invoke(it) }
            }
        }
    }
    return remember(allowLocalFiles, baseDirectory, onAnchor, query, openFile) {
        MdHost(open, onAnchor, baseDirectory, allowLocalFiles, query)
    }
}

/** One top-level block of a [MarkdownDoc]. */
@Composable
fun MdBlockView(block: MdBlock, host: MdHost) {
    when (block) {
        is MdBlock.Block -> MarkdownBlock(block.node, host, depth = 0)
        is MdBlock.Details -> DetailsBlock(block, host)
        is MdBlock.Html -> HtmlBlockView(block, host)
        is MdBlock.FrontMatter -> FrontMatterCard(block)
    }
}

@Composable
private fun inline(node: Node, host: MdHost): AnnotatedString {
    val colors = MaterialTheme.colorScheme
    return markdownInline(node, colors.surfaceContainerHighest, colors.primary, onAnchor = host.onAnchor, query = host.query, open = host.openLink)
}

@Composable
private fun MarkdownBlock(node: Node, host: MdHost, depth: Int, tight: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    when (node) {
        is Paragraph -> {
            val images = imagesOnly(node)
            if (images != null) ImageRow(images, host, centered = false)
            else Text(inline(node, host), style = MaterialTheme.typography.bodyLarge)
        }
        is Heading -> {
            val style = when (node.level) {
                1 -> MaterialTheme.typography.headlineMedium
                2 -> MaterialTheme.typography.headlineSmall
                3 -> MaterialTheme.typography.titleLarge
                4 -> MaterialTheme.typography.titleMedium
                5 -> MaterialTheme.typography.titleSmall
                else -> MaterialTheme.typography.labelLarge
            }
            Column(Modifier.semantics { heading() }.padding(top = if (node.level <= 2) 6.dp else 2.dp)) {
                Text(inline(node, host), style = style, fontWeight = FontWeight.Bold,
                    color = if (node.level >= 6) colors.onSurfaceVariant else colors.onSurface)
                // Like GitHub: a rule under the top two levels.
                if (node.level <= 2) HorizontalDivider(Modifier.padding(top = 6.dp), color = colors.outlineVariant)
            }
        }
        is FencedCodeBlock -> CodeBlock(node.literal.trimEnd('\n'), language = node.info?.trim()?.substringBefore(' '), copyable = true, query = host.query)
        is IndentedCodeBlock -> CodeBlock(node.literal.trimEnd('\n'), copyable = true, query = host.query)
        is BulletList, is OrderedList -> ListBlock(node, host, depth)
        is BlockQuote -> QuoteBlock(node, host, depth)
        is ThematicBreak -> HorizontalDivider(color = colors.outlineVariant)
        is TableBlock -> TableView(node, host)
        is FootnoteDefinition -> Row(Modifier.fillMaxWidth()) {
            Text("${node.label}.", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, modifier = Modifier.width(28.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                node.children().forEach { child ->
                    if (child is Paragraph) Text(inline(child, host), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    else MarkdownBlock(child, host, depth)
                }
            }
        }
        // Top-level HTML is handled by MarkdownDoc; this is HTML nested in a list or quote.
        is HtmlBlock -> {
            val images = HtmlLite.images(node.literal)
            if (images.isNotEmpty()) ImageRow(images, host, HtmlLite.centered(node.literal))
            HtmlLite.text(node.literal).takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
        }
        else -> node.children().forEach { MarkdownBlock(it, host, depth, tight) }
    }
}

/** The images of a paragraph that holds nothing but images (and whitespace), else null. */
private fun imagesOnly(p: Paragraph): List<MdImage>? {
    val kids = p.children()
    val images = kids.filterIsInstance<Image>()
    if (images.isEmpty()) return null
    val others = kids.filterNot { it is Image || it is SoftLineBreak || it is HardLineBreak || (it is org.commonmark.node.Text && it.literal.isBlank()) }
    if (others.isNotEmpty()) return null
    return images.map { MdImage(it.destination, MarkdownDoc.plainText(it)) }
}

private val BULLETS = listOf("•", "◦", "▪")

@Composable
private fun ListBlock(node: Node, host: MdHost, depth: Int) {
    val items = node.children().filterIsInstance<ListItem>()
    val ordered = node as? OrderedList
    val start = ordered?.markerStartNumber ?: 1
    // Numbers share one width, so 9. and 10. line up.
    val markerWidth = if (ordered != null) ((start + items.size - 1).toString().length * 9 + 10).dp else 18.dp
    val tight = (node as? BulletList)?.isTight ?: ordered?.isTight ?: false
    Column(verticalArrangement = Arrangement.spacedBy(if (tight) 4.dp else 8.dp)) {
        items.forEachIndexed { index, item ->
            val marker = item.firstChild as? TaskListItemMarker ?: (item.firstChild?.firstChild as? TaskListItemMarker)
            Row {
                Box(Modifier.width(markerWidth).padding(top = 2.dp)) {
                    when {
                        marker != null -> PhIcon(
                            if (marker.isChecked) Ph.CheckCircle else Ph.Circle,
                            if (marker.isChecked) "Done" else "Not done",
                            size = 16.dp,
                            tint = if (marker.isChecked) AppColors.status.success else MaterialTheme.colorScheme.outline,
                        )
                        ordered != null -> Text("${start + index}.", style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.End, modifier = Modifier.fillMaxWidth().padding(end = 6.dp))
                        else -> Text(BULLETS[depth % BULLETS.size], style = MaterialTheme.typography.bodyLarge)
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(if (tight) 2.dp else 6.dp)) {
                    item.children().forEach { child ->
                        if (child !is TaskListItemMarker) MarkdownBlock(child, host, if (child is BulletList || child is OrderedList) depth + 1 else depth, tight)
                    }
                }
            }
        }
    }
}

/** GitHub's `> [!NOTE]` callouts, by name: title colour and icon. */
private val ALERTS = mapOf(
    "NOTE" to Ph.Info, "TIP" to Ph.Lightning, "IMPORTANT" to Ph.Warning, "WARNING" to Ph.Warning, "CAUTION" to Ph.Warning,
)

@Composable
private fun QuoteBlock(node: BlockQuote, host: MdHost, depth: Int) {
    val colors = MaterialTheme.colorScheme
    val first = node.firstChild as? Paragraph
    val alert = (first?.firstChild as? org.commonmark.node.Text)?.literal?.trim()
        ?.let { Regex("^\\[!(NOTE|TIP|IMPORTANT|WARNING|CAUTION)]").find(it)?.groupValues?.get(1) }
    val barColor = when (alert) {
        "NOTE", "IMPORTANT" -> colors.primary
        "TIP" -> AppColors.status.success
        "WARNING" -> AppColors.status.warning
        "CAUTION" -> colors.error
        else -> colors.outline
    }
    // IntrinsicSize.Min: the bar runs the full height of the quote, however long.
    Row(Modifier.height(IntrinsicSize.Min)) {
        Box(Modifier.width(3.dp).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(barColor))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (alert != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PhIcon(ALERTS.getValue(alert), null, size = 16.dp, tint = barColor)
                    Spacer(Modifier.width(6.dp))
                    Text(alert.lowercase().replaceFirstChar { it.uppercase() }, color = barColor, style = MaterialTheme.typography.titleSmall)
                }
            }
            node.children().forEachIndexed { i, child ->
                if (i == 0 && alert != null && child is Paragraph) {
                    // The rest of the first paragraph, without the [!NOTE] marker.
                    val text = inline(child, host)
                    val marker = text.text.indexOf(']') + 1
                    val rest = if (marker in 1..text.length) text.subSequence(marker, text.length) else text
                    if (rest.text.isNotBlank()) Text(rest, style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
                } else MarkdownBlock(child, host, depth)
            }
        }
    }
}

@Composable
private fun TableView(node: TableBlock, host: MdHost) {
    val colors = MaterialTheme.colorScheme
    val rows = node.children().flatMap { it.children() }
    val cells = rows.map { it.children().filterIsInstance<TableCell>() }
    val columns = cells.maxOfOrNull { it.size } ?: 0
    // Each column as wide as its longest cell (within limits), not a fixed width for all.
    val widths = (0 until columns).map { c ->
        val longest = cells.maxOfOrNull { row -> row.getOrNull(c)?.let { MarkdownDoc.plainText(it).length } ?: 0 } ?: 0
        (longest * 7.5f + 24f).coerceIn(56f, 300f).dp
    }
    Box(Modifier.horizontalScroll(rememberScrollState())) {
        Column(Modifier.clip(RoundedCornerShape(10.dp)).border(1.dp, colors.outlineVariant, RoundedCornerShape(10.dp))) {
            cells.forEachIndexed { r, row ->
                val header = row.firstOrNull()?.isHeader == true
                Row(Modifier.background(if (header) colors.surfaceContainerHigh else if (r % 2 == 0) Color.Transparent else colors.surfaceContainer.copy(alpha = 0.5f))) {
                    row.forEachIndexed { c, cell ->
                        val align = when (cell.alignment) {
                            TableCell.Alignment.CENTER -> TextAlign.Center
                            TableCell.Alignment.RIGHT -> TextAlign.End
                            else -> TextAlign.Start
                        }
                        Text(
                            inline(cell, host),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (header) FontWeight.SemiBold else FontWeight.Normal,
                            textAlign = align,
                            modifier = Modifier.width(widths.getOrElse(c) { 120.dp }).padding(horizontal = 10.dp, vertical = 8.dp),
                        )
                    }
                }
                if (r < cells.lastIndex) HorizontalDivider(color = colors.outlineVariant)
            }
        }
    }
}

@Composable
private fun DetailsBlock(block: MdBlock.Details, host: MdHost) {
    val colors = MaterialTheme.colorScheme
    // A search hit inside opens it.
    val hit = host.query.isNotBlank() && MarkdownDoc.plainText(block).contains(host.query.trim(), ignoreCase = true)
    var open by rememberSaveable(block.summary) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).border(1.dp, colors.outlineVariant, RoundedCornerShape(14.dp))) {
        Row(
            Modifier.fillMaxWidth().clickable { open = !open }.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PhIcon(if (open || hit) Ph.CaretDown else Ph.CaretRight, null, size = 14.dp, tint = colors.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
            Text(highlight(AnnotatedString(block.summary), host.query, Color(0x66FFD54F)), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        }
        AnimatedVisibility(open || hit) {
            Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                block.children.forEach { MdBlockView(it, host) }
            }
        }
    }
}

@Composable
private fun HtmlBlockView(block: MdBlock.Html, host: MdHost) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (block.centered) Alignment.CenterHorizontally else Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (block.images.isNotEmpty()) ImageRow(block.images, host, block.centered)
        if (block.text.isNotBlank()) {
            Text(highlight(AnnotatedString(block.text), host.query, Color(0x66FFD54F)), style = MaterialTheme.typography.bodyLarge,
                textAlign = if (block.centered) TextAlign.Center else TextAlign.Start)
        }
    }
}

@Composable
private fun FrontMatterCard(block: MdBlock.FrontMatter) {
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(colors.surfaceContainer).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for ((key, value) in block.entries) {
            Row {
                Text(key, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant, modifier = Modifier.widthIn(min = 80.dp).padding(end = 10.dp))
                Text(value, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

// ---- Code ------------------------------------------------------------------------------------

/** Fence names that aren't file extensions. */
private val LANGUAGE_ALIASES = mapOf(
    "kotlin" to "kt", "javascript" to "js", "typescript" to "ts", "python" to "py", "shell" to "sh", "bash" to "sh",
    "zsh" to "sh", "console" to "sh", "sh" to "sh", "yaml" to "yml", "rust" to "rs", "ruby" to "rb", "markdown" to "md",
    "c++" to "cpp", "csharp" to "cs", "golang" to "go", "groovy" to "gradle", "jsonc" to "json",
)

/** The editor theme, unless it fights the app's light/dark mode; then GitHub's matching one. */
@Composable
private fun codeTheme(): EditorTheme {
    val dark = ThemePrefs.isDark()
    val chosen = EditorPrefs.theme
    return if (chosen.isDark == dark) chosen else if (dark) EditorThemes.GitHubDark else EditorThemes.GitHubLight
}

/**
 * A code block: syntax colours for the fence's language (the editor's lexer and theme), the
 * language name, and a copy button.
 */
@Composable
fun CodeBlock(
    text: String,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
    language: String? = null,
    copyable: Boolean = false,
    query: String = "",
) {
    val context = LocalContext.current
    val theme = codeTheme()
    val lang = remember(language) {
        language?.lowercase()?.takeIf { it.isNotBlank() }?.let { Languages.forFile("x." + (LANGUAGE_ALIASES[it] ?: it)) }
    }
    val styled = remember(text, lang, theme, query) {
        val base = if (lang != null && text.length <= 20_000) buildAnnotatedString {
            append(text)
            for (t in Highlighter.tokenize(text, lang)) addStyle(theme.style(t.kind), t.start, t.end)
        } else AnnotatedString(text)
        highlight(base, query, theme.Match)
    }
    Column(modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).background(theme.Bg)) {
        if (copyable || language != null) {
            Row(Modifier.fillMaxWidth().background(theme.Bar).padding(start = 10.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(language?.takeIf { it.isNotBlank() } ?: "code", color = theme.Dim, fontSize = 11.sp, modifier = Modifier.weight(1f))
                if (copyable) {
                    Row(
                        Modifier.clip(RoundedCornerShape(8.dp)).clickable {
                            context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("code", text))
                            Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                        }.padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        PhIcon(Ph.Copy, null, size = 13.dp, tint = theme.Dim)
                        Spacer(Modifier.width(4.dp))
                        Text("Copy", color = theme.Dim, fontSize = 11.sp)
                    }
                }
            }
        }
        Text(styled, fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 16.sp,
            maxLines = maxLines, softWrap = false, color = theme.Fg,
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(10.dp))
    }
}

// ---- Images ----------------------------------------------------------------------------------

/** Decoded images, shared by every document (they're re-shown on scroll and recomposition). */
private object MdImageCache {
    private val cache = android.util.LruCache<String, ImageBitmap>(24)
    fun get(key: String): ImageBitmap? = cache.get(key)
    fun put(key: String, image: ImageBitmap) { cache.put(key, image) }
}

@Composable
private fun ImageRow(images: List<MdImage>, host: MdHost, centered: Boolean) {
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, if (centered) Alignment.CenterHorizontally else Alignment.Start),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Several images side by side (README screenshot rows) share the width.
        val each = if (images.size > 1) (1f / images.size).coerceAtLeast(0.18f) else 1f
        images.forEach { MdImageView(it, host, each) }
    }
}

/**
 * An image: local ones (relative to the document) are shown downscaled; remote ones only after a
 * tap, so opening a file doesn't contact the image's server. SVG can't be shown and keeps its text.
 */
@Composable
private fun MdImageView(img: MdImage, host: MdHost, fraction: Float) {
    val colors = MaterialTheme.colorScheme
    val uri = img.src.toUri()
    val remote = uri.scheme?.lowercase() in setOf("http", "https")
    val local: File? = if (remote || !host.allowLocalFiles) null else when (uri.scheme?.lowercase()) {
        null -> host.baseDirectory?.let { base -> uri.path?.let { p -> File(p).let { if (it.isAbsolute) it else File(base, p) } } }
        "file" -> uri.path?.let(::File)
        else -> null
    }
    val svg = img.src.substringBefore('?').lowercase().endsWith(".svg")
    var allowRemote by remember(img.src) { mutableStateOf(MdImageCache.get(img.src) != null) }
    val bitmap by produceState(MdImageCache.get(img.src), img.src, allowRemote) {
        if (value != null || svg) return@produceState
        value = when {
            local != null -> withContext(Dispatchers.IO) { runCatching { decodeSampled(local.readBytes()) }.getOrNull() }
            remote && allowRemote -> withContext(Dispatchers.IO) { runCatching { decodeSampled(download(img.src)) }.getOrNull() }
            else -> null
        }?.also { MdImageCache.put(img.src, it) }
    }
    val widthMod = img.width?.let { Modifier.widthIn(max = (it / 1.5f).dp) } ?: Modifier
    val b = bitmap
    when {
        b != null -> Image(
            b, img.alt,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxWidth(fraction).then(widthMod).clip(RoundedCornerShape(8.dp)),
        )
        remote && !allowRemote && !svg -> Row(
            Modifier.clip(RoundedCornerShape(10.dp)).background(colors.surfaceContainer).clickable { allowRemote = true }.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PhIcon(Ph.Image, null, size = 16.dp, tint = colors.onSurfaceVariant)
            Spacer(Modifier.width(6.dp))
            Text((img.alt.ifBlank { "Image" }) + " · tap to load from ${uri.host}", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
        }
        else -> Text(
            "[${img.alt.ifBlank { "image" }}]",
            style = MaterialTheme.typography.labelMedium,
            color = colors.onSurfaceVariant,
            modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(colors.surfaceContainer).padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

/** Decodes at most ~1600 px wide, so a big photo doesn't take all the memory. */
private fun decodeSampled(bytes: ByteArray): ImageBitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0) return null
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= 1600) sample *= 2
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
}

private fun download(url: String): ByteArray {
    val conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
        connectTimeout = 15_000
        readTimeout = 20_000
        instanceFollowRedirects = true
        setRequestProperty("User-Agent", "Yurlungr")
    }
    try {
        require(conn.responseCode in 200..299) { "HTTP ${conn.responseCode}" }
        conn.inputStream.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                require(out.size() <= 10 * 1024 * 1024) { "Image too large" }
            }
            return out.toByteArray()
        }
    } finally {
        conn.disconnect()
    }
}
