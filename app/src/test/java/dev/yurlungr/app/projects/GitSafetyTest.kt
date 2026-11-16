package dev.yurlungr.app.projects

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class GitSafetyTest {
    @Test fun `paths are literal, not globs`() {
        // Otherwise `discard app/[id]/page.tsx` also resets app/i/page.tsx.
        assertEquals("1", Git.ENV["GIT_LITERAL_PATHSPECS"])
        assertEquals("0", Git.ENV["GIT_TERMINAL_PROMPT"])
    }

    @Test fun `a stash only counts when refs-stash moved`() {
        assertTrue(Git.stashCreated(before = null, after = "a".repeat(40)))
        assertTrue(Git.stashCreated(before = "a".repeat(40), after = "b".repeat(40)))
        // "No local changes to save": exit 0, the old stash still on top.
        assertFalse(Git.stashCreated(before = "a".repeat(40), after = "a".repeat(40)))
        assertFalse(Git.stashCreated(before = null, after = null))
    }

    @Test fun `conflict markers are attributed to the right file`() {
        val diff = """
            diff --git a/one.txt b/one.txt
            index 1..2 100644
            --- a/one.txt
            +++ b/one.txt
            @@ -1,0 +2 @@
            +++ not a header, an added line starting with "++ "
            diff --git "a/caf\303\251.txt" "b/caf\303\251.txt"
            index 1..2 100644
            --- "a/caf\303\251.txt"
            +++ "b/caf\303\251.txt"
            @@ -1,0 +1,3 @@
            +<<<<<<< HEAD
            +x
            +>>>>>>> other
        """.trimIndent()
        assertEquals(setOf("café.txt"), Git.filesWithMarkers(diff))
    }

    @Test fun `a content line that looks like a header doesn't rename the file`() {
        val diff = """
            diff --git a/a.md b/a.md
            --- a/a.md
            +++ b/a.md
            @@ -0,0 +1,2 @@
            +++ b/other.md
            +<<<<<<< ours
        """.trimIndent()
        assertEquals(setOf("a.md"), Git.filesWithMarkers(diff))
    }

    @Test fun `C-quoted paths are unquoted`() {
        assertEquals("plain.txt", Git.unquoteC("plain.txt"))
        assertEquals("b/café.txt", Git.unquoteC("\"b/caf\\303\\251.txt\""))
        assertEquals("b/a\"b\\c\td", Git.unquoteC("\"b/a\\\"b\\\\c\\td\""))
    }

    @Test fun `task names are shell-quoted unless plain`() {
        assertEquals("build", ProjectTasks.arg("build"))
        assertEquals("test:unit", ProjectTasks.arg("test:unit"))
        assertEquals("'x; rm -rf ~'", ProjectTasks.arg("x; rm -rf ~"))
        assertEquals("'\$(id)'", ProjectTasks.arg("\$(id)"))
        assertEquals("'it'\\''s'", ProjectTasks.arg("it's"))
    }

    @Test fun `package json scripts with odd names run as one argument`() {
        val root = kotlin.io.path.createTempDirectory("tasks").toFile()
        try {
            File(root, "package.json").writeText("""{"scripts": {"dev": "vite", "a b; echo pwned": "x"}}""")
            val tasks = ProjectTasks.detect(root, listOf(DetectedStack("node", "Node.js", root, "package.json")))
            val commands = tasks.map { it.command }
            assertTrue(commands.toString(), "npm run dev" in commands)
            assertTrue(commands.toString(), "npm run 'a b; echo pwned'" in commands)
        } finally {
            root.deleteRecursively()
        }
    }
}
