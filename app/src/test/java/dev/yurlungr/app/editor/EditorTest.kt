package dev.yurlungr.app.editor

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorTest {
    private fun kinds(text: String, lang: Language) =
        Highlighter.tokenize(text, lang).map { text.substring(it.start, it.end) to it.kind }

    @Test
    fun `kotlin keywords, strings, comments, annotations, functions, types`() {
        val t = kinds("@Composable fun Foo(x: Int) = \"hi\" // done", Languages.Kotlin)
        assertTrue(("@Composable" to TokenKind.Annotation) in t)
        assertTrue(("fun" to TokenKind.Keyword) in t)
        assertTrue(("Foo" to TokenKind.Function) in t)
        assertTrue(("Int" to TokenKind.Type) in t)
        assertTrue(("\"hi\"" to TokenKind.Str) in t)
        assertTrue(("// done" to TokenKind.Comment) in t)
    }

    @Test
    fun `json keys are properties, values strings`() {
        val t = kinds("{\"name\": \"app\", \"n\": 2, \"ok\": true}", Languages.Json)
        assertTrue(("\"name\"" to TokenKind.Property) in t)
        assertTrue(("\"app\"" to TokenKind.Str) in t)
        assertTrue(("2" to TokenKind.Number) in t)
        assertTrue(("true" to TokenKind.Literal) in t)
    }

    @Test
    fun `xml tags attributes and comments`() {
        val t = kinds("<!-- c --><manifest package=\"a.b\"/>", Languages.Xml)
        assertTrue(("<!-- c -->" to TokenKind.Comment) in t)
        assertTrue(("manifest" to TokenKind.Tag) in t)
        assertTrue(("package" to TokenKind.Attribute) in t)
        assertTrue(("\"a.b\"" to TokenKind.Str) in t)
    }

    @Test
    fun `unterminated block comment and string do not crash`() {
        Highlighter.tokenize("/* open\n\"str", Languages.Kotlin)
        Highlighter.tokenize("<tag attr=\"x", Languages.Markup)
    }

    @Test
    fun `language by file name`() {
        assertEquals("Kotlin", Languages.forFile("build.gradle.kts").name)
        assertEquals("TypeScript", Languages.forFile("App.tsx").name)
        assertEquals("Plain text", Languages.forFile("LICENSE").name)
    }

    @Test
    fun `enter keeps indent and splits braces`() {
        val old = TextFieldValue("    foo {}", TextRange(9))
        val typed = TextFieldValue("    foo {\n}", TextRange(10))
        val r = smartEdit(old, typed, "    ", Languages.Kotlin)
        assertEquals("    foo {\n        \n    }", r.text)
        assertEquals(TextRange(18), r.selection)
    }

    @Test
    fun `opener gets its closer and closer steps over`() {
        val r = smartEdit(TextFieldValue("f", TextRange(1)), TextFieldValue("f(", TextRange(2)), "  ", Languages.Kotlin)
        assertEquals("f()", r.text)
        val r2 = smartEdit(r, TextFieldValue("f())", TextRange(3)), "  ", Languages.Kotlin)
        assertEquals("f()", r2.text)
        assertEquals(TextRange(3), r2.selection)
    }

    @Test
    fun `lines, find and indent detection`() {
        val starts = lineStarts("a\nbc\n\nd")
        assertEquals(listOf(0, 2, 5, 6), starts.toList())
        assertEquals(1, lineIndexOf(starts, 3))
        assertEquals(3, findAll("Foo foo FOO", "foo", matchCase = false).size)
        assertEquals(1, findAll("Foo foo FOO", "foo", matchCase = true).size)
        assertEquals("  ", detectIndent("a\n  b\n    c\n", Languages.Kotlin))
        assertEquals("\t", detectIndent("a\n\tb\n\tc\n", Languages.Kotlin))
    }
}
