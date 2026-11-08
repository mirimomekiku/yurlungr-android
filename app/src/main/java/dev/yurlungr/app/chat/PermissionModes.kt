package dev.yurlungr.app.chat

import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import dev.yurlungr.app.ui.MenuChip

/** Exact ACP IDs only: never infer a bypass from a translated display name. */
internal object PermissionModes {
    private val skipIds = setOf("bypassPermissions", "yolo", "agent-full-access", "full-access", "auto-approve")
    fun isSkipMode(id: String) = id in skipIds
    fun skipMode(modes: List<SessionMode>): SessionMode? = modes.firstOrNull { isSkipMode(it.id) }
}

@Composable
fun PermissionPicker(skip: Boolean, onChange: (Boolean) -> Unit) {
    MenuChip(if (skip) "Permissions: Skip (YOLO)" else "Permissions: Agent default") { close ->
        DropdownMenuItem(text = { Text("Agent default") }, onClick = { onChange(false); close() })
        DropdownMenuItem(text = {
            Column(Modifier.widthIn(max = 260.dp)) {
                Text("Skip permissions (YOLO)")
                Text("Allow tools without approval using the agent’s approval override.", style = MaterialTheme.typography.bodySmall)
            }
        }, onClick = { onChange(true); close() })
    }
}
