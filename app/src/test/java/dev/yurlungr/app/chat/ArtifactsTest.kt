package dev.yurlungr.app.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtifactsTest {
    private fun edit(path: String, old: String?) = ChatItem.ToolCall("t-$path").apply {
        kind = "edit"
        content = listOf(ToolContent.Diff(path, old, "new"))
    }

    @Test fun `files are collected once, by the prompt that last changed them`() {
        val items = listOf(
            ChatItem.User("make a page"),
            edit("/p/index.html", null),
            edit("/p/a.png", null),
            ChatItem.User("tweak it"),
            edit("/p/index.html", "old"),
            ChatItem.Notice("ignored"),
        )
        val found = Artifacts.collect(items)
        assertEquals(listOf("/p/index.html", "/p/a.png"), found.map { it.path })
        val page = found.first { it.name == "index.html" }
        assertTrue("created in the first prompt, so still new", page.isNew)
        assertEquals(2, page.turn)
        assertEquals(1, found.first { it.name == "a.png" }.turn)
    }

    @Test fun `a file only edited is not new, and relative diff paths are skipped`() {
        val found = Artifacts.collect(listOf(ChatItem.User("x"), edit("/p/Main.kt", "old"), edit("rel/other.kt", null)))
        assertEquals(1, found.size)
        assertFalse(found.single().isNew)
    }

    @Test fun `merging keeps one entry per file, new if first seen new, in the latest prompt`() {
        val snapshots = listOf(Artifact("/p/chart.png", isNew = true, turn = 1), Artifact("/p/app.py", isNew = false, turn = 2))
        val diffs = listOf(Artifact("/p/app.py", isNew = true, turn = 1), Artifact("/p/chart.png", isNew = false, turn = 3))
        val merged = Artifacts.merge(snapshots, diffs).associateBy { it.path }
        assertEquals(2, merged.size)
        assertEquals(3, merged.getValue("/p/chart.png").turn)
        assertTrue(merged.getValue("/p/chart.png").isNew)
        assertEquals(2, merged.getValue("/p/app.py").turn)
        assertTrue("created in prompt 1 by a diff", merged.getValue("/p/app.py").isNew)
    }

    @Test fun `search snippets are one line around the match`() {
        val text = "first line\nthe quick brown fox jumps over the lazy dog and keeps running far away from here"
        val at = text.indexOf("fox")
        val s = ChatSearch.snippet(text, at, 3)
        assertTrue(s.contains("fox"))
        assertFalse(s.contains('\n'))
        assertTrue("short excerpts keep the start and flatten newlines", s.startsWith("first line the quick"))
        assertTrue("long text is cut with an ellipsis", s.endsWith("…"))
    }
}
