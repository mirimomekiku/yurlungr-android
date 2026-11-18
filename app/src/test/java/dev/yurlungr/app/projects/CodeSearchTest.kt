package dev.yurlungr.app.projects

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class CodeSearchTest {
    private fun begin(path: String) = """{"type":"begin","data":{"path":{"text":"$path"}}}"""
    private fun end(path: String) = """{"type":"end","data":{"path":{"text":"$path"},"binary_offset":null,"stats":{}}}"""
    private fun context(path: String, n: Int, text: String) =
        """{"type":"context","data":{"path":{"text":"$path"},"lines":{"text":"$text\n"},"line_number":$n,"absolute_offset":0,"submatches":[]}}"""
    private fun match(path: String, n: Int, text: String, start: Int, end: Int) =
        """{"type":"match","data":{"path":{"text":"$path"},"lines":{"text":"$text\n"},"line_number":$n,"absolute_offset":0,""" +
            """"submatches":[{"match":{"text":"x"},"start":$start,"end":$end}]}}"""

    @Test fun `rg json stream becomes matches with context`() {
        val p = RgJsonParser(context = 1)
        val out = listOf(
            begin("app/src/Main.kt"),
            context("app/src/Main.kt", 1, "package a"),
            match("app/src/Main.kt", 2, "fun main() {}", 4, 8),
            context("app/src/Main.kt", 3, "// end"),
            end("app/src/Main.kt"),
            """{"type":"summary","data":{}}""",
        ).flatMap(p::feed) + p.finish()
        assertEquals(1, out.size)
        val m = out.single()
        assertEquals("app", m.project)
        assertEquals("src/Main.kt", m.path)
        assertEquals(2, m.line)
        assertEquals("fun main() {}", m.text)
        assertEquals(listOf(4..7), m.ranges)
        assertEquals(listOf(CodeLine(1, "package a")), m.before)
        assertEquals(listOf(CodeLine(3, "// end")), m.after)
    }

    @Test fun `adjacent matches don't use each other as context and stream before the file ends`() {
        val p = RgJsonParser(context = 1)
        assertTrue(p.feed(match("a/x.txt", 1, "foo", 0, 3)).isEmpty())
        // The next match hands out the first one.
        assertEquals(1, p.feed(match("a/x.txt", 2, "foo", 0, 3)).single().line)
        val second = p.feed(end("a/x.txt")).single()
        assertEquals(2, second.line)
        assertTrue(second.before.isEmpty())
    }

    @Test fun `non-json lines are kept as errors`() {
        val p = RgJsonParser()
        assertTrue(p.feed("regex parse error:").isEmpty())
        assertTrue(p.feed("""{"type":"match","data":{"path":{"bytes":"AAE="},"lines":{"text":"x"},"line_number":1,"submatches":[]}}""").isEmpty())
        assertTrue(p.finish().isEmpty())
        assertEquals(listOf("regex parse error:"), p.errors)
    }

    @Test fun `byte offsets turn into character offsets`() {
        // "é" is two bytes in UTF-8.
        assertEquals(2..4, RgJsonParser.byteRangeToChars("éaabc", 3, 6))
        assertNull(RgJsonParser.byteRangeToChars("abc", 2, 10))
    }

    @Test fun `paths split into project and file`() {
        assertEquals("web" to "src/app.ts", CodeSearch.splitPath("./web/src/app.ts"))
        assertEquals("web" to "a.ts", CodeSearch.splitPath("web\\a.ts"))
        assertNull(CodeSearch.splitPath("loose.txt"))
    }

    @Test fun `globs filter by name or path, with exclusions`() {
        val g = CodeSearch.globs("*.kt, *.kts !*Test.kt")
        assertEquals(listOf("*.kt", "*.kts", "!*Test.kt"), g)
        assertTrue(CodeSearch.passesGlobs(g, "src/Main.kt"))
        assertTrue(CodeSearch.passesGlobs(g, "build.gradle.kts"))
        assertFalse(CodeSearch.passesGlobs(g, "src/MainTest.kt"))
        assertFalse(CodeSearch.passesGlobs(g, "README.md"))
        assertTrue(CodeSearch.passesGlobs(emptyList(), "anything"))
        assertTrue(CodeSearch.passesGlobs(CodeSearch.globs("src/**/*.ts"), "src/a/b/c.ts"))
        assertTrue(CodeSearch.passesGlobs(CodeSearch.globs("*.{js,ts}"), "x/y.ts"))
        assertFalse(Glob.matches("*.ts", "a/b.ts"))
    }

    @Test fun `ignore rules skip dependencies, build output and generated files`() {
        for (d in listOf(".git", "node_modules", "build", ".venv", "__pycache__", "target")) assertTrue(d, CodeSearch.isIgnoredDir(d))
        assertFalse(CodeSearch.isIgnoredDir("src"))
        assertTrue(CodeSearch.isIgnoredFile("app.min.js"))
        assertTrue(CodeSearch.isIgnoredFile("package-lock.json"))
        assertFalse(CodeSearch.isIgnoredFile("app.js"))
    }

    @Test fun `plain matching is literal and case-insensitive by default`() {
        val m = CodeMatcher(CodeQuery("a.b"))
        assertEquals(listOf(0..2, 4..6), m.find("A.B a.b axb"))
        assertTrue(CodeMatcher(CodeQuery("A.B", caseSensitive = true)).find("a.b").isEmpty())
    }

    @Test fun `regex matching skips empty matches`() {
        assertEquals(listOf(4..6), CodeMatcher(CodeQuery("""f\w+""", regex = true)).find("val foo"))
        assertTrue(CodeMatcher(CodeQuery("x*", regex = true)).find("abc").isEmpty())
    }

    @Test(expected = java.util.regex.PatternSyntaxException::class)
    fun `bad regex throws`() { CodeMatcher(CodeQuery("(", regex = true)) }

    @Test fun `long lines are clipped around the first match`() {
        val line = "x".repeat(1000) + "NEEDLE" + "y".repeat(1000)
        val (text, ranges) = CodeSearch.clip(line, listOf(1000..1005), max = 100)
        assertTrue(text.length <= 102)
        assertEquals("NEEDLE", text.substring(ranges.single().first, ranges.single().last + 1))
        assertEquals("short" to listOf(0..1), CodeSearch.clip("short\n", listOf(0..1)))
    }

    @Test fun `rg arguments keep the query and folders from being read as options`() {
        val a = CodeSearch.rgArgs("rg", CodeQuery("-v", glob = "*.kt"), listOf("app"))
        assertTrue("--fixed-strings" in a)
        assertTrue("--ignore-case" in a)
        assertEquals(listOf("-e", "-v", "--", "app"), a.takeLast(4))
        assertTrue(a.windowed(2).any { it == listOf("--glob", "!node_modules") })
        assertTrue(a.windowed(2).any { it == listOf("--glob", "*.kt") })
        val r = CodeSearch.rgArgs("rg", CodeQuery("a+", regex = true, caseSensitive = true), listOf("app"))
        assertFalse("--fixed-strings" in r)
        assertTrue("--case-sensitive" in r)
    }

    @Test fun `matches group by project then file in the order found`() {
        val ms = listOf(
            CodeMatch("b", "x.kt", 1, "", emptyList()),
            CodeMatch("a", "y.kt", 2, "", emptyList()),
            CodeMatch("b", "x.kt", 5, "", emptyList()),
            CodeMatch("b", "z.kt", 1, "", emptyList()),
        )
        val g = CodeSearch.group(ms)
        assertEquals(listOf("b", "a"), g.map { it.project })
        assertEquals(listOf("x.kt", "z.kt"), g[0].files.map { it.path })
        assertEquals(listOf(1, 5), g[0].files[0].matches.map { it.line })
        assertEquals(3, g[0].count)
    }

    @Test fun `the fallback walker skips ignored folders, binaries and stops at the limit`() {
        val root = Files.createTempDirectory("codesearch").toFile()
        try {
            val app = File(root, "app").apply { mkdirs() }
            File(app, "src").mkdirs()
            File(app, "src/Main.kt").writeText("package a\nval needle = 1\n// after\n")
            File(app, "notes.md").writeText("needle in notes\n")
            File(app, "node_modules/lib").mkdirs()
            File(app, "node_modules/lib/index.js").writeText("needle\n")
            File(app, "image.bin").writeBytes(byteArrayOf(0, 1, 2) + "needle".toByteArray())

            val found = ArrayList<CodeMatch>()
            CodeWalker.search(listOf(app), CodeQuery("NEEDLE")) { found += it; true }
            assertEquals(listOf("notes.md", "src/Main.kt"), found.map { it.path }.sorted())
            val main = found.first { it.path == "src/Main.kt" }
            assertEquals(2, main.line)
            assertEquals("app", main.project)
            assertEquals(listOf(CodeLine(1, "package a")), main.before)
            assertEquals(listOf(CodeLine(3, "// after")), main.after)

            val kt = ArrayList<CodeMatch>()
            CodeWalker.search(listOf(app), CodeQuery("needle", glob = "*.kt")) { kt += it; true }
            assertEquals(listOf("src/Main.kt"), kt.map { it.path })

            val one = ArrayList<CodeMatch>()
            CodeWalker.search(listOf(app), CodeQuery("needle"), limit = 1) { one += it; true }
            assertEquals(1, one.size)
        } finally {
            root.deleteRecursively()
        }
    }
}
