package dev.yurlungr.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

/** A small outlined pill, "Label ⌄", that opens a dropdown of choices. */
@Composable
fun MenuChip(label: String, modifier: Modifier = Modifier, menu: @Composable ColumnScope.(close: () -> Unit) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        Chip(label, trailing = Ph.CaretDown, onClick = { open = true })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) { menu { open = false } }
    }
}

/** 32dp outlined pill. [selected] fills it with the accent (for on/off options). */
@Composable
fun Chip(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    trailing: androidx.compose.ui.graphics.vector.ImageVector? = null,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier
            .height(32.dp)
            .clip(CircleShape)
            .background(if (selected) colors.primaryContainer else colors.surfaceContainer)
            .border(1.dp, if (selected) colors.primaryContainer else colors.outlineVariant, CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) colors.onPrimaryContainer else colors.onSurface,
            maxLines = 1,
        )
        if (trailing != null) {
            Spacer(Modifier.width(6.dp))
            PhIcon(trailing, null, size = 12.dp)
        }
    }
}
