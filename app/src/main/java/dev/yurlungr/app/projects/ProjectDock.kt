package dev.yurlungr.app.projects

import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import dev.yurlungr.app.ui.PhIcon
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import dev.yurlungr.app.ui.NavTile
import dev.yurlungr.app.ui.StatTile
import dev.yurlungr.app.terminal.TerminalSessions
import com.termux.terminal.TerminalSession
import dev.yurlungr.app.chat.cwdLabel
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import dev.yurlungr.app.agents.AgentBadge
import dev.yurlungr.app.chat.ChatSession
import dev.yurlungr.app.chat.ChatSessions
import dev.yurlungr.app.chat.ChatState
import dev.yurlungr.app.ui.AppColors
import dev.yurlungr.app.ui.Motion
import dev.yurlungr.app.ui.WarningBanner
import dev.yurlungr.app.ui.Ph
import dev.yurlungr.app.ui.SkeletonCode
import dev.yurlungr.app.ui.WaveDots
import dev.yurlungr.app.ui.SkeletonListItem
import dev.yurlungr.app.ui.NavButton
import dev.yurlungr.app.ui.Keyboards
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import dev.yurlungr.app.ui.Chip
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.yurlungr.app.agents.Agent
import dev.yurlungr.app.agents.agentStatuses
import dev.yurlungr.app.projects.Git.Companion.shellQuote
import dev.yurlungr.app.runtime.RuntimePaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** One tool in the project dock (and its More sheet). */
internal class DockTool(
    val label: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val tile: dev.yurlungr.app.ui.Tile?,
    /** Shown in the More sheet. */
    val description: String,
    val dot: Color? = null,
    val onLongClick: (() -> Unit)? = null,
    val onClick: () -> Unit,
)

/**
 * The project dock. Chat, then the project's main action: Run/Open for web apps, Build for Android,
 * otherwise Terminal. With more than three buttons the rest (Setup, Git, Terminal…) go into a
 * **More** sheet, which carries a dot when one of them needs you.
 */
