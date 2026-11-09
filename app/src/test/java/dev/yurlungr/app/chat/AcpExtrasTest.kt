package dev.yurlungr.app.chat

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AcpExtrasTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun json(s: String) = Json.parseToJsonElement(s)

    @Test
    fun `config options - select, grouped select and boolean`() {
        val opts = AcpExtras.parseConfigOptions(json("""
            [
              {"id":"model","name":"Model","category":"model","type":"select","currentValue":"sonnet",
               "options":[{"value":"sonnet","name":"Sonnet"},{"value":"opus","name":"Opus","description":"Smartest"}]},
              {"id":"effort","name":"Effort","type":"select","currentValue":"high",
               "options":[{"group":"g1","name":"Levels","options":[{"value":"low","name":"Low"},{"value":"high","name":"High"}]}]},
              {"id":"web","name":"Web search","type":"boolean","currentValue":true},
              {"name":"no id, skipped"}
            ]
        """))
        assertEquals(3, opts.size)
        assertEquals("Sonnet", opts[0].currentName)
        assertEquals(listOf("sonnet", "opus"), opts[0].choices.map { it.value })
        assertEquals("Smartest", opts[0].choices[1].description)
        assertEquals(listOf("low", "high"), opts[1].choices.map { it.value })
        assertTrue(opts[2].isBoolean)
        assertEquals(true, opts[2].booleanValue)
        assertNull(opts[2].currentValue)
    }

    @Test
    fun `config options survive the round trip used for mirroring`() {
        val original = AcpExtras.parseConfigOptions(json("""
            [{"id":"model","name":"Model","category":"model","type":"select","currentValue":"b",
              "options":[{"value":"a","name":"A"},{"value":"b","name":"B"}]},
             {"id":"x","name":"X","type":"boolean","currentValue":false}]
        """))
        val back = AcpExtras.parseConfigOptions(AcpExtras.encodeConfigOptions(original))
        assertEquals(original, back)
    }

    @Test
    fun `commands strip a leading slash and keep input hints`() {
        val cmds = AcpExtras.parseCommands(json("""
            [{"name":"/review","description":"Review changes"},{"name":"compact","description":"Summarize","input":{"hint":"focus"}}]
        """))
        assertEquals(listOf("review", "compact"), cmds.map { it.name })
        assertEquals("focus", cmds[1].hint)
        assertEquals(cmds, AcpExtras.parseCommands(AcpExtras.encodeCommands(cmds)))
    }

    @Test
    fun `usage and its label`() {
        val u = AcpExtras.parseUsage(json("""{"used":12345,"size":200000,"cost":{"amount":0.0412,"currency":"USD"}}""").jsonObject)!!
        assertEquals("12k / 200k tokens · $0.04", AcpExtras.usageLabel(u))
        assertEquals(u, AcpExtras.parseUsage(AcpExtras.encodeUsage(u)))
        assertNull(AcpExtras.parseUsage(json("""{"size":1}""").jsonObject))
        assertEquals("900 / 2k tokens", AcpExtras.usageLabel(Usage(900, 2000, null, null)))
    }

    @Test
    fun `prompt capabilities default to text only`() {
        assertEquals(PromptCaps(), AcpExtras.parseCaps(null))
        val caps = AcpExtras.parseCaps(json("""{"promptCapabilities":{"image":true,"embeddedContext":false}}""").jsonObject)
        assertTrue(caps.image)
        assertFalse(caps.embeddedContext)
    }

    @Test
    fun `attachments become embedded text or resource links, saved in a self-ignoring folder`() {
        val cwd = tmp.newFolder("proj")
        val text = Attachment("notes.md", "text/markdown", "# hi".toByteArray())
        val binary = Attachment("app.apk", "application/vnd.android.package-archive", byteArrayOf(0, 1, 2))
        val image = Attachment("shot.png", "image/png", byteArrayOf(1, 2, 3))
        val blocks = Attachments.toPromptBlocks(cwd, listOf(text, binary, image), PromptCaps(image = false, embeddedContext = true))

        fun JsonObject.type() = (this["type"] as JsonPrimitive).content
        assertEquals(listOf("resource", "resource_link", "resource_link"), blocks.map { it.type() })
        val resource = blocks[0]["resource"]!!.jsonObject
        assertEquals("# hi", (resource["text"] as JsonPrimitive).content)
        val attachmentDir = java.io.File(cwd, ".yurlungr/attachments")
        assertTrue((resource["uri"] as JsonPrimitive).content.startsWith("file://${attachmentDir.path}${java.io.File.separator}"))
        assertEquals("*\n", java.io.File(cwd, ".yurlungr/.gitignore").readText())
        assertEquals(3, java.io.File(cwd, ".yurlungr/attachments").listFiles()!!.size)
    }

    @Test
    fun `without embedded context support, text files are links too`() {
        val cwd = tmp.newFolder("p2")
        val blocks = Attachments.toPromptBlocks(cwd, listOf(Attachment("a.kt", "text/plain", "x".toByteArray())), PromptCaps())
        assertEquals("resource_link", (blocks.single()["type"] as JsonPrimitive).content)
    }

    @Test
    fun `user message attachments are saved`() {
        val chat = SavedChat("c", "Claude", "/p", "t", null, 0, listOf(ChatItem.User("look", listOf("shot.png", "log.txt"))))
        val back = ChatStore.decode(Json.parseToJsonElement(ChatStore.encode(chat).toString()).jsonObject)
        assertEquals(listOf("shot.png", "log.txt"), (back.items.single() as ChatItem.User).attachments)
    }
}
