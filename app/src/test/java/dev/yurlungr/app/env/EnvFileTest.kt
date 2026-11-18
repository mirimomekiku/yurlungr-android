package dev.yurlungr.app.env

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EnvFileTest {
    private val sample = """
        |# Database
        |DATABASE_URL=postgres://u:p@localhost/db
        |
        |export API_KEY="abc\"def"  # the key
        |  SPACED = value with spaces   # trailing comment
        |HASH=abc#def
        |HASH_SPACE=abc #comment
        |SINGLE='it''s'
        |LITERAL='${'$'}HOME \n stays'
        |EMPTY=
        |MULTI="line one
        |line two"
        |TICK=`tick`
        |not a variable line
        |ESCAPES="a\nb\tc\\d"
        |""".trimMargin()

    @Test fun `parse and render round-trip byte for byte`() {
        assertEquals(sample, EnvFormat.parse(sample).render())
        val crlf = sample.replace("\n", "\r\n")
        assertEquals(crlf, EnvFormat.parse(crlf).render())
        val noFinalNewline = "A=1\nB=\"x\""
        assertEquals(noFinalNewline, EnvFormat.parse(noFinalNewline).render())
        assertEquals("", EnvFormat.parse("").render())
    }

    @Test fun `values are read like the loaders read them`() {
        val doc = EnvFormat.parse(sample)
        assertEquals("postgres://u:p@localhost/db", doc.value("DATABASE_URL"))
        assertEquals("abc\"def", doc.value("API_KEY"))
        assertTrue(doc.entries.first { it.key == "API_KEY" }.export)
        assertEquals("value with spaces", doc.value("SPACED"))
        assertEquals("abc#def", doc.value("HASH"))
        assertEquals("abc", doc.value("HASH_SPACE"))
        assertEquals("\$HOME \\n stays", doc.value("LITERAL"))
        assertEquals("", doc.value("EMPTY"))
        assertEquals("line one\nline two", doc.value("MULTI"))
        assertEquals("tick", doc.value("TICK"))
        assertEquals("a\nb\tc\\d", doc.value("ESCAPES"))
        assertFalse("not a variable line" in doc.keys)
        assertNull(doc.value("MISSING"))
    }

    @Test fun `multiline values in a CRLF file read with plain newlines`() {
        val doc = EnvFormat.parse("KEY=\"one\r\ntwo\"\r\nNEXT=1\r\n")
        assertEquals("one\ntwo", doc.value("KEY"))
        assertEquals("1", doc.value("NEXT"))
        assertEquals("\r\n", doc.newline)
    }

    @Test fun `an unterminated quote is read as a bare value`() {
        val doc = EnvFormat.parse("A=\"open")
        assertEquals("\"open", doc.value("A"))
        assertEquals("A=\"open", doc.render())
    }

    @Test fun `editing one variable leaves every other line untouched`() {
        val doc = EnvFormat.parse(sample).set("SPACED", "new value")
        val expected = sample.replace("  SPACED = value with spaces   # trailing comment", "  SPACED = 'new value'   # trailing comment")
        assertEquals(expected, doc.render())
    }

    @Test fun `export prefix and quote style are kept on edit`() {
        val doc = EnvFormat.parse("export TOKEN=\"old\"\n").set("TOKEN", "new")
        assertEquals("export TOKEN=\"new\"\n", doc.render())
        val single = EnvFormat.parse("A='x'\n").set("A", "y z")
        assertEquals("A='y z'\n", single.render())
        // Single quotes can't hold a quote: switch to double quotes rather than corrupt it.
        val switched = EnvFormat.parse("A='x'\n").set("A", "it's")
        assertEquals("A=\"it's\"\n", switched.render())
        assertEquals("it's", EnvFormat.parse(switched.render()).value("A"))
    }

    @Test fun `bare values get quotes only when they need them`() {
        assertEquals("A=plain\n", EnvFormat.parse("A=old\n").set("A", "plain").render())
        assertEquals("A='has # hash'\n", EnvFormat.parse("A=old\n").set("A", "has # hash").render())
        assertEquals("A='p\$ss'\n", EnvFormat.parse("A=old\n").set("A", "p\$ss").render())
        assertEquals("A=\"two\\nlines\"\n", EnvFormat.parse("A=old\n").set("A", "two\nlines").render())
    }

    @Test fun `added variables go at the end with the file's line ending`() {
        assertEquals("A=1\r\nB=2\r\n", EnvFormat.parse("A=1\r\n").set("B", "2").render())
        assertEquals("A=1\nB=2\n", EnvFormat.parse("A=1").set("B", "2").render())
        assertEquals("# only a comment\nB=2\n", EnvFormat.parse("# only a comment").set("B", "2").render())
        assertEquals("B=2\n", EnvFormat.parse("").set("B", "2").render())
    }

    @Test fun `the last of duplicate keys is the one edited`() {
        val doc = EnvFormat.parse("A=1\nA=2\n")
        assertEquals("2", doc.value("A"))
        assertEquals("A=1\nA=3\n", doc.set("A", "3").render())
    }

    @Test fun `remove drops the variable's lines and nothing else`() {
        val doc = EnvFormat.parse(sample).remove("MULTI")
        assertEquals(sample.replace("MULTI=\"line one\nline two\"\n", ""), doc.render())
        assertFalse("MULTI" in doc)
    }

    @Test fun `rename keeps the line in place`() {
        val doc = EnvFormat.parse("# c\nexport e=1\nB=2\n").replace("e", "E2", "5")
        assertEquals("# c\nexport E2=5\nB=2\n", doc.render())
    }

    @Test fun `every value survives a write and a read`() {
        val values = listOf(
            "", "plain", "with space", " lead", "trail ", "a#b", "a #b", "#start", "q\"uote", "it's", "back`tick",
            "back\\slash", "multi\nline", "cr\r\nlf", "\$VAR", "\${VAR}", "tab\there", "\\n literal", "émoji ✓", "=equals=",
        )
        for (previous in listOf("A=x\n", "A='x'\n", "A=\"x\"\n", "A=`x`\n", "export A=x # c\n", "A=x\r\n")) {
            for (v in values) {
                val written = EnvFormat.parse(previous).set("A", v).render()
                val back = EnvFormat.parse(written)
                assertEquals("$previous → <$v> as $written", v, back.value("A"))
                assertEquals(1, back.entries.size)
            }
        }
    }

    @Test fun `variables missing from the example are listed in its order`() {
        val env = EnvFormat.parse("B=1\nA=2\n")
        val example = EnvFormat.parse("# needed\nA=\nC=changeme\nB=\nD=x\nC=again\n")
        assertEquals(listOf("C", "D"), EnvFiles.missing(env, example).map { it.key })
        assertEquals("changeme", EnvFiles.missing(env, example).first().value)
    }

    @Test fun `gitignore patterns that cover the env file`() {
        assertTrue(EnvGitignore.ignores(".env\n", ".env"))
        assertTrue(EnvGitignore.ignores("/.env\n", ".env"))
        assertTrue(EnvGitignore.ignores("node_modules/\n.env*\n", ".env.local"))
        assertTrue(EnvGitignore.ignores("**/.env\n", ".env"))
        assertTrue(EnvGitignore.ignores("*.local\n", ".env.local"))
        assertFalse(EnvGitignore.ignores(".env.local\n", ".env"))
        assertFalse(EnvGitignore.ignores(".env\n!.env\n", ".env"))
        assertFalse(EnvGitignore.ignores("# .env\n", ".env"))
        assertFalse(EnvGitignore.ignores("config/.env\n", ".env"))
        assertFalse(EnvGitignore.ignores(".env/\n", ".env"))
        assertFalse(EnvGitignore.ignores("", ".env"))
        assertTrue(EnvGitignore.ignores(".env\r\n", ".env"))
    }

    @Test fun `the gitignore fix adds one anchored line`() {
        assertEquals("/.env\n", EnvGitignore.withEntry("", ".env"))
        assertEquals("node_modules\n/.env\n", EnvGitignore.withEntry("node_modules", ".env"))
        assertEquals("a\r\n/.env\r\n", EnvGitignore.withEntry("a\r\n", ".env"))
        assertTrue(EnvGitignore.ignores(EnvGitignore.withEntry("x\n", ".env.local"), ".env.local"))
    }

    @Test fun `keys are validated`() {
        assertTrue(EnvFormat.isValidKey("DATABASE_URL"))
        assertTrue(EnvFormat.isValidKey("vite.app-name"))
        assertFalse(EnvFormat.isValidKey("1ABC"))
        assertFalse(EnvFormat.isValidKey("A B"))
        assertFalse(EnvFormat.isValidKey(""))
    }
}
