package dev.yurlungr.app.editor

import org.junit.Assert.*
import org.junit.Test

class MarkdownFileTest {
    @Test fun `Markdown previews include uppercase extensions and leave other files in the editor`() {
        for (name in listOf("README.MD", "notes.md", "Guide.Markdown", "notes.mdown")) assertTrue(isMarkdownFile(name))
        for (name in listOf("README", "md", "notes.md.kt", "report.mdx", "app.kt")) assertFalse(isMarkdownFile(name))
    }
}