@Composable
internal fun ActionBar(dir: File, isAndroid: Boolean, onNewChat: (Agent) -> Unit, runInTerminal: (String, String) -> Unit,
    onOpenTerminal: () -> Unit, hasWeb: Boolean, onRunWeb: () -> Unit, hasGit: Boolean, onGitActions: () -> Unit,
    hasStacks: Boolean, onSetup: () -> Unit, setupNeeded: Boolean = false, onRunWebOptions: () -> Unit = onRunWeb,
    /** More tools for the More sheet (Scripts, Agent settings…). */
    extraTools: List<DockTool> = emptyList()) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val tiles = AppColors.tiles
    val installed by androidx.compose.runtime.produceState(emptyList<Agent>()) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            agentStatuses(RuntimePaths(context)).filter { it.installed }.map { it.agent }
        }
    }
    val webState = dev.yurlungr.app.web.WebRunSessions.get(dir)?.state
    // The project's chosen agent starts with a tap; long-press (or none chosen) picks.
    val preferred = remember(installed) { ProjectAgentPrefs.defaultAgent(context, dir)?.takeIf { it in installed } }
    var agentMenu by remember { mutableStateOf(false) }
    var terminalMenu by remember { mutableStateOf(false) }
    var more by remember { mutableStateOf(false) }
    val newHere = { runInTerminal("cd ${shellQuote(dir.path)}", dir.name) }

    val running = webState == dev.yurlungr.app.web.WebRunState.Running
    val starting = webState?.active == true && !running
    val run = if (hasWeb) DockTool(
        when { running -> "Open"; starting -> "Starting"; else -> "Run" },
        if (running) Ph.Globe else Ph.Play, tiles.lime,
        when { running -> "Open the app in the browser"; starting -> "Show the server's logs"; else -> "Start the web app and open it" },
        dot = when { running -> AppColors.status.success; starting -> AppColors.status.warning; else -> null },
        onLongClick = onRunWebOptions, onClick = onRunWeb,
    ) else null
    val build = if (isAndroid) DockTool("Build", Ph.Hammer, tiles.lime, "Build the debug APK and install it") {
        runInTerminal(
            "cd ${shellQuote(dir.path)} && bash ./gradlew assembleDebug && " +
                "yurlungr-install \"\$(ls -t app/build/outputs/apk/debug/*.apk | head -1)\"",
            "Build ${dir.name}",
        )
    } else null
    val terminal = DockTool("Terminal", Ph.TerminalWindow, null, "Open a shell in this project, or switch to an open one") {
        // With terminals already open, ask: reuse one (those in this project first) or start fresh.
        if (TerminalSessions.sessions.isEmpty()) newHere() else terminalMenu = true
    }
    val others = listOfNotNull(
        if (hasStacks) DockTool("Setup", Ph.Wrench, tiles.sky, if (setupNeeded) "Dependencies are missing: install them" else "Detected stacks and their dependencies",
            dot = if (setupNeeded) AppColors.status.warning else null, onClick = onSetup) else null,
        if (hasGit) DockTool("Git", Ph.GitBranch, tiles.lilac, "Get latest, sync, branches and AI fixes", onClick = onGitActions) else null,
    ) + extraTools
    // The main action for this kind of project; everything else is secondary.
    val primary = run ?: build ?: terminal
    val secondary = listOfNotNull(build.takeIf { primary !== it }) + others + listOfNotNull(terminal.takeIf { primary !== it }) +
        listOfNotNull(run?.let { DockTool("Run options", Ph.GearSix, null, "App folder, script, port and start command", onClick = onRunWebOptions) })
    // Chat + primary + one more fits; past three buttons, the rest move to the More sheet.
    // Run options don't count: long-pressing Run (or the server card's menu) reaches them.
    val counted = secondary.filterNot { it.label == "Run options" }
    val inline = if (counted.size <= 1) counted else emptyList()
    val overflow = if (inline.isEmpty()) secondary else emptyList()

    val shape = RoundedCornerShape(26.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 12.dp)
            .clip(shape)
            .background(colors.surfaceContainer)
            .border(1.dp, colors.outlineVariant, shape)
            .padding(6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(Modifier.weight(1.5f)) {
            val enabled = installed.isNotEmpty()
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(if (enabled) colors.primary else colors.surfaceContainerHigh)
                    .combinedClickable(
                        enabled = enabled,
                        onLongClick = { agentMenu = true },
                        onClick = { preferred?.let(onNewChat) ?: if (installed.size == 1) onNewChat(installed[0]) else agentMenu = true },
                    ),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val tint = if (enabled) colors.onPrimary else colors.outline
                if (preferred != null) AgentBadge(preferred, size = 22.dp) else PhIcon(Ph.ChatCircle, null, size = 20.dp, tint = tint)
                Spacer(Modifier.width(8.dp))
                Text("Chat", style = MaterialTheme.typography.titleMedium, fontSize = 16.sp, color = tint)
            }
            DropdownMenu(expanded = agentMenu, onDismissRequest = { agentMenu = false }) {
                for (agent in installed) {
                    DropdownMenuItem(
                        leadingIcon = { AgentBadge(agent, size = 26.dp) },
                        text = { Text(agent.displayName) },
                        onClick = {
                            agentMenu = false
                            onNewChat(agent)
                        },
                    )
                }
            }
        }
        // The main action is a wide pill with its label beside the icon.
        PrimaryDockButton(primary, Modifier.weight(1.3f))
        for (tool in inline) DockButton(tool.label, tool.icon, tool.tile, Modifier.weight(0.8f), dot = tool.dot, onLongClick = tool.onLongClick, onClick = tool.onClick)
        Box(if (overflow.isNotEmpty()) Modifier.weight(0.8f) else Modifier) {
            if (overflow.isNotEmpty()) {
                DockButton("More", Ph.DotsThreeVertical, null, Modifier.fillMaxWidth(),
                    dot = overflow.firstNotNullOfOrNull { it.dot.takeIf { _ -> it.label == "Setup" } }) { more = true }
            }
            // Anchored here so it opens above the dock, from the main button or the sheet.
            TerminalMenu(dir, terminalMenu, onDismiss = { terminalMenu = false }, onNew = { terminalMenu = false; newHere() }) { s ->
                terminalMenu = false
                TerminalSessions.select(s)
                onOpenTerminal()
            }
        }
    }

    if (more) {
        dev.yurlungr.app.ui.AppSheet("Project tools", onDismiss = { more = false }, subtitle = dir.name, icon = Ph.Wrench) {
            for (tool in overflow) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(colors.surfaceContainerHigh)
                        .clickable { more = false; tool.onClick() }
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.size(42.dp).clip(RoundedCornerShape(14.dp)).background(tool.tile?.fill ?: colors.surfaceContainerHighest),
                        contentAlignment = Alignment.Center,
                    ) { PhIcon(tool.icon, null, size = 20.dp, tint = tool.tile?.on ?: colors.onSurface) }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(tool.label, style = MaterialTheme.typography.titleSmall)
                        Text(tool.description, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    }
                    tool.dot?.let { Box(Modifier.size(9.dp).clip(CircleShape).background(it)) }
                    Spacer(Modifier.width(4.dp))
                    PhIcon(Ph.CaretRight, null, size = 14.dp, tint = colors.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun PrimaryDockButton(tool: DockTool, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    val fill = tool.tile?.fill ?: colors.surfaceContainerHighest
    val on = tool.tile?.on ?: colors.onSurface
    Box(modifier) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(56.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(fill)
                .combinedClickable(onClickLabel = tool.label, onLongClickLabel = tool.onLongClick?.let { "${tool.label} options" },
                    onLongClick = tool.onLongClick, onClick = tool.onClick),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PhIcon(tool.icon, null, size = 18.dp, tint = on)
            Spacer(Modifier.width(6.dp))
            Text(tool.label, style = MaterialTheme.typography.titleSmall, color = on, maxLines = 1, softWrap = false)
        }
        tool.dot?.let {
            Box(
                Modifier.align(Alignment.TopEnd).padding(top = 6.dp, end = 8.dp).size(11.dp).clip(CircleShape)
                    .background(fill).padding(2.dp).clip(CircleShape).background(it),
            )
        }
    }
}

/** Open terminals (this project's first, marked "here") plus "New terminal in <project>". */
@Composable
private fun TerminalMenu(dir: File, expanded: Boolean, onDismiss: () -> Unit, onNew: () -> Unit, onOpen: (TerminalSession) -> Unit) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        val entries = remember(expanded) {
            TerminalSessions.sessions.mapIndexed { i, s ->
                val cwd = runCatching { s.cwd }.getOrNull()
                Triple(i, s, cwd)
            }.sortedByDescending { (_, _, cwd) -> cwd != null && (cwd == dir.path || cwd.startsWith(dir.path + "/")) }
        }
        Text(
            "Open terminal",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        )
        DropdownMenuItem(
            leadingIcon = { PhIcon(Ph.Plus, null, size = 18.dp, tint = MaterialTheme.colorScheme.primary) },
            text = { Text("New terminal in ${dir.name}") },
            onClick = onNew,
        )
        HorizontalDivider()
        for ((i, s, cwd) in entries) {
            val here = cwd != null && (cwd == dir.path || cwd.startsWith(dir.path + "/"))
            DropdownMenuItem(
                leadingIcon = {
                    Box(
                        Modifier.size(26.dp).clip(RoundedCornerShape(8.dp)).background(if (here) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest),
                        contentAlignment = Alignment.Center,
                    ) { Text("${i + 1}", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 12.sp) }
                },
                text = {
                    Column {
                        Text(TerminalSessions.title(s, i), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            listOfNotNull(
                                cwd?.let { cwdLabel(it) },
                                if (!s.isRunning) "exited" else if (here) "here" else null,
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (here) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                onClick = { onOpen(s) },
            )
        }
    }
}

/** A dock tool: a tinted icon chip over a short label, with an optional status dot. */
@Composable
private fun DockButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tile: dev.yurlungr.app.ui.Tile?,
    modifier: Modifier = Modifier,
    dot: Color? = null,
    /** Options, when the tap already acts. */
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier
            .height(56.dp)
            .clip(RoundedCornerShape(18.dp))
            .combinedClickable(onClickLabel = label, onLongClickLabel = onLongClick?.let { "$label options" }, onLongClick = onLongClick, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box {
            Box(
                Modifier.size(width = 40.dp, height = 28.dp).clip(RoundedCornerShape(14.dp))
                    .background(tile?.fill ?: colors.surfaceContainerHighest),
                contentAlignment = Alignment.Center,
            ) { PhIcon(icon, null, size = 17.dp, tint = tile?.on ?: colors.onSurface) }
            if (dot != null) {
                // Ringed in the dock colour so it reads on any tile.
                Box(
                    Modifier.align(Alignment.TopEnd).size(11.dp).clip(CircleShape)
                        .background(colors.surfaceContainer).padding(2.dp).clip(CircleShape).background(dot),
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(label, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = colors.onSurface, maxLines = 1, softWrap = false,
            modifier = if (dot != null) Modifier.semantics { stateDescription = "has an update" } else Modifier)
    }
}
