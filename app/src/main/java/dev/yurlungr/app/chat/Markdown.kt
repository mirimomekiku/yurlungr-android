package dev.yurlungr.app.chat

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.yurlungr.app.runtime.RuntimeBridge
import dev.yurlungr.app.ui.LocalOpenFile
import org.commonmark.node.*
import org.commonmark.parser.Parser
import org.commonmark.ext.gfm.tables.*
import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import java.io.File

/** A real CommonMark tree also handles incomplete blocks while a reply is streaming. */
internal object MarkdownParser {
    private val parser = Parser.builder().extensions(listOf(TablesExtension.create(), StrikethroughExtension.create())).build()
    fun parse(text: String): Node = parser.parse(text)
}

internal fun Node.children(): List<Node> = buildList {
    var child = firstChild
    while (child != null) { add(child); child = child.next }
}

internal fun markdownInline(node: Node, codeBg: Color, linkColor: Color, open: (String) -> Unit): AnnotatedString = buildAnnotatedString {
    fun render(n: Node) {
        when (n) {
            is org.commonmark.node.Text -> append(n.literal)
            is Code -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg)) { append(n.literal) }
            is SoftLineBreak -> append(' ')
            is HardLineBreak -> append('\n')
            is StrongEmphasis -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { n.children().forEach(::render) }
            is Emphasis -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { n.children().forEach(::render) }
            is Strikethrough -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { n.children().forEach(::render) }
            is Link -> {
                pushLink(LinkAnnotation.Clickable(n.destination, TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline))) { open(n.destination) })
                n.children().forEach(::render)
                pop()
            }
            is Image -> { append("[Image: "); n.children().forEach(::render); append(']') }
            is HtmlInline -> append(n.literal) // never execute agent-supplied HTML
            else -> n.children().forEach(::render)
        }
    }
    render(node)
}

@Composable
fun MarkdownText(text: String, modifier: Modifier = Modifier, allowLocalFiles: Boolean = true, baseDirectory: File? = null) {
    val document = remember(text) { MarkdownParser.parse(text) }
    val context = LocalContext.current
    val openFile = LocalOpenFile.current
    val open: (String) -> Unit = { destination ->
        val uri = Uri.parse(destination)
        when (uri.scheme?.lowercase()) {
            "http", "https" -> RuntimeBridge.open(context, destination)
            null -> if (allowLocalFiles && baseDirectory != null && !uri.path.isNullOrEmpty()) {
                val path = File(uri.path!!)
                val target = if (path.isAbsolute) path else File(baseDirectory, uri.path!!)
                if (target.isFile) openFile?.invoke(target)
            }
            "file" -> if (allowLocalFiles && uri.authority.isNullOrEmpty()) {
                uri.path?.let(::File)?.takeIf { it.isFile }?.let { openFile?.invoke(it) }
            }
        }
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        document.children().forEach { MarkdownBlock(it, open) }
    }
}

@Composable
private fun MarkdownBlock(node: Node, open: (String) -> Unit, tight: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    val inline = markdownInline(node, colors.surfaceContainerHighest, colors.primary, open)
    when (node) {
        is org.commonmark.node.Paragraph -> Text(inline, style = MaterialTheme.typography.bodyLarge)
        is Heading -> Text(inline, style = when (node.level) {
            1 -> MaterialTheme.typography.headlineMedium
            2 -> MaterialTheme.typography.headlineSmall
            else -> MaterialTheme.typography.titleMedium
        }, fontWeight = FontWeight.Bold)
        is FencedCodeBlock -> CodeBlock(node.literal.trimEnd('\n'))
        is IndentedCodeBlock -> CodeBlock(node.literal.trimEnd('\n'))
        is BulletList, is OrderedList -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            node.children().forEachIndexed { index, item ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (node is OrderedList) "${node.markerStartNumber + index}." else "•", style = MaterialTheme.typography.bodyLarge)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(if (tight) 2.dp else 6.dp)) {
                        item.children().forEach { MarkdownBlock(it, open, true) }
                    }
                }
            }
        }
        is BlockQuote -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.width(3.dp).height(24.dp).background(colors.outline))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                node.children().forEach { MarkdownBlock(it, open) }
            }
        }
        is ThematicBreak -> HorizontalDivider()
        is TableBlock -> Column(Modifier.horizontalScroll(rememberScrollState())) {
            node.children().flatMap { it.children() }.forEach { row ->
                Row {
                    row.children().forEach { cell ->
                        Text(markdownInline(cell, colors.surfaceContainerHighest, colors.primary, open),
                            fontWeight = if ((cell as? TableCell)?.isHeader == true) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier.width(160.dp).padding(8.dp))
                    }
                }
                HorizontalDivider()
            }
        }
        is HtmlBlock -> Text(node.literal, style = MaterialTheme.typography.bodyLarge)
        else -> node.children().forEach { MarkdownBlock(it, open) }
    }
}

@Composable
fun CodeBlock(text: String, modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE) {
    Text(text, fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 16.sp,
        maxLines = maxLines, softWrap = false, color = Color(0xFFE6EDF3),
        modifier = modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).background(Color(0xFF0D1117))
            .horizontalScroll(rememberScrollState()).padding(10.dp))
}
