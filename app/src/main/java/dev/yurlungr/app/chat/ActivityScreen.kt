package dev.yurlungr.app.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.yurlungr.app.agents.Agent
import dev.yurlungr.app.ui.AppColors
import dev.yurlungr.app.ui.Ph
import dev.yurlungr.app.ui.PhIcon
import dev.yurlungr.app.ui.ScreenHeader
import dev.yurlungr.app.ui.SectionLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/** What an agent is doing across all chats: its live to-do list, and what each agent has used. */
object Activity {
    class Row(val chat: ChatSession, val needsYou: Boolean, val plan: List<PlanEntry>) {
        val done get() = plan.count { it.status == "completed" }
        val current get() = plan.firstOrNull { it.status == "in_progress" }?.content
    }

    fun working(chats: List<ChatSession>): List<Row> = chats.mapNotNull { c ->
        val needs = c.items.any { it is ChatItem.Permission && it.resolved == null }
        if (!c.isRunning && !needs && c.queued.isEmpty()) return@mapNotNull null
        val plan = (c.items.lastOrNull { it is ChatItem.Plan } as? ChatItem.Plan)?.entries.orEmpty()
        Row(c, needs, plan)
    }

    /**
     * [costs] is summed per currency (agents may report different ones). [contextInUse] adds up
     * what each chat's context window holds now, as ACP reports it: not the tokens ever used.
     */
    class AgentUsage(val agent: Agent, val chats: Int, val prompts: Int, val costs: Map<String, Double>, val contextInUse: Long)

    fun usage(chats: List<ChatSession>): List<AgentUsage> = chats.groupBy { it.agent }.map { (agent, list) ->
        val costs = list.mapNotNull { c -> c.usage?.costAmount?.let { (c.usage?.currency ?: "USD") to it } }
            .groupBy({ it.first }, { it.second }).mapValues { (_, v) -> v.sum() }
        AgentUsage(agent, list.size, list.sumOf { c -> c.items.count { it is ChatItem.User } }, costs, list.sumOf { it.usage?.used ?: 0L })
    }.sortedByDescending { it.prompts }
}

@Composable
fun ActivityScreen(onBack: () -> Unit, onOpenChat: (String) -> Unit) {
    val context = LocalContext.current
    val chats = ChatSessions.chats.toList()
    val working = Activity.working(chats)
    val usage = Activity.usage(chats)
    // Keyed on the latest activity too, so totals refresh after a turn, not only when a chat is added.
    val totals by produceState(Checkpoints.Stats(0, 0, 0), chats.size, chats.maxOfOrNull { it.updatedAt }) {
        value = withContext(Dispatchers.IO) {
            chats.map { Checkpoints.stats(context, it.id) }.let { s -> Checkpoints.Stats(s.sumOf { it.turns }, s.sumOf { it.millis }, s.sumOf { it.filesChanged }) }
        }
    }
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize().background(colors.background).systemBarsPadding()) {
        ScreenHeader("Activity", onBack)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { SectionLabel("Working now") }
            if (working.isEmpty()) item { Text("No agent is working. Messages you queue and plans show up here.", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant) }
            items(working, key = { it.chat.id }) { row ->
                val tone = if (row.needsYou) AppColors.tiles.peach else AppColors.tiles.lilac
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(colors.surfaceContainer).clickable { onOpenChat(row.chat.id) }.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PhIcon(if (row.needsYou) Ph.Warning else Ph.Sparkle, null, size = 18.dp)
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(row.chat.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                listOfNotNull(
                                    row.chat.agent.displayName,
                                    if (row.needsYou) "waiting for your answer" else "working",
                                    row.chat.queued.size.takeIf { it > 0 }?.let { "$it queued" },
                                ).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                            )
                        }
                        if (row.chat.isRunning) TextButton(onClick = { row.chat.cancel() }) { Text("Stop") }
                    }
                    if (row.plan.isNotEmpty()) {
                        Text("${row.done} of ${row.plan.size} steps" + (row.current?.let { " · $it" } ?: ""), style = MaterialTheme.typography.bodySmall, color = tone.on.takeIf { false } ?: colors.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        for (e in row.plan.take(8)) {
                            Text(
                                (when (e.status) { "completed" -> "✓ "; "in_progress" -> "▸ "; else -> "○ " }) + e.content,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (e.status == "completed") colors.onSurfaceVariant else colors.onSurface,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
            item { SectionLabel("Usage") }
            item {
                Text(
                    "${chats.size} chat${if (chats.size == 1) "" else "s"} · ${totals.turns} prompts · ${duration(totals.millis)} working · ${totals.filesChanged} files changed",
                    style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant,
                )
            }
            items(usage, key = { it.agent.name }) { u ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(colors.surfaceContainer).padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(u.agent.displayName, style = MaterialTheme.typography.titleSmall)
                        Text("${u.chats} chat${if (u.chats == 1) "" else "s"} · ${u.prompts} prompts", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(u.costs.entries.joinToString(" · ") { (cur, v) -> String.format(Locale.US, "%.2f %s", v, cur) }.ifEmpty { "cost n/a" }, fontWeight = FontWeight.Medium)
                        if (u.contextInUse > 0) Text("${u.contextInUse / 1000}k in context now", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    }
                }
            }
            item {
                Text(
                    "Cost and context use are what each agent reports for chats opened since the app started; agents that don't report them show n/a.",
                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                )
            }
        }
    }
}
