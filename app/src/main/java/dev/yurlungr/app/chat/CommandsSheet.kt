package dev.yurlungr.app.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.yurlungr.app.ui.AppColors
import dev.yurlungr.app.ui.AppSheet
import dev.yurlungr.app.ui.Ph

/** Everything the agent advertises as a slash command (skills and plugin commands included), with search. */
@Composable
fun CommandsSheet(agentName: String, commands: List<SlashCommand>, onPick: (SlashCommand) -> Unit, onDismiss: () -> Unit, onManage: (() -> Unit)? = null) {
    var query by remember { mutableStateOf("") }
    val shown = remember(query, commands) {
        val q = query.trim().removePrefix("/").lowercase()
        commands.filter { q.isEmpty() || it.name.lowercase().contains(q) || it.description.lowercase().contains(q) }
    }
    AppSheet("Commands", onDismiss = onDismiss, icon = Ph.Terminal, tile = AppColors.tiles.lilac, subtitle = "${commands.size} from $agentName") {
        OutlinedTextField(query, { query = it }, placeholder = { Text("Search commands") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp).padding(top = 6.dp)) {
            items(shown, key = { it.name }) { c ->
                Column(Modifier.fillMaxWidth().clickable { onPick(c); onDismiss() }.padding(vertical = 8.dp)) {
                    Text("/${c.name}" + (c.hint?.let { " <$it>" } ?: ""), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
                    if (c.description.isNotBlank()) Text(c.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            if (shown.isEmpty()) item { Text(if (commands.isEmpty()) "$agentName hasn't listed any commands yet." else "No match.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 12.dp)) }
        }
        if (onManage != null) androidx.compose.material3.TextButton(onClick = { onDismiss(); onManage() }) { Text("Manage skills & commands") }
    }
}
