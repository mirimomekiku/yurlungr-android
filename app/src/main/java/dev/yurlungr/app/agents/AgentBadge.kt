package dev.yurlungr.app.agents

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.yurlungr.app.R

/** Each agent's own logo (LobeHub Icons, MIT; see assets/licenses/LobeHub-Icons-LICENSE.txt). */
private val Agent.logo: Int
    get() = when (this) {
        Agent.Claude -> R.drawable.agent_claude
        Agent.Codex -> R.drawable.agent_codex
        Agent.Gemini -> R.drawable.agent_gemini
        Agent.Antigravity -> R.drawable.agent_antigravity
    }

/** The agent's logo on its own, no tile behind it. [size] is the logo's box. */
@Composable
fun AgentBadge(agent: Agent, modifier: Modifier = Modifier, size: Dp = 40.dp) {
    Image(
        painter = painterResource(agent.logo),
        contentDescription = agent.displayName,
        modifier = modifier.size(size),
    )
}
