package dev.yurlungr.app.chat

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import org.commonmark.ext.task.list.items.TaskListItemMarker
import org.commonmark.node.BulletList
import org.commonmark.node.Heading
import org.commonmark.node.Link
import org.commonmark.node.Node
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownModelTest {
    private val readme = """
        <h1 align="center">App</h1>

        <p align="center">
          <img src="docs/a.webp" alt="First" width="19%">
          <img src='docs/b.webp' alt="Second &amp; more">
        </p>

        ## Quick start

        Text with a [link](#features) and a note[^1].

        <details>
        <summary><b>Chat</b></summary>

        - item one
        - item two

        ### Inside

        </details>

        ## Quick start

        [^1]: The footnote.
    """.trimIndent()

    @Test fun `README html becomes images, text and collapsible sections`() {
        val doc = MarkdownDoc.parse(readme)
        val html = doc.blocks.filterIsInstance<MdBlock.Html>()
        assertEquals("App", html.first().text)
        assertTrue(html.first().centered)
        val images = html.flatMap { it.images }
        assertEquals(listOf("docs/a.webp", "docs/b.webp"), images.map { it.src })
        assertEquals("Second & more", images[1].alt)
        val details = doc.blocks.filterIsInstance<MdBlock.Details>().single()
        assertEquals("Chat", details.summary)
        assertTrue(details.children.any { it is MdBlock.Block && it.node is BulletList })
        assertTrue(details.children.any { it is MdBlock.Block && it.node is Heading })
    }

    @Test fun `headings get GitHub anchors, repeats numbered, nested ones point at their section`() {
        val doc = MarkdownDoc.parse(readme)
        assertEquals(listOf("quick-start", "inside", "quick-start-1"), doc.headings.map { it.anchor })
        val inside = doc.headings.first { it.text == "Inside" }
        assertTrue("a heading inside <details> scrolls to the section", doc.blocks[inside.blockIndex] is MdBlock.Details)
        assertEquals(doc.headings[0].blockIndex, doc.blockFor("#Quick-Start"))
        assertNull(doc.blockFor("#missing"))
        assertTrue(doc.blockFor("#fn-1") != null)
    }

    @Test fun `slugs drop punctuation like GitHub`() {
        assertEquals("settings--system-checks", SlugCounter.slug("Settings & system checks"))
        assertEquals("what-is-new-in-003", SlugCounter.slug("What is new in 0.0.3?"))
        assertEquals("api_v2", SlugCounter.slug("API_v2"))
    }

    @Test fun `front matter is read, not shown as text`() {
        val doc = MarkdownDoc.parse("---\ntitle: Notes\ntags: [a, b]\n---\n\n# Body\n")
        val fm = doc.blocks.first() as MdBlock.FrontMatter
        assertEquals("title" to "Notes", fm.entries.first())
        assertEquals(1, doc.headings.size)
    }

    @Test fun `task lists and bare URLs are parsed`() {
        val root = MarkdownParser.parse("- [x] done\n- [ ] todo\n\nSee https://example.com now")
        fun all(n: Node): List<Node> = listOf(n) + n.children().flatMap(::all)
        val markers = all(root).filterIsInstance<TaskListItemMarker>()
        assertEquals(listOf(true, false), markers.map { it.isChecked })
        assertEquals("https://example.com", all(root).filterIsInstance<Link>().single().destination)
    }

    @Test fun `unclosed details still shows its content`() {
        val doc = MarkdownDoc.parse("<details>\n<summary>More</summary>\n\nStreaming text")
        val d = doc.blocks.single() as MdBlock.Details
        assertEquals("More", d.summary)
        assertEquals(1, d.children.size)
    }

    @Test fun `html helpers keep text and drop everything else`() {
        assertEquals("a\nb", HtmlLite.text("<p>a</p>b"))
        assertEquals("a\nb", HtmlLite.text("a<br/>b"))
        assertEquals("script and style bodies are dropped", "ok", HtmlLite.text("<script>alert(1)</script><style>p{}</style>ok"))
        assertEquals("x < y", HtmlLite.text("x &lt; y<!-- hidden -->"))
        assertEquals("kbd" to false, HtmlLite.inlineTag("<kbd>"))
        assertEquals("kbd" to true, HtmlLite.inlineTag("</kbd>"))
        assertNull("unknown tags are dropped", HtmlLite.inlineTag("<script>"))
        assertNull(HtmlLite.inlineTag("<a href=\"x\">"))
    }

    @Test fun `inline html styles apply and unknown tags vanish`() {
        val p = MarkdownParser.parse("Press <kbd>Ctrl</kbd> <span>then</span> H<sub>2</sub>O<br>next").firstChild
        val text = markdownInline(p, Color.Gray, Color.Blue) {}.text
        assertEquals("Press Ctrl then H2O\nnext", text)
    }

    @Test fun `search matches are highlighted case-insensitively`() {
        val a = highlight(AnnotatedString("Find the FIND word"), "find", Color.Yellow)
        assertEquals(2, a.spanStyles.size)
        assertEquals(0, a.spanStyles[0].start)
        assertEquals(9, a.spanStyles[1].start)
        assertFalse(highlight(AnnotatedString("x"), "", Color.Yellow).spanStyles.isNotEmpty())
    }
}
