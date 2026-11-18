package dev.yurlungr.app.chat

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatFeaturesTest {
    private fun transcript(): List<ChatItem> = listOf(
        ChatItem.User("Fix the login bug", listOf("screen.png")),
        ChatItem.Thought("t").apply { text = "Looking at auth" },
        ChatItem.ToolCall("1").apply {
            title = "Edit Login.kt"; kind = "edit"; status = "completed"
            locations = listOf("/p/app/Login.kt:12")
            content = listOf(ToolContent.Diff("/p/app/Login.kt", "a\nold\nz", "a\nnew\nz"))
        },
        ChatItem.ToolCall("2").apply {
            title = "Run tests"; kind = "execute"; status = "failed"
            content = listOf(ToolContent.Text("```\nok\nFAILED LoginTest"))
        },
        ChatItem.AgentText("m").apply { text = "Fixed it." },
        ChatItem.Notice("Stopped"),
    )

    @Test fun `markdown export reads as a conversation`() {
        val md = ChatExport.markdown("Login", "Claude", "~/projects/app", transcript(), exportedAt = 0)
        assertTrue(md, md.startsWith("# Login\n"))
        assertTrue(md.contains("## You\n\nFix the login bug\n\n_Attached: screen.png_"))
        assertTrue(md.contains("## Claude"))
        assertTrue(md.contains("<details><summary>Thinking</summary>"))
        assertTrue(md.contains("**Edit** · Edit Login.kt · _completed_"))
        assertTrue(md.contains("```diff\n@@ /p/app/Login.kt:2\n-old\n+new\n```"))
        // Output holding a fence gets a longer fence, so it can't break out.
        assertTrue(md.contains("````\n```\nok\nFAILED LoginTest\n````"))
        assertTrue(md.contains("Fixed it."))
        assertTrue(md.contains("> _Stopped_"))
    }

    @Test fun `json export carries every item`() {
        val obj = Json.parseToJsonElement(ChatExport.json("Login", "Claude", "~/p", transcript(), exportedAt = 5)).jsonObject
        assertEquals("yurlungr-chat", obj["format"]!!.jsonPrimitive.content)
        assertEquals(6, obj["items"]!!.jsonArray.size)
        assertEquals("user", obj["items"]!!.jsonArray[0].jsonObject["t"]!!.jsonPrimitive.content)
    }

    @Test fun `export file names are safe`() {
        assertEquals("Fix a b.md", ChatExport.fileName("Fix: a/b?", "md"))
        assertEquals("chat.json", ChatExport.fileName("  ***  ", "json"))
    }

    @Test fun `digest keeps the newest exchanges and the files they changed`() {
        val d = ChatExport.digest(transcript(), "~/projects/app")
        assertTrue(d, d.contains("Me: Fix the login bug"))
        assertTrue(d.contains("You: Fixed it."))
        assertTrue(d.contains("Files changed: Login.kt"))
        val many = (1..200).flatMap { listOf(ChatItem.User("question $it " + "x".repeat(200)), ChatItem.AgentText(null).apply { text = "answer $it" }) }
        val short = ChatExport.digest(many, "~/p", maxChars = 3000)
        assertTrue(short.length <= 3000)
        assertTrue(short.contains("question 200"))
        assertFalse(short.contains("question 1 "))
        assertTrue(short.contains("earlier exchanges left out"))
    }

    @Test fun `long output folds and finds its first error`() {
        val lines = (1..100).map { "line $it" }.toMutableList().apply { this[60] = "src/A.kt:3: error: unresolved reference" }
        assertEquals(60, OutputFold.firstError(lines))
        val (head, tail) = OutputFold.fold(lines)!!
        assertEquals(0..3, head)
        assertEquals(90..99, tail)
        assertNull(OutputFold.fold(lines.take(10)))
        assertEquals(57..72, OutputFold.around(60, 100))
        assertFalse(OutputFold.isError("BUILD SUCCESSFUL, 0 errors"))
        assertFalse(OutputFold.isError("errorHandler.kt compiled"))
        assertTrue(OutputFold.isError("Traceback (most recent call last):"))
        assertTrue(OutputFold.isError("1 test FAILED"))
    }

    @Test fun `display math becomes a math fence, code is left alone`() {
        assertEquals("Area:\n```math\n\\pi r^2\n```\ndone", RichBlocks.fenceDisplayMath("Area:\n$$\\pi r^2$$\ndone"))
        assertEquals("```math\na \\\\\nb\n```", RichBlocks.fenceDisplayMath("$$\na \\\\\nb\n$$"))
        assertEquals("```math\nx\n```", RichBlocks.fenceDisplayMath("\\[\nx\n\\]"))
        val code = "```sh\necho $$\n$$ not math\n```"
        assertEquals(code, RichBlocks.fenceDisplayMath(code))
        // Unclosed: left as written.
        assertEquals("costs $$ 5", RichBlocks.fenceDisplayMath("costs $$ 5"))
        assertEquals(RichKind.Mermaid, RichBlocks.kindOf("Mermaid"))
        assertEquals(RichKind.Math, RichBlocks.kindOf("latex"))
        assertNull(RichBlocks.kindOf("kotlin"))
    }

    @Test fun `the page escapes its source`() {
        val page = RichBlocks.page(RichKind.Mermaid, "graph TD; A-->B</script><script>alert(1)", dark = true, fg = "#FFFFFF")
        assertFalse(page.contains("</script><script>alert"))
        assertTrue(page.contains("theme:'dark'"))
        assertTrue(page.contains("securityLevel:'strict'"))
    }

    @Test fun `context meter shows from 60 percent and finds compact`() {
        assertNull(ContextUse.fraction(null))
        assertNull(ContextUse.fraction(Usage(10, 0, null, null)))
        assertEquals(0.5f, ContextUse.fraction(Usage(50, 100, null, null))!!, 0.001f)
        assertEquals("compact", ContextUse.compactCommand(listOf(SlashCommand("review", "", null), SlashCommand("compact", "", null)))?.name)
        assertNull(ContextUse.compactCommand(listOf(SlashCommand("review", "", null))))
    }

    @Test fun `pinned and named chats survive a save`() {
        val saved = SavedChat("id", "Claude", "/p", "My name", null, 1, emptyList(), pinned = true, titleLocked = true)
        val back = ChatStore.decode(ChatStore.encode(saved))
        assertTrue(back.pinned)
        assertTrue(back.titleLocked)
        val old = ChatStore.decode(ChatStore.encode(saved.copy(pinned = false, titleLocked = false)))
        assertFalse(old.pinned)
        assertFalse(old.titleLocked)
    }

    @Test fun `notification buttons are allow once and deny once`() {
        val options = listOf(
            PermissionOption("a", "Always allow", "allow_always"),
            PermissionOption("b", "Allow", "allow_once"),
            PermissionOption("c", "Reject", "reject_once"),
        )
        assertEquals(listOf("b", "c"), ChatNotifications.buttons(options).map { it.optionId })
        assertEquals(listOf("a"), ChatNotifications.buttons(options.take(1)).map { it.optionId })
    }
}
