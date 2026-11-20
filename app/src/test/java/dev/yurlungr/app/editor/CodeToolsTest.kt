package dev.yurlungr.app.editor

import dev.yurlungr.app.ui.Feedback
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CodeToolsTest {
    private val conflicted = """
        keep
        <<<<<<< HEAD
        ours line
        ||||||| base
        base line
        =======
        theirs line
        >>>>>>> feature
        after
    """.trimIndent() + "\n"

    @Test fun `conflicts parse with their base, and render back`() {
        assertTrue(Merge.hasConflicts(conflicted))
        val parts = Merge.parse(conflicted)
        val c = parts.filterIsInstance<MergePart.Conflict>().single()
        assertEquals("ours line", c.ours)
        assertEquals("base line", c.base)
        assertEquals("theirs line", c.theirs)
        assertEquals("HEAD", c.oursLabel)
        assertEquals("keep\ntheirs line\nafter\n", Merge.render(parts, listOf("theirs line")))
        assertEquals("keep\nours line\ntheirs line\nafter\n", Merge.render(parts, listOf("ours line\ntheirs line")))
        assertEquals("keep\nafter\n", Merge.render(parts, listOf("")))
        // Unresolved: the markers stay, so nothing is lost.
        assertEquals(conflicted, Merge.render(parts, listOf(null)))
    }

    @Test fun `text without markers is one plain part`() {
        assertFalse(Merge.hasConflicts("a\n=======\nb\n"))
        assertEquals(listOf<MergePart>(MergePart.Text("a\nb\n")), Merge.parse("a\nb\n"))
    }

    @Test fun `snippets put the cursor at the bar, and templates suit the file type`() {
        assertEquals("fun name() {\n    \n}" to 4, Snippets.expand("fun |name() {\n    \n}"))
        assertEquals("a | b" to 5, Snippets.expand("a || b"))
        assertTrue(Snippets.forFile("App.tsx").isNotEmpty())
        assertTrue(Snippets.forFile("x.unknown").isEmpty())
        assertTrue(Snippets.template("src/UserCard.tsx").contains("export function UserCard()"))
        assertTrue(Snippets.template("date-utils.kt").contains("class Dateutils") || Snippets.template("date-utils.kt").startsWith("class "))
        assertTrue(Snippets.template("main.go").startsWith("package main"))
        assertEquals("", Snippets.template("notes.xyz"))
    }

    @Test fun `long jobs on a low or hot phone are flagged`() {
        assertNull(Feedback.powerConcern(Feedback.Power(80, false, 30f)))
        assertNull(Feedback.powerConcern(Feedback.Power(10, true, 30f)))
        assertNotNull(Feedback.powerConcern(Feedback.Power(10, false, 30f)))
        assertNotNull(Feedback.powerConcern(Feedback.Power(90, true, 44f)))
        assertTrue(Feedback.isHeavy("cd /p && ./gradlew assembleDebug"))
        assertTrue(Feedback.isHeavy("bash \"\$PREFIX/libexec/yurlungr/rust-setup.sh\""))
        assertFalse(Feedback.isHeavy("cd /p && ls"))
    }

    @Test fun `language server helpers`() {
        assertTrue(LspClient.uri(File("/data/x/a b.kt")).let { it.startsWith("file:///") && it.endsWith("a%20b.kt") })
        assertTrue(LspServers.candidates(File("main.rs")).contains("rust-analyzer"))
        assertTrue(LspServers.candidates(File("app.py")).containsAll(listOf("pyright", "pylsp", "ruff")))
        assertTrue(LspServers.candidates(File("notes.txt")).isEmpty())
    }
}
