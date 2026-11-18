package dev.yurlungr.app.chat

import dev.yurlungr.app.agents.Agent
import dev.yurlungr.app.agents.AgentStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PromptedChatTest {
    private val statuses = listOf(
        AgentStatus(Agent.Claude, installed = true, signedIn = false),
        AgentStatus(Agent.Codex, installed = true, signedIn = true),
        AgentStatus(Agent.Gemini, installed = false, signedIn = null),
    )

    @Test fun `project default, then the new-chat choice, then signed in, then installed`() {
        assertEquals(Agent.Claude, PromptedChat.pickAgent(Agent.Claude, Agent.Codex, statuses))
        assertEquals(Agent.Codex, PromptedChat.pickAgent(null, Agent.Codex, statuses))
        assertEquals(Agent.Codex, PromptedChat.pickAgent(Agent.Gemini, null, statuses)) // not installed: skipped
        assertEquals(Agent.Claude, PromptedChat.pickAgent(null, null, statuses.map { it.copy(signedIn = null) }))
    }

    @Test fun `nothing installed, no agent`() {
        assertNull(PromptedChat.pickAgent(Agent.Claude, Agent.Codex, statuses.map { it.copy(installed = false) }))
    }
}
