package dev.yurlungr.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatGroupsTest {
    private data class C(val name: String, val pinned: Boolean, val at: Long, val folder: String)

    private val day = 24 * 60 * 60 * 1000L
    private val now = 1_800_000_000_000L
    private val chats = listOf(
        C("old", false, now - 30 * day, "~/a"),
        C("pin", true, now - 40 * day, "~/b"),
        C("today", false, now, "~/b"),
    )

    private fun names(groups: List<Pair<String, List<C>>>) = groups.map { (label, list) -> label to list.map { it.name } }

    @Test fun `pinned chats come first, the rest by day`() {
        val g = groupChats(chats, byProject = false, { it.pinned }, { it.at }, { it.folder }, now)
        assertEquals(listOf("Pinned" to listOf("pin"), "Today" to listOf("today"), "Older" to listOf("old")), names(g))
    }

    @Test fun `by project groups the rest by folder, most recent folder first`() {
        val g = groupChats(chats, byProject = true, { it.pinned }, { it.at }, { it.folder }, now)
        assertEquals(listOf("Pinned" to listOf("pin"), "~/b" to listOf("today"), "~/a" to listOf("old")), names(g))
    }
}
