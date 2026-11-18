package dev.yurlungr.app.home

import dev.yurlungr.app.agents.Agent
import dev.yurlungr.app.agents.AgentStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FirstRunStepsTest {
    private fun facts(
        linux: Boolean = false,
        node: Boolean = false,
        agents: List<AgentStatus> = emptyList(),
        folders: List<String> = emptyList(),
        last: String? = null,
        ran: Boolean = false,
        chatted: Boolean = false,
    ) = FirstRunFacts(linux, node, agents, folders, last, ran, chatted)

    @Test fun `a fresh install has nothing done and starts at the Linux environment`() {
        val states = FirstRunSteps.states(facts())
        assertEquals(FirstRunStep.entries, states.map { it.step })
        assertTrue(states.none { it.done })
        assertEquals(FirstRunStep.Linux, FirstRunSteps.next(states)?.step)
        assertEquals(FirstRunAction.SystemChecks, states[0].action)
        assertEquals(FirstRunAction.InstallNode, states[1].action)
        assertTrue(FirstRunSteps.visible(dismissed = false, finished = false, states = states))
    }

    @Test fun `scratch and hidden folders aren't projects`() {
        assertFalse(FirstRunSteps.hasProject(listOf("scratch", ".cache")))
        assertTrue(FirstRunSteps.hasProject(listOf("scratch", "my-app")))
    }

    @Test fun `agents count when installed and not known to be signed out`() {
        val signedOut = AgentStatus(Agent.Claude, installed = true, signedIn = false)
        val unknown = AgentStatus(Agent.Claude, installed = true, signedIn = null)
        val missing = AgentStatus(Agent.Claude, installed = false, signedIn = null)
        assertFalse(FirstRunSteps.agentReady(listOf(signedOut, missing)))
        assertTrue(FirstRunSteps.agentReady(listOf(unknown)))
        assertEquals(FirstRunAction.SignIn(Agent.Claude), FirstRunSteps.agentAction(listOf(missing, signedOut)).first)
        assertEquals(FirstRunAction.Agents, FirstRunSteps.agentAction(listOf(missing)).first)
    }

    @Test fun `the last step opens the last project, or the projects list`() {
        val withProject = FirstRunSteps.states(facts(last = "/p/app")).last()
        assertEquals(FirstRunAction.OpenProject("/p/app"), withProject.action)
        assertEquals(FirstRunAction.Projects, FirstRunSteps.states(facts()).last().action)
        assertTrue(FirstRunSteps.states(facts(chatted = true)).last().done)
        assertTrue(FirstRunSteps.states(facts(ran = true)).last().done)
    }

    @Test fun `all done hides it, and so do dismissing and finishing once`() {
        val ready = AgentStatus(Agent.Claude, installed = true, signedIn = true)
        val all = FirstRunSteps.states(facts(true, true, listOf(ready), listOf("app"), "/p/app", ran = true))
        assertTrue(FirstRunSteps.allDone(all))
        assertNull(FirstRunSteps.next(all))
        assertFalse(FirstRunSteps.visible(dismissed = false, finished = false, states = all))
        val some = FirstRunSteps.states(facts(linux = true))
        assertEquals(FirstRunStep.Runtime, FirstRunSteps.next(some)?.step)
        assertFalse(FirstRunSteps.visible(dismissed = true, finished = false, states = some))
        assertFalse(FirstRunSteps.visible(dismissed = false, finished = true, states = some))
        assertFalse(FirstRunSteps.visible(dismissed = false, finished = false, states = null))
    }
}
