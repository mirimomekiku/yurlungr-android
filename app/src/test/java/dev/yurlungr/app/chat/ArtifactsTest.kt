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

    @Test fun `kinds follow the file extension`() {
        assertEquals(ArtifactKind.Image, ArtifactKind.of("shot.PNG"))
        assertEquals(ArtifactKind.Web, ArtifactKind.of("index.html"))
        assertEquals(ArtifactKind.Web, ArtifactKind.of("logo.svg"))
        assertEquals(ArtifactKind.Markdown, ArtifactKind.of("README.md"))
        assertEquals(ArtifactKind.Code, ArtifactKind.of("Makefile"))
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
