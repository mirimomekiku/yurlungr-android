package dev.yurlungr.app.chat

import org.junit.Assert.*
import org.junit.Test

class PermissionModesTest {
    @Test fun `Antigravity gets its native ACP flag while adapters use protocol modes`() {
        assertEquals("agy acp --dangerously-skip-permissions", dev.yurlungr.app.agents.Agent.Antigravity.launchCommand(true))
        for (agent in dev.yurlungr.app.agents.Agent.entries) {
            assertEquals(agent.acpCommand, agent.launchCommand(false))
            if (agent != dev.yurlungr.app.agents.Agent.Antigravity) assertEquals(agent.acpCommand, agent.launchCommand(true))
        }
    }

    @Test fun `selects advertised skip mode IDs across agents`() {
        for (id in listOf("bypassPermissions", "yolo", "agent-full-access", "full-access", "auto-approve")) {
            val modes = listOf(SessionMode("default", "Default", null), SessionMode(id, "Skip", null))
            assertEquals(id, PermissionModes.skipMode(modes)?.id)
        }
    }

    @Test fun `does not infer full access from names or permissive sounding modes`() {
        assertNull(PermissionModes.skipMode(listOf(SessionMode("default", "YOLO", null), SessionMode("auto_edit", "Auto edit", null))))
        assertNull(PermissionModes.skipMode(emptyList()))
    }

    @Test fun `saved chats persist opt in and older files default to agent permissions`() {
        val saved = SavedChat("id", "Claude", "/project", "Chat", null, 1L, emptyList(), skipPermissions = true)
        assertTrue(ChatStore.decode(ChatStore.encode(saved)).skipPermissions)
        val old = kotlinx.serialization.json.JsonObject(ChatStore.encode(saved).filterKeys { it != "skipPermissions" })
        assertFalse(ChatStore.decode(old).skipPermissions)
    }
}
