package dev.yurlungr.app.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeakableTextTest {
    private fun speak(md: String) = SpeakableText.fromMarkdown(md.trimIndent())

    @Test fun plainParagraphsEndWithPunctuation() {
        assertEquals("Hello there.\nSecond paragraph!", speak("Hello there\n\nSecond paragraph!"))
    }

    @Test fun emphasisAndHeadingsLoseTheirMarks() {
        assertEquals("Summary.\nThis is important.", speak("## Summary\n\nThis is **important**."))
    }

    @Test fun codeBlocksAreNamedNotRead() {
        val out = speak(
            """
            Run this:

            ```bash
            ./gradlew assembleDebug --stacktrace
            ```

                indented code
            """,
        )
        assertEquals("Run this:\nCode block.\nCode block.", out)
        assertFalse(out.contains("gradlew"))
    }

    @Test fun diagramsAreNamed() {
        assertEquals("Diagram.", speak("```mermaid\ngraph TD; A-->B\n```"))
    }

    @Test fun shortInlineCodeIsReadLongIsNot() {
        assertEquals("Edit build.gradle.kts now.", speak("Edit `build.gradle.kts` now."))
        assertEquals("It printed code.", speak("It printed `" + "x".repeat(60) + "`."))
    }

    @Test fun linksReadTheirTextAndBareUrlsSayLink() {
        assertEquals("See the docs for more.", speak("See [the docs](https://example.com/docs) for more."))
        assertEquals("Open link today.", speak("Open https://example.com/a/b today."))
        assertEquals("Open link.", speak("Open [https://example.com](https://example.com)."))
    }

    @Test fun tablesAreSummarized() {
        val out = speak(
            """
            | Name | Size |
            |------|------|
            | a    | 1    |
            | b    | 2    |
            """,
        )
        assertEquals("A table with 2 rows and 2 columns.", out)
    }

    @Test fun listsBecomeSentences() {
        assertEquals("First item.\nSecond item.", speak("- First item\n- Second item"))
        assertEquals("Done: Write tests.\nTo do: Ship it.", speak("- [x] Write tests\n- [ ] Ship it"))
    }

    @Test fun imagesReadAltAndHtmlIsReduced() {
        assertEquals("A cat.", speak("![A cat](cat.png)"))
        assertEquals("Hi there.", speak("<p>Hi <b>there</b></p>"))
    }

    @Test fun blankIsEmpty() {
        assertEquals("", speak("   "))
        assertEquals("", speak("---"))
    }

    @Test fun chunksRespectTheLimitAndPreferBoundaries() {
        val text = "One sentence here. Another one follows. " + "word ".repeat(40)
        val pieces = SpeakableText.chunks(text, 50)
        assertTrue(pieces.all { it.length <= 50 })
        assertEquals("One sentence here. Another one follows.", pieces.first())
        assertEquals(text.split(Regex("\\s+")).filter { it.isNotEmpty() }, pieces.joinToString(" ").split(' '))
    }

    @Test fun chunksSplitLongWordsHard() {
        val pieces = SpeakableText.chunks("a".repeat(25), 10)
        assertEquals(listOf("a".repeat(10), "a".repeat(10), "a".repeat(5)), pieces)
    }

    @Test fun shortTextIsOneChunk() {
        assertEquals(listOf("Hi."), SpeakableText.chunks("  Hi.  ", 4000))
        assertEquals(emptyList<String>(), SpeakableText.chunks("", 4000))
    }

    @Test fun permissionPromptNamesTheAction() {
        assertEquals(
            "Claude wants to run: npm install. Say allow or deny.",
            SpeakableText.permissionPrompt("Claude", "`npm install`", "execute"),
        )
        assertEquals(
            "Codex wants to edit: src/App.kt. Say allow or deny.",
            SpeakableText.permissionPrompt("Codex", "src/App.kt", "edit"),
        )
        assertTrue(SpeakableText.permissionPrompt("Gemini", "x".repeat(500), null).length < 220)
    }
}
