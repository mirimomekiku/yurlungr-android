package dev.yurlungr.app.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FilePreviewTest {
    @Test fun `kinds follow the file extension`() {
        assertEquals(PreviewKind.Image, FilePreview.kindOf("shot.PNG"))
        assertEquals(PreviewKind.Web, FilePreview.kindOf("index.html"))
        assertEquals(PreviewKind.Web, FilePreview.kindOf("logo.svg"))
        assertEquals(PreviewKind.Table, FilePreview.kindOf("data.tsv"))
        assertEquals(PreviewKind.Json, FilePreview.kindOf("package.json"))
        assertEquals(PreviewKind.None, FilePreview.kindOf("README.md"))
        assertEquals(PreviewKind.None, FilePreview.kindOf("Makefile"))
    }

    @Test fun `csv fields can be quoted, hold commas, quotes and newlines`() {
        val rows = FilePreview.parseDelimited("name,note\n\"Lee, Ann\",\"said \"\"hi\"\"\"\nx,\"two\nlines\"\n")
        assertEquals(3, rows.size)
        assertEquals(listOf("Lee, Ann", "said \"hi\""), rows[1])
        assertEquals(listOf("x", "two\nlines"), rows[2])
    }

    @Test fun `windows line endings, a missing final newline and blank lines are fine`() {
        val rows = FilePreview.parseDelimited("a,b\r\n\r\n1,2")
        assertEquals(listOf(listOf("a", "b"), listOf("1", "2")), rows)
        assertEquals('\t', FilePreview.delimiterFor("x.TSV"))
        assertEquals(listOf(listOf("a", "b")), FilePreview.parseDelimited("a\tb", '\t'))
    }

    @Test fun `json is re-indented, and invalid json is left alone`() {
        val pretty = FilePreview.prettyJson("{\"a\":[1,2],\"b\":{\"c\":null}}")!!
        assertTrue(pretty.contains("\n"))
        assertTrue(pretty.contains("\"a\": ["))
        assertNull(FilePreview.prettyJson("{not json"))
    }
}
