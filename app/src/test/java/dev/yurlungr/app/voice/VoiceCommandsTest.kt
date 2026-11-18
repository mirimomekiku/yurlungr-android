package dev.yurlungr.app.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceCommandsTest {
    private fun answer(text: String) = VoiceCommands.parse(text, answering = true)
    private fun talk(text: String) = VoiceCommands.parse(text, answering = false)

    @Test fun allowVariants() {
        for (t in listOf("allow", "Allow.", "yes", "Yeah!", "OK", "okay please", "go ahead", "Allow once", "sure", "um, yes")) {
            assertEquals(t, VoiceCommand.Allow, answer(t))
        }
    }

    @Test fun denyVariants() {
        for (t in listOf("deny", "No.", "nope", "Don't", "do not", "don't allow", "Reject", "cancel", "no thank you")) {
            assertEquals(t, VoiceCommand.Deny, answer(t))
        }
    }

    @Test fun stopVariants() {
        for (t in listOf("stop", "Stop!", "stop listening", "hands-free off", "Hands free off", "that's all", "That’s all", "goodbye", "please stop")) {
            assertEquals(t, VoiceCommand.Stop, talk(t))
            assertEquals(t, VoiceCommand.Stop, answer(t))
        }
        assertEquals(VoiceCommand.Stop, talk("cancel"))
    }

    @Test fun messagesAreNotCommands() {
        for (t in listOf("stop the dev server", "yes and also fix the tests", "allow users to log in", "no tests are failing?", "", "thank you")) {
            assertEquals(t, VoiceCommand.None, talk(t))
        }
        // Allow/deny words only count while a prompt waits.
        assertEquals(VoiceCommand.None, talk("yes"))
        assertEquals(VoiceCommand.None, talk("no"))
    }

    @Test fun unclearAnswersAreNone() {
        assertEquals(VoiceCommand.None, answer("maybe"))
        assertEquals(VoiceCommand.None, answer("what is it doing"))
    }

    @Test fun alternativesPickTheFirstCommand() {
        assertEquals(VoiceCommand.Allow, VoiceCommands.parse(listOf("a low", "allow"), answering = true))
        assertEquals(VoiceCommand.None, VoiceCommands.parse(listOf("a low", "hello"), answering = true))
    }

    @Test fun normalizeDropsPunctuationAndFillers() {
        assertEquals("hands free off", VoiceCommands.normalize("Hands-free off, please."))
        assertEquals("don't", VoiceCommands.normalize("Um… don’t!"))
    }
}
