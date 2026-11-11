package dev.yurlungr.app.agents

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.yurlungr.app.R

/** Each agent's own logo (LobeHub Icons, MIT; see assets/licenses/LobeHub-Icons-LICENSE.txt), if there is one. */
private val Agent.logo: Int?
    get() = when (this) {
        Agent.Claude -> R.drawable.agent_claude
        Agent.Codex -> R.drawable.agent_codex
        Agent.Gemini -> R.drawable.agent_gemini
        Agent.Antigravity -> R.drawable.agent_antigravity
        Agent.Qwen -> R.drawable.agent_qwen
        Agent.Kimi -> R.drawable.agent_kimi
        Agent.Auggie -> null
        Agent.CommandCode -> R.drawable.agent_commandcode
        Agent.Copilot -> R.drawable.agent_copilot
        Agent.Goose -> R.drawable.agent_goose
        Agent.OpenCode -> R.drawable.agent_opencode
        Agent.MiniMax -> R.drawable.agent_minimax
        Agent.Qoder -> R.drawable.agent_qoder
        Agent.Cline -> R.drawable.agent_cline
        Agent.Kilo -> R.drawable.agent_kilo
        Agent.Cursor -> R.drawable.agent_cursor
        Agent.Amp -> R.drawable.agent_amp
        Agent.MistralVibe -> R.drawable.agent_mistral
        Agent.Kiro -> R.drawable.agent_kiro
        Agent.Pi, Agent.FastAgent, Agent.MinionCode -> null
    }

/** Single-colour logos: tinted with the text colour so they show in light and dark mode. */
private val Agent.monochrome: Boolean
    get() = this in setOf(Agent.Copilot, Agent.Goose, Agent.OpenCode, Agent.CommandCode, Agent.Cline, Agent.Kilo, Agent.Cursor)

/** The agent's logo on its own, no tile behind it; a monogram when there's no logo. [size] is the logo's box. */
@Composable
fun AgentBadge(agent: Agent, modifier: Modifier = Modifier, size: Dp = 40.dp) {
    val logo = agent.logo
    if (logo != null) {
        Image(
            painter = painterResource(logo),
            contentDescription = agent.displayName,
            modifier = modifier.size(size),
            colorFilter = if (agent.monochrome) androidx.compose.ui.graphics.ColorFilter.tint(androidx.compose.material3.MaterialTheme.colorScheme.onSurface) else null,
        )
    } else {
        val tile = dev.yurlungr.app.ui.AppColors.tiles.lilac
        androidx.compose.foundation.layout.Box(
            modifier.size(size).clip(CircleShape).background(tile.fill),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                agent.displayName.take(1),
                color = tile.on,
                fontWeight = FontWeight.Bold,
                fontSize = (size.value * 0.45f).sp,
            )
        }
    }
}
