package dev.yurlungr.app.chat

import androidx.compose.ui.graphics.Color
import org.commonmark.node.*
import org.commonmark.ext.gfm.tables.TableBlock
import org.junit.Assert.*
import org.junit.Test

class MarkdownTest {
    @Test fun `file links containing code labels render without markdown syntax`() {
        val doc = MarkdownParser.parse("* **File browser:** See [`src/file_browser.rs`](file:///data/data/com.termux/files/home/projects/demo/src/file_browser.rs).")
        val list = doc.firstChild as BulletList
        val paragraph = list.firstChild.firstChild
        val rendered = markdownInline(paragraph, Color.Gray, Color.Blue) {}
        assertEquals("File browser: See src/file_browser.rs.", rendered.text)
        val link = rendered.getLinkAnnotations(0, rendered.length).single()
        assertEquals("file:///data/data/com.termux/files/home/projects/demo/src/file_browser.rs", (link.item as androidx.compose.ui.text.LinkAnnotation.Clickable).tag)
    }

    @Test fun `nested lists headings quotes tables and code have distinct blocks`() {
        val doc = MarkdownParser.parse("""
            # Title

            3. Third
               - Nested

            > A quote

            | Name | Value |
            | --- | --- |
            | one | two |

            ```kotlin
            val value = "**literal**"
            ```
        """.trimIndent())
        val blocks = doc.children()
        assertTrue(blocks[0] is Heading)
        assertTrue(blocks[1] is OrderedList)
        assertTrue(blocks[1].firstChild.children().any { it is BulletList })
        assertTrue(blocks[2] is BlockQuote)
        assertTrue(blocks[3] is TableBlock)
        assertEquals("val value = \"**literal**\"\n", (blocks[4] as FencedCodeBlock).literal)
    }

    @Test fun `streaming unclosed fences and escaped markers stay readable`() {
        val fence = MarkdownParser.parse("~~~js\nconst a = 1;").firstChild as FencedCodeBlock
        assertEquals("const a = 1;\n", fence.literal)
        val p = MarkdownParser.parse("\\*literal\\* and *italic* and ~~gone~~  \nnext").firstChild
        assertEquals("*literal* and italic and gone\nnext", markdownInline(p, Color.Gray, Color.Blue) {}.text)
    }
}
