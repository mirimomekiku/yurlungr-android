package dev.yurlungr.app.voice

import dev.yurlungr.app.chat.HtmlLite
import dev.yurlungr.app.chat.MarkdownParser
import dev.yurlungr.app.chat.children
import org.commonmark.ext.footnotes.FootnoteDefinition
import org.commonmark.ext.footnotes.FootnoteReference
import org.commonmark.ext.front.matter.YamlFrontMatterBlock
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableBody
import org.commonmark.ext.gfm.tables.TableHead
import org.commonmark.ext.task.list.items.TaskListItemMarker
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.Code
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
import org.commonmark.node.Text
import org.commonmark.node.ThematicBreak

/**
 * Turns an agent's Markdown reply into text worth hearing: code blocks, tables and diagrams are
 * named instead of read out symbol by symbol, links read their text, and every block ends with
 * punctuation so the voice pauses between them. Pure (no Android), so it's unit-tested.
 */
object SpeakableText {
    /** Inline code longer than this is said as "code" (paths and short names are worth hearing). */
    private const val INLINE_CODE_MAX = 32
    private val DIAGRAMS = setOf("mermaid", "plantuml", "dot", "graphviz", "d2")
    private val URL = Regex("^(https?://|www\\.)\\S+$", RegexOption.IGNORE_CASE)
    private val SPACES = Regex("[ \\t\\u00A0]+")

    fun fromMarkdown(markdown: String): String {
        if (markdown.isBlank()) return ""
        val blocks = ArrayList<String>()
        for (node in MarkdownParser.parse(markdown).children()) block(node, blocks)
        return blocks.map(::tidy).filter { it.isNotEmpty() }.joinToString("\n")
    }

    private fun block(node: Node, out: MutableList<String>) {
        when (node) {
            is Heading, is Paragraph -> out += inline(node)
            is FencedCodeBlock -> out += if (node.info.orEmpty().trim().substringBefore(' ').lowercase() in DIAGRAMS) "Diagram." else "Code block."
            is IndentedCodeBlock -> out += "Code block."
            is TableBlock -> out += tableSummary(node)
            is BulletList, is OrderedList -> node.children().forEach { block(it, out) }
            is ListItem -> {
                // The first paragraph carries the item's text; nested lists and blocks follow.
                val parts = ArrayList<String>()
                node.children().forEach { if (it !is TaskListItemMarker) block(it, parts) }
                parts.removeAll { it.isBlank() }
                // The checkbox sits on the item or on its first paragraph, depending on the parser.
                val marker = node.firstChild as? TaskListItemMarker ?: node.firstChild?.firstChild as? TaskListItemMarker
                val prefix = marker?.let { if (it.isChecked) "Done: " else "To do: " }.orEmpty()
                if (parts.isNotEmpty()) parts[0] = prefix + parts[0].trimStart()
                out += parts
            }
            is BlockQuote -> node.children().forEach { block(it, out) }
            is HtmlBlock -> HtmlLite.text(node.literal).takeIf { it.isNotBlank() }?.let { out += it.replace('\n', ' ') }
            is ThematicBreak, is YamlFrontMatterBlock, is FootnoteDefinition -> Unit
            else -> out += inline(node)
        }
    }

    private fun tableSummary(table: TableBlock): String {
        val rows = table.children().filterIsInstance<TableBody>().sumOf { it.children().size }
        val columns = table.children().filterIsInstance<TableHead>().firstOrNull()?.firstChild?.children()?.size ?: 0
        return when {
            rows == 0 -> "A table."
            columns > 0 -> "A table with $rows ${plural(rows, "row")} and $columns ${plural(columns, "column")}."
            else -> "A table with $rows ${plural(rows, "row")}."
        }
    }

    private fun plural(n: Int, word: String) = if (n == 1) word else word + "s"

    private fun inline(node: Node): String = buildString {
        fun walk(n: Node) {
            when (n) {
                is Text -> append(n.literal)
                is Code -> append(if (n.literal.length <= INLINE_CODE_MAX && '\n' !in n.literal) n.literal else "code")
                is SoftLineBreak, is HardLineBreak -> append(' ')
                is Link -> {
                    val text = buildString { n.children().forEach { c -> if (c is Text) append(c.literal) else if (c is Code) append(c.literal) } }
                    // A bare URL (or an autolink) is noise when spoken.
                    if (text.isBlank() || URL.matches(text.trim())) append("link") else n.children().forEach(::walk)
                }
                is Image -> n.children().forEach(::walk) // the alt text
                is HtmlInline, is FootnoteReference, is TaskListItemMarker -> Unit
                else -> n.children().forEach(::walk)
            }
        }
        walk(node)
    }

    /** One line per block, single spaces, ending in punctuation so the voice pauses. */
    private fun tidy(text: String): String {
        val t = SPACES.replace(text.replace('\n', ' '), " ").trim()
        if (t.isEmpty()) return t
        return if (t.last().isLetterOrDigit() || t.last() in ")\"'`*") t + "." else t
    }

    /**
     * Splits [text] into pieces no longer than [max] (TTS engines cap one utterance at about
     * 4,000 characters), at line breaks, then sentence ends, then spaces where possible.
     */
    fun chunks(text: String, max: Int): List<String> {
        require(max > 0)
        val out = ArrayList<String>()
        var rest = text.trim()
        while (rest.length > max) {
            val window = rest.substring(0, max + 1)
            val cut = listOf(window.lastIndexOf('\n'), sentenceEnd(window), window.lastIndexOf(' '))
                .firstOrNull { it > max / 3 } ?: max
            out += rest.substring(0, cut).trim()
            rest = rest.substring(cut).trim()
        }
        if (rest.isNotEmpty()) out += rest
        return out.filter { it.isNotEmpty() }
    }

    /** Index just after the last ". ", "! " or "? " in [s], or -1. */
    private fun sentenceEnd(s: String): Int {
        val i = maxOf(s.lastIndexOf(". "), s.lastIndexOf("! "), s.lastIndexOf("? "))
        return if (i < 0) -1 else i + 1
    }

    /** What's spoken for a permission prompt in hands-free mode. */
    fun permissionPrompt(agentName: String, toolTitle: String, toolKind: String?): String {
        val what = toolTitle.replace("`", "").replace(SPACES, " ").trim().let { if (it.length > 160) it.take(160).trimEnd() + "…" else it }
        val verb = when (toolKind) {
            "execute" -> "run"
            "edit" -> "edit"
            "delete" -> "delete"
            "move" -> "move"
            "fetch" -> "fetch"
            "read" -> "read"
            else -> "do"
        }
        return "$agentName wants to $verb: $what. Say allow or deny."
    }
}
