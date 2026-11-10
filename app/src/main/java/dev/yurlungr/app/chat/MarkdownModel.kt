package dev.yurlungr.app.chat

import org.commonmark.ext.autolink.AutolinkExtension
import org.commonmark.ext.footnotes.FootnoteDefinition
import org.commonmark.ext.footnotes.FootnotesExtension
import org.commonmark.ext.front.matter.YamlFrontMatterBlock
import org.commonmark.ext.front.matter.YamlFrontMatterExtension
import org.commonmark.ext.front.matter.YamlFrontMatterNode
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.ext.task.list.items.TaskListItemsExtension
import org.commonmark.node.Code
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.Heading
import org.commonmark.node.HtmlBlock
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Node
import org.commonmark.parser.Parser

/** A real CommonMark tree (GitHub flavour) also handles incomplete blocks while a reply is streaming. */
internal object MarkdownParser {
    private val parser = Parser.builder().extensions(
        listOf(
            TablesExtension.create(),
            StrikethroughExtension.create(),
            TaskListItemsExtension.create(),
            AutolinkExtension.create(),
            FootnotesExtension.create(),
            YamlFrontMatterExtension.create(),
        ),
    ).build()

    fun parse(text: String): Node = parser.parse(text)
}

internal fun Node.children(): List<Node> = buildList {
    var child = firstChild
    while (child != null) { add(child); child = child.next }
}

/** An image to show: from Markdown `![alt](src)` or an HTML `<img>`. [width] in px when given. */
data class MdImage(val src: String, val alt: String, val width: Int? = null)

/** One top-level piece of a document, as shown (and scrolled to) in the viewer. */
sealed interface MdBlock {
    /** A CommonMark block. [anchor] is set for headings (GitHub-style id, for #links). */
    data class Block(val node: Node, val anchor: String? = null) : MdBlock
    /** `<details><summary>…</summary> … </details>`: a collapsible section. */
    data class Details(val summary: String, val children: List<MdBlock>) : MdBlock
    /** An HTML block reduced to what's safe to show: its images and its text. */
    data class Html(val images: List<MdImage>, val text: String, val centered: Boolean) : MdBlock
    /** YAML front matter at the top of the file. */
    data class FrontMatter(val entries: List<Pair<String, String>>) : MdBlock
}

/** A heading for the outline: where it is ([blockIndex] among the top-level blocks) and its #anchor. */
data class MdHeading(val level: Int, val text: String, val anchor: String, val blockIndex: Int)

/** A parsed document, ready for lazy rendering, the outline, #anchors, footnotes and search. */
class MarkdownDoc(
    val blocks: List<MdBlock>,
    val headings: List<MdHeading>,
    /** Footnote label → the top-level block that defines it. */
    val footnotes: Map<String, Int>,
) {
    /** The block an anchor (`#install`, `fn-1`) points at, or null. */
    fun blockFor(anchor: String): Int? {
        val a = anchor.removePrefix("#").lowercase()
        headings.firstOrNull { it.anchor == a }?.let { return it.blockIndex }
        return footnotes[a.removePrefix("fn-")]
    }

    companion object {
        fun parse(text: String): MarkdownDoc = build(MarkdownParser.parse(text))

        fun build(document: Node): MarkdownDoc {
            val slugs = SlugCounter()
            val headings = ArrayList<MdHeading>()
            val footnotes = HashMap<String, Int>()
            // A stack of open <details>: their summary and the blocks collected inside them.
            val stack = ArrayList<Pair<String, MutableList<MdBlock>>>()
            val top = ArrayList<MdBlock>()

            fun emit(block: MdBlock) {
                (stack.lastOrNull()?.second ?: top).add(block)
            }

            for (node in document.children()) {
                when (node) {
                    is YamlFrontMatterBlock -> {
                        val entries = node.children().filterIsInstance<YamlFrontMatterNode>()
                            .map { it.key to it.values.joinToString(", ") }
                        if (entries.isNotEmpty()) emit(MdBlock.FrontMatter(entries))
                    }
                    is HtmlBlock -> {
                        val html = node.literal
                        var rest = html
                        // A block can open and/or close details, with a summary and other markup around it.
                        if (HtmlLite.opensDetails(rest)) {
                            stack += HtmlLite.summary(rest).ifBlank { "Details" } to mutableListOf()
                            rest = HtmlLite.afterSummary(rest)
                        }
                        val closes = HtmlLite.closesDetails(rest)
                        rest = rest.replace(HtmlLite.DETAILS_CLOSE, "")
                        val images = HtmlLite.images(rest)
                        val text = HtmlLite.text(rest)
                        if (images.isNotEmpty() || text.isNotBlank()) emit(MdBlock.Html(images, text, HtmlLite.centered(rest)))
                        if (closes && stack.isNotEmpty()) {
                            val (summary, children) = stack.removeAt(stack.lastIndex)
                            emit(MdBlock.Details(summary, children))
                        }
                    }
                    is Heading -> {
                        val text = plainText(node)
                        val anchor = slugs.next(text)
                        // Inside a <details> nothing reaches the top level until it closes, so top.size is
                        // also where that section will be: headings in it scroll to the section.
                        headings += MdHeading(node.level, text, anchor, top.size)
                        emit(MdBlock.Block(node, anchor))
                    }
                    is FootnoteDefinition -> {
                        footnotes[node.label.lowercase()] = top.size
                        emit(MdBlock.Block(node))
                    }
                    else -> emit(MdBlock.Block(node))
                }
            }
            // Unclosed <details> (common while a reply streams): show what's there.
            while (stack.isNotEmpty()) {
                val (summary, children) = stack.removeAt(stack.lastIndex)
                emit(MdBlock.Details(summary, children))
            }
            return MarkdownDoc(top, headings, footnotes)
        }

        /** The text of a node and its children, for search, anchors and the outline. */
        fun plainText(node: Node): String = buildString {
            fun walk(n: Node) {
                when (n) {
                    is org.commonmark.node.Text -> append(n.literal)
                    is Code -> append(n.literal)
                    is FencedCodeBlock -> append(n.literal)
                    is IndentedCodeBlock -> append(n.literal)
                    is org.commonmark.node.SoftLineBreak, is org.commonmark.node.HardLineBreak -> append(' ')
                    else -> n.children().forEach(::walk)
                }
            }
            walk(node)
        }

        fun plainText(block: MdBlock): String = when (block) {
            is MdBlock.Block -> plainText(block.node)
            is MdBlock.Details -> block.summary + " " + block.children.joinToString(" ") { plainText(it) }
            is MdBlock.Html -> block.text
            is MdBlock.FrontMatter -> block.entries.joinToString(" ") { "${it.first} ${it.second}" }
        }
    }
}

