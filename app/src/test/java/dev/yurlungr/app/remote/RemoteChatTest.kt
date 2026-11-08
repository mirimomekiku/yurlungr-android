package dev.yurlungr.app.remote

import dev.yurlungr.app.agents.Agent
import dev.yurlungr.app.chat.ChatItem
import dev.yurlungr.app.chat.ChatState
import dev.yurlungr.app.chat.ChatStore
import dev.yurlungr.app.chat.PermissionOption
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteChatTest {
    private val sent = mutableListOf<JsonObject>()
    private val chat = RemoteChat("chat-1", Agent.Claude, send = { sent += it })

    private fun wire(item: ChatItem) = ChatStore.encodeItem(item, wire = true)

    private fun snapshot(vararg items: ChatItem, state: String = "ready") = buildJsonObject {
        put("t", "snapshot")
        put("chat", buildJsonObject {
            put("id", "chat-1")
            put("agent", "Codex")
            put("folder", "app")
            put("title", "Codex · app")
            put("cwd", "~/projects/app")
            put("state", state)
            put("modes", JsonArray(emptyList()))
            put("items", JsonArray(items.map(::wire)))
        })
    }

    @Test
    fun `snapshot replaces everything`() {
        chat.apply(snapshot(ChatItem.User("hi"), ChatItem.AgentText("m").apply { text = "hello" }))
        assertEquals(Agent.Codex, chat.agent)
        assertEquals("~/projects/app", chat.cwdLabel)
        assertEquals(ChatState.Ready, chat.state)
        assertEquals(2, chat.items.size)
        assertEquals("hello", (chat.items[1] as ChatItem.AgentText).text)

        chat.apply(snapshot(ChatItem.User("only")))
        assertEquals(1, chat.items.size)
    }

    @Test
    fun `streamed text appends to the right item`() {
        val hostText = ChatItem.AgentText("m")
        chat.apply(snapshot(hostText))
        chat.apply(buildJsonObject { put("t", "append"); put("chat", "chat-1"); put("k", hostText.key); put("text", "Hel") })
        chat.apply(buildJsonObject { put("t", "append"); put("chat", "chat-1"); put("k", hostText.key); put("text", "lo") })
        assertEquals("Hello", (chat.items.single() as ChatItem.AgentText).text)
    }

    @Test
    fun `item updates merge into the existing item instead of duplicating`() {
        val tool = ChatItem.ToolCall("t1").apply { title = "Run tests"; status = "in_progress" }
        chat.apply(snapshot(tool))
        tool.status = "completed"
        chat.apply(buildJsonObject { put("t", "item"); put("chat", "chat-1"); put("item", wire(tool)) })
        val mirrored = chat.items.single() as ChatItem.ToolCall
        assertEquals("completed", mirrored.status)

        chat.apply(buildJsonObject { put("t", "item"); put("chat", "chat-1"); put("item", wire(ChatItem.Notice("new"))) })
        assertEquals(2, chat.items.size)
    }

    @Test
    fun `state changes apply, failures carry their message`() {
        chat.apply(buildJsonObject { put("t", "state"); put("chat", "chat-1"); put("state", "running") })
        assertEquals(ChatState.Running, chat.state)
        chat.apply(buildJsonObject { put("t", "state"); put("chat", "chat-1"); put("state", "failed"); put("message", "boom") })
        assertEquals(ChatState.Failed("boom"), chat.state)
    }

    @Test
    fun `answering a permission sends the host's item key`() {
        val option = PermissionOption("allow", "Allow once", "allow_once")
        val hostPermission = ChatItem.Permission("Run gradle", listOf(option), CompletableDeferred())
        chat.apply(snapshot(hostPermission))
        val mirrored = chat.items.single() as ChatItem.Permission
        assertNull(mirrored.resolved)
        assertEquals(listOf(option), mirrored.options)

        chat.answerPermission(mirrored, option)
        assertEquals("Allow once", mirrored.resolved)
        val cmd = sent.last()
        assertEquals("permission", (cmd["t"] as JsonPrimitive).content)
        assertEquals(hostPermission.key.toString(), (cmd["k"] as JsonPrimitive).content)
        assertEquals("allow", (cmd["option"] as JsonPrimitive).content)

        // Answering twice does nothing.
        sent.clear()
        chat.answerPermission(mirrored, option)
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `send ignores blank input and trims`() {
        chat.send("   ")
        assertTrue(sent.isEmpty())
        chat.send("  build it  ")
        assertEquals("build it", (sent.single()["text"] as JsonPrimitive).content)
    }

    @Test
    fun `addresses get the default port`() {
        assertEquals("100.64.1.2:8765", RemoteConnection.withPort("100.64.1.2"))
        assertEquals("rog:9000", RemoteConnection.withPort("rog:9000"))
        assertEquals("rog-phone:8765", RemoteConnection.withPort("ws://rog-phone/"))
    }
}
