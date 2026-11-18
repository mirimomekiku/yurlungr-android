package dev.yurlungr.app.projects

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import dev.yurlungr.app.runtime.ToolSetup
import dev.yurlungr.app.ui.AppColors
import dev.yurlungr.app.ui.Chip
import dev.yurlungr.app.ui.Ph
import dev.yurlungr.app.ui.PhIcon
import dev.yurlungr.app.ui.WaveDots

/** Template chips by group, then what the chosen one sets up and anything it still needs. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TemplatePicker(
    selected: ProjectTemplate,
    /** Tools [selected] needs that aren't installed; null while checking. */
    missing: List<Requirement>?,
    enabled: Boolean,
    onSelect: (ProjectTemplate) -> Unit,
    onInstallPack: (ToolSetup) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Template", style = MaterialTheme.typography.titleSmall)
        for (group in TemplateGroup.entries) {
            val list = Templates.all.filter { it.group == group }
            if (list.isEmpty()) continue
            Text(group.label, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (t in list) Chip(t.name + if (t.experimental) " (experimental)" else "", onClick = { if (enabled) onSelect(t) }, selected = t.id == selected.id)
            }
        }
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(colors.surfaceContainerHigh).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(selected.blurb + ".", style = MaterialTheme.typography.bodyMedium)
            if (!selected.isEmpty) {
                Text(
                    "Adds starter files, a .gitignore and AGENTS.md, installs dependencies in a terminal, then starts it and opens the preview.",
                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                )
            }
            if (selected.experimental) {
                Row {
                    PhIcon(Ph.Warning, null, size = 14.dp, tint = AppColors.status.warning, modifier = Modifier.padding(top = 2.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Experimental: runs as a web app inside the Ubuntu environment. Android builds are unlikely to work on the phone.",
                        style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
            }
            when {
                missing == null -> if (selected.requires.isNotEmpty()) WaveDots(dotSize = 4.dp)
                missing.isNotEmpty() -> {
                    Text(Templates.missingMessage(missing).orEmpty(), style = MaterialTheme.typography.bodySmall, color = AppColors.status.warning)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (pack in missing.mapNotNull { it.pack }.distinct()) {
                            FilledTonalButton(onClick = { onInstallPack(pack) }, enabled = enabled) {
                                PhIcon(Ph.DownloadSimple, null, size = 15.dp)
                                Spacer(Modifier.width(6.dp))
                                Text("Install ${pack.title}")
                            }
                        }
                    }
                }
                selected.requires.isNotEmpty() -> Row(verticalAlignment = Alignment.CenterVertically) {
                    PhIcon(Ph.CheckCircle, null, size = 14.dp, tint = AppColors.status.success)
                    Spacer(Modifier.width(6.dp))
                    Text("Needs " + selected.requires.joinToString { it.label } + ": installed",
                        style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
            }
        }
    }
}

/** A setting row: title, one line of explanation, and a switch. */
@Composable
internal fun CheckRow(title: String, subtitle: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(enabled = enabled) { onChange(!checked) }.padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(10.dp))
        Switch(checked, onChange, enabled = enabled)
    }
}
