package dev.yurlungr.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Outline phone and tablet glyphs drawn as strokes (Phosphor-like weight). Phosphor's own
 * device-mobile path fills its bezel chambers in Compose and reads as a solid block.
 */
object DeviceIcons {
    private fun outline(name: String, left: Float, top: Float, right: Float, bottom: Float, radius: Float): ImageVector =
        ImageVector.Builder(name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 256f, viewportHeight = 256f)
            .path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 16f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                // Rounded body.
                moveTo(left + radius, top)
                lineTo(right - radius, top)
                arcTo(radius, radius, 0f, false, true, right, top + radius)
                lineTo(right, bottom - radius)
                arcTo(radius, radius, 0f, false, true, right - radius, bottom)
                lineTo(left + radius, bottom)
                arcTo(radius, radius, 0f, false, true, left, bottom - radius)
                lineTo(left, top + radius)
                arcTo(radius, radius, 0f, false, true, left + radius, top)
                close()
                // Speaker slot.
                val mid = (left + right) / 2
                moveTo(mid - 16f, top + 28f)
                lineTo(mid + 16f, top + 28f)
            }
            .build()

    /** Screen above a base, like a laptop seen from the front. */
    val Laptop: ImageVector by lazy {
        ImageVector.Builder(name = "Laptop", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 256f, viewportHeight = 256f)
            .path(stroke = SolidColor(Color.Black), strokeLineWidth = 16f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(64f, 56f)
                lineTo(192f, 56f)
                arcTo(16f, 16f, 0f, false, true, 208f, 72f)
                lineTo(208f, 168f)
                lineTo(48f, 168f)
                lineTo(48f, 72f)
                arcTo(16f, 16f, 0f, false, true, 64f, 56f)
                close()
                moveTo(24f, 200f)
                lineTo(232f, 200f)
            }
            .build()
    }

    val Phone: ImageVector by lazy { outline("Phone", 68f, 20f, 188f, 236f, 28f) }
    val Tablet: ImageVector by lazy { outline("Tablet", 40f, 24f, 216f, 232f, 24f) }
}