/** GitHub's heading ids: lowercase, punctuation dropped, spaces to dashes, repeats numbered. */
class SlugCounter {
    private val seen = HashMap<String, Int>()

    fun next(text: String): String {
        val base = slug(text)
        val n = seen[base]
        seen[base] = (n ?: 0) + 1
        return if (n == null) base else "$base-$n"
    }

    companion object {
        fun slug(text: String): String = text.trim().lowercase()
            .filter { it.isLetterOrDigit() || it == ' ' || it == '-' || it == '_' }
            .replace(' ', '-')
    }
}

/**
 * The little HTML real READMEs use, reduced to safe pieces: details/summary, images, line breaks
 * and text. Nothing is executed or loaded from here; everything else is dropped.
 */
object HtmlLite {
    private val DETAILS_OPEN = Regex("<details(\\s[^>]*)?>", RegexOption.IGNORE_CASE)
    val DETAILS_CLOSE = Regex("</details\\s*>", RegexOption.IGNORE_CASE)
    private val SUMMARY = Regex("<summary(\\s[^>]*)?>(.*?)</summary\\s*>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val IMG = Regex("<img\\b([^>]*)>", RegexOption.IGNORE_CASE)
    private val BR = Regex("<br\\s*/?>", RegexOption.IGNORE_CASE)
    private val BLOCK_END = Regex("</(p|div|h[1-6]|li|tr)\\s*>", RegexOption.IGNORE_CASE)
    private val TAG = Regex("<[^>]*>")
    private val COMMENT = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)
    /** Script and style bodies are code, not text: dropped with their tags. */
    private val SCRIPT = Regex("<(script|style)\\b[^>]*>.*?</\\1\\s*>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val CENTER = Regex("align\\s*=\\s*[\"']?center", RegexOption.IGNORE_CASE)

    fun opensDetails(html: String) = DETAILS_OPEN.containsMatchIn(html)
    fun closesDetails(html: String) = DETAILS_CLOSE.containsMatchIn(html)
    fun summary(html: String): String = SUMMARY.find(html)?.groupValues?.get(2)?.let(::text).orEmpty()

    /** What follows `<details>` and its `<summary>` in the same block. */
    fun afterSummary(html: String): String {
        val afterOpen = DETAILS_OPEN.find(html)?.let { html.substring(it.range.last + 1) } ?: html
        return SUMMARY.find(afterOpen)?.let { afterOpen.substring(it.range.last + 1) } ?: afterOpen
    }

    fun centered(html: String) = CENTER.containsMatchIn(html)

    fun images(html: String): List<MdImage> = IMG.findAll(html).mapNotNull { m ->
        val attrs = m.groupValues[1]
        val src = attr(attrs, "src")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        MdImage(decode(src), decode(attr(attrs, "alt").orEmpty()), attr(attrs, "width")?.filter(Char::isDigit)?.toIntOrNull())
    }.toList()

    /** The text a reader sees: tags removed, breaks kept, entities decoded, blank runs collapsed. */
    fun text(html: String): String {
        val withBreaks = COMMENT.replace(html, "").let { SCRIPT.replace(it, "") }.let { BR.replace(it, "\n") }.let { BLOCK_END.replace(it, "\n") }
        return decode(TAG.replace(withBreaks, ""))
            .lines().map { it.trim() }.joinToString("\n").replace(Regex("\n{3,}"), "\n\n").trim()
    }

    private fun attr(attrs: String, name: String): String? =
        Regex("\\b$name\\s*=\\s*(\"([^\"]*)\"|'([^']*)'|([^\\s>]+))", RegexOption.IGNORE_CASE).find(attrs)?.let { m ->
            m.groupValues[2].ifEmpty { m.groupValues[3] }.ifEmpty { m.groupValues[4] }
        }

    fun decode(s: String): String = s
        .replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
        .replace("&#39;", "'").replace("&apos;", "'").replace("&middot;", "·").replace("&amp;", "&")

    /** Inline tags the renderer styles (the rest are dropped); null when [tag] isn't one. */
    fun inlineTag(tag: String): Pair<String, Boolean>? {
        val m = Regex("^<(/?)\\s*([a-zA-Z0-9]+)[^>]*?>$").find(tag.trim()) ?: return null
        val name = m.groupValues[2].lowercase()
        if (name !in INLINE) return null
        return name to (m.groupValues[1] == "/")
    }

    private val INLINE = setOf("b", "strong", "i", "em", "code", "kbd", "sub", "sup", "br", "s", "del", "mark", "u")
}
