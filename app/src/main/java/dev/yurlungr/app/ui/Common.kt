package dev.yurlungr.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** ☰/‹ + a large title + optional actions on the right. */
@Composable
fun ScreenHeader(title: String, onBack: () -> Unit, modifier: Modifier = Modifier, actions: @Composable RowScope.() -> Unit = {}) {
    Row(modifier.fillMaxWidth().padding(start = 12.dp, end = 8.dp, top = 10.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        NavTile(onBack)
        Text(
            title,
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-1).sp,
            fontFamily = DisplayFont,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(start = 12.dp),
        )
        actions()
    }
}

/** Small grey heading above a group. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = color,
        modifier = modifier.padding(start = 4.dp, top = 8.dp, bottom = 2.dp),
    )
}

/** Rounded group of rows (iOS/Android-settings style), rows separated by hairlines. */
@Composable
fun Group(modifier: Modifier = Modifier, content: @Composable GroupScope.() -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f), RoundedCornerShape(24.dp)),
    ) { GroupScope(this).content() }
}

class GroupScope(column: ColumnScope) : ColumnScope by column {
    private var rows = 0

    /** Call before each row; draws the divider between rows. */
    @Composable
    fun Separator() {
        if (rows++ > 0) HorizontalDivider(Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    }
}

/** A settings-style row: icon, title, subtitle, trailing content. */
@Composable
fun GroupScope.GroupRow(
    title: String,
    subtitle: String? = null,
    icon: ImageVector? = null,
    iconTint: Color = MaterialTheme.colorScheme.primary,
    subtitleColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    subtitleLines: Int = Int.MAX_VALUE,
    background: Color = Color.Transparent,
    onClick: (() -> Unit)? = null,
    /** Draws [icon] on a colored Bento tile instead of bare. */
    tile: Tile? = null,
    /** Replaces [icon] when set (e.g. a status glyph). */
    leading: (@Composable () -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit = { if (onClick != null) PhIcon(Ph.CaretRight, null, size = 14.dp, tint = MaterialTheme.colorScheme.outline) },
) {
    Separator()
    Row(
        Modifier
            .fillMaxWidth()
            .background(background)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(14.dp))
        } else if (icon != null && tile != null) {
            Box(Modifier.size(36.dp).clip(RoundedCornerShape(12.dp)).background(tile.fill), contentAlignment = Alignment.Center) {
                PhIcon(icon, null, size = 18.dp, tint = tile.on)
            }
            Spacer(Modifier.width(12.dp))
        } else if (icon != null) {
            PhIcon(icon, null, size = 20.dp, tint = iconTint)
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = subtitleColor, maxLines = subtitleLines, overflow = TextOverflow.Ellipsis) }
        }
        Spacer(Modifier.width(8.dp))
        trailing()
    }
}

/** A small round status dot. */
@Composable
fun Dot(color: Color, modifier: Modifier = Modifier, size: androidx.compose.ui.unit.Dp = 8.dp) {
    Box(modifier.size(size).clip(CircleShape).background(color))
}

/** Status pill, e.g. "Ready": a soft fill of [color] with a dot. */
@Composable
fun StatusLabel(text: String, color: Color, modifier: Modifier = Modifier) {
    Row(
        modifier.clip(CircleShape).background(color.copy(alpha = 0.14f)).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Dot(color, size = 7.dp)
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = color)
    }
}

/** Pill filled with a Bento [tile] color. */
@Composable
fun TilePill(text: String, tile: Tile, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        color = tile.on,
        maxLines = 1,
        modifier = modifier.clip(CircleShape).background(tile.fill).padding(horizontal = 10.dp, vertical = 3.dp),
    )
}

/** A square 46dp header button: white card in light, raised in dark (Bento × Daylight headers). */
@Composable
fun HeaderTile(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    fill: Color = MaterialTheme.colorScheme.surfaceContainer,
    tint: Color = MaterialTheme.colorScheme.onSurface,
) {
    Box(
        modifier
            .size(46.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(fill)
            .border(1.dp, if (fill == MaterialTheme.colorScheme.surfaceContainer) MaterialTheme.colorScheme.outlineVariant else Color.Transparent, RoundedCornerShape(16.dp))
            .clickable(onClickLabel = description, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { PhIcon(icon, description, size = 20.dp, tint = tint) }
}

/** ☰ (top level) or ‹ (deeper) as a [HeaderTile]. */
@Composable
fun NavTile(onBack: () -> Unit) {
    val openDrawer = LocalOpenDrawer.current
    if (LocalTopLevel.current && openDrawer != null) HeaderTile(Ph.List, "Open menu", openDrawer)
    else HeaderTile(Ph.CaretLeft, "Back", onBack)
}

/** A big color-blocked stat tile: small label on top, large value at the bottom. */
@Composable
fun StatTile(label: String, value: String, tile: Tile, modifier: Modifier = Modifier, valueSize: androidx.compose.ui.unit.TextUnit = 26.sp, onClick: (() -> Unit)? = null) {
    Column(
        modifier
            .height(88.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(tile.fill)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = tile.on)
        Text(value, fontFamily = DisplayFont, fontWeight = FontWeight.Bold, fontSize = valueSize, letterSpacing = (-0.6).sp, color = tile.on, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Full-width banner for something that needs attention, with an optional action. */
@Composable
fun WarningBanner(text: String, modifier: Modifier = Modifier, action: (@Composable RowScope.() -> Unit)? = null) {
    val s = AppColors.status
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(s.warningContainer)
            .padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp)
            .height(36.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = MaterialTheme.typography.bodySmall, color = s.onWarningContainer, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
        action?.invoke(this)
    }
}
