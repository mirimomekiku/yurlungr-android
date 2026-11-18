package dev.yurlungr.app.chat

import android.content.Context
import dev.yurlungr.app.agents.Agent
import dev.yurlungr.app.agents.AgentStatus
import dev.yurlungr.app.agents.agentStatuses
import dev.yurlungr.app.projects.ProjectAgentPrefs
import dev.yurlungr.app.runtime.RuntimePaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** A new chat started from elsewhere in the app (a failure, a warning) with its first message ready. */
object PromptedChat {
    /**
     * The agent for it: the project's default, else the one New chat last used, else the first
     * signed-in one, else the first installed one; only ever an installed agent.
     */
    fun pickAgent(projectDefault: Agent?, preferred: Agent?, statuses: List<AgentStatus>): Agent? {
        val installed = statuses.filter { it.installed }
        return listOfNotNull(projectDefault, preferred).firstOrNull { a -> installed.any { it.agent == a } }
            ?: installed.firstOrNull { it.signedIn == true }?.agent
            ?: installed.firstOrNull()?.agent
    }

    /** A chat in [cwd] that sends [prompt] once the agent is ready; null (and a note) when no agent is installed. */
    suspend fun start(context: Context, cwd: File, prompt: String): ChatSession? {
        val agent = withContext(Dispatchers.IO) {
            pickAgent(ProjectAgentPrefs.defaultAgent(context, cwd), NewChatPrefs.agent(context), agentStatuses(RuntimePaths(context)))
        }
        if (agent == null) {
            android.widget.Toast.makeText(context, "Install an agent first (Agents)", android.widget.Toast.LENGTH_LONG).show()
            return null
        }
        return ChatSessions.create(context, agent, cwd).also { it.queue(prompt, emptyList()) }
    }
}
