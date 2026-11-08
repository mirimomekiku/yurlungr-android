package dev.yurlungr.app.chat

import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatStoreTest {

    private fun roundTrip(chat: SavedChat): SavedChat {
        val text = ChatStore.encode(chat).toString()
        return ChatStore.decode(Json.parseToJsonElement(text).jsonObject)
    }

    private fun chat(vararg items: ChatItem) = SavedChat(
        id = "c1",
        agent = "Claude",
        cwd = "/data/data/com.termux/files/home/projects/scratch",
        title = "Claude · scratch",
        acpSessionId = "acp-123",
        updatedAt = 1_700_000_000_000,
        items = items.toList(),
    )

    @Test
    fun `metadata survives`() {
        val back = roundTrip(chat())
        assertEquals("c1", back.id)
        assertEquals("Claude", back.agent)
        assertEquals("acp-123", back.acpSessionId)
        assertEquals(1_700_000_000_000, back.updatedAt)
        assertEquals("Claude · scratch", back.title)
    }

    @Test
    fun `missing session id stays null`() {
        assertNull(roundTrip(chat().copy(acpSessionId = null)).acpSessionId)
    }

    @Test
    fun `every item type round-trips`() {
        val agent = ChatItem.AgentText("m1").apply { text = "Here is `code`\n```kt\nval x = 1\n```" }
        val thought = ChatItem.Thought(null).apply { text = "thinking" }
        val tool = ChatItem.ToolCall("t1").apply {
            title = "Edit main.kt"
            kind = "edit"
            status = "completed"
            locations = listOf("main.kt:3")
            content = listOf(
                ToolContent.Diff("main.kt", "a\nb", "a\nc"),
                ToolContent.Text("ok"),
                ToolContent.Terminal("term-1"),
            )
        }
        val plan = ChatItem.Plan().apply { entries = listOf(PlanEntry("Write tests", "in_progress", "high")) }
        val permission = ChatItem.Permission("Run gradle", emptyList(), CompletableDeferred()).apply { resolved = "Allow once" }
        val items = roundTrip(
            chat(ChatItem.User("hi"), agent, thought, tool, plan, permission, ChatItem.Notice("Stopped", isError = true)),
        ).items

        assertEquals(7, items.size)
        assertEquals("hi", (items[0] as ChatItem.User).text)
        assertEquals(agent.text, (items[1] as ChatItem.AgentText).text)
        assertEquals("thinking", (items[2] as ChatItem.Thought).text)

        val t = items[3] as ChatItem.ToolCall
        assertEquals("Edit main.kt", t.title)
        assertEquals("edit", t.kind)
        assertEquals("completed", t.status)
        assertEquals(listOf("main.kt:3"), t.locations)
        assertEquals(ToolContent.Diff("main.kt", "a\nb", "a\nc"), t.content[0])
        assertEquals(ToolContent.Text("ok"), t.content[1])
        assertEquals(ToolContent.Terminal("term-1"), t.content[2])

        assertEquals(listOf(PlanEntry("Write tests", "in_progress", "high")), (items[4] as ChatItem.Plan).entries)
        assertEquals("Allow once", (items[5] as ChatItem.Permission).resolved)
        val notice = items[6] as ChatItem.Notice
        assertEquals("Stopped", notice.text)
        assertTrue(notice.isError)
    }

    @Test
    fun `work interrupted by a restart is marked as such`() {
        val running = ChatItem.ToolCall("t2").apply { status = "in_progress" }
        val pending = ChatItem.Permission("Delete files", emptyList(), CompletableDeferred())
        val items = roundTrip(chat(running, pending)).items
        assertEquals("interrupted", (items[0] as ChatItem.ToolCall).status)
        assertEquals("Interrupted", (items[1] as ChatItem.Permission).resolved)
    }

    @Test
    fun `unknown item types are skipped, not fatal`() {
        val json = ChatStore.encode(chat(ChatItem.User("a"))).toString()
            .replace("\"items\":[", "\"items\":[{\"t\":\"from_the_future\"},")
        val back = ChatStore.decode(Json.parseToJsonElement(json).jsonObject)
        assertEquals(1, back.items.size)
    }
}
