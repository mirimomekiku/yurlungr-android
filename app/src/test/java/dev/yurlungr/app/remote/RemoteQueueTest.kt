package dev.yurlungr.app.remote

import dev.yurlungr.app.agents.Agent
import dev.yurlungr.app.chat.ChatState
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Messages sent from a paired phone while the host's agent works are queued, not lost. */
class RemoteQueueTest {
    private val sent = mutableListOf<JsonObject>()
    private val chat = RemoteChat("chat-1", Agent.Claude, send = { sent += it })

    private fun state(s: String) = chat.apply(buildJsonObject { put("t", "state"); put("chat", "chat-1"); put("state", s) })

    private fun meta(queued: List<String>?) = chat.apply(buildJsonObject {
        put("t", "meta"); put("chat", "chat-1"); put("title", "T")
        if (queued != null) put("queued", JsonArray(queued.map(::JsonPrimitive)))
    })

    private val JsonObject.type get() = this["t"]!!.jsonPrimitive.content
    private val JsonObject.text get() = this["text"]!!.jsonPrimitive.content

    @Test
    fun `a host that queues gets enqueue and its queue is mirrored`() {
        meta(emptyList())
        state("running")
        chat.enqueue("next please", emptyList())
        assertEquals("enqueue", sent.single().type)
        assertEquals("next please", sent.single().text)

        meta(listOf("next please"))
        assertEquals(listOf("next please"), chat.queued.map { it.text })
        chat.removeQueued(0)
        assertEquals("unqueue", sent.last().type)
        assertEquals("next please", sent.last().text)
        chat.sendQueuedNow()
        assertEquals("sendNow", sent.last().type)
    }

    @Test
    fun `an older host's chat holds messages until it's ready`() {
        state("running")
        chat.enqueue("first", emptyList())
        chat.enqueue("second", emptyList())
        assertTrue(sent.isEmpty())
        assertEquals(listOf("first", "second"), chat.queued.map { it.text })

        state("ready")
        assertEquals(listOf("send"), sent.map { it.type })
        assertEquals("first", sent.single().text)
        assertEquals(listOf("second"), chat.queued.map { it.text })
    }

    @Test
    fun `held messages move to a host that turns out to queue`() {
        state("running")
        chat.enqueue("held", emptyList())
        meta(emptyList())
        assertEquals("enqueue", sent.single().type)
        assertEquals("held", sent.single().text)
    }

    @Test
    fun `idle chats send at once`() {
        state("ready")
        chat.enqueue("hi", emptyList())
        assertEquals("send", sent.single().type)
        assertEquals(ChatState.Ready, chat.state)
    }

    @Test
    fun `risky select values need allow remote skip`() {
        for (v in listOf("bypassPermissions", "danger-full-access", "yolo", "acceptEdits", "auto-edit", "autoEdit", "full-auto", "dontAsk")) {
            assertTrue(v, RemoteProtocol.isRiskyConfigValue("mode", "Mode", "mode", v))
            assertTrue(v, RemoteProtocol.isRiskyConfigValue("model", "Model", "model", v))
        }
        assertTrue(RemoteProtocol.isRiskyConfigValue("approval_policy", "Approval policy", null, "never"))
        assertFalse(RemoteProtocol.isRiskyConfigValue("reasoning", "Reasoning summary", null, "never"))
        assertFalse(RemoteProtocol.isRiskyConfigValue("model", "Model", "model", "claude-sonnet"))
        assertFalse(RemoteProtocol.isRiskyConfigValue("mode", "Mode", "mode", "plan"))
        assertFalse(RemoteProtocol.isRiskyConfigValue("mode", "Mode", "mode", "default"))
    }
}
