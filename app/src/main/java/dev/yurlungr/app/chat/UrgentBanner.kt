package dev.yurlungr.app.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.yurlungr.app.ui.AppColors
import dev.yurlungr.app.ui.Ph
import dev.yurlungr.app.ui.PhIcon

/**
 * Sticky in-app alert while an agent waits for the user's answer: stays until it's answered
 * (it can't be swiped away), and a tap opens that chat. Hidden on the chat it's about, where
 * the approval card is already on screen.
 */
@Composable
fun UrgentBanner(viewing: String?, onOpen: (String) -> Unit, modifier: Modifier = Modifier) {
    val alert = ChatSessions.urgent.firstOrNull { it.chatId != viewing }
    AnimatedVisibility(
        visible = alert != null,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
        modifier = modifier,
    ) {
        val shown = alert ?: return@AnimatedVisibility
        val tile = AppColors.tiles.peach
        Row(
            Modifier
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(tile.fill)
                .clickable { onOpen(shown.chatId) }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PhIcon(Ph.Sparkle, null, size = 20.dp, tint = tile.on)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("${shown.agentName} needs your answer", style = MaterialTheme.typography.titleSmall, color = tile.on)
                Text(shown.what, style = MaterialTheme.typography.bodySmall, color = tile.on, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (ChatSessions.urgent.size > 1) {
                Spacer(Modifier.width(8.dp))
                Text("+${ChatSessions.urgent.size - 1}", style = MaterialTheme.typography.labelLarge, color = tile.on)
            }
        }
    }
}
