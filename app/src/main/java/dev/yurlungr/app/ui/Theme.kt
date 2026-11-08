package dev.yurlungr.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Surface
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.yurlungr.app.R

/*
 * "Bento × Daylight": light-first white cards on a cool grey ground (Daylight), with
 * color-blocked tiles, Space Grotesk headings and chunky radii (Bento). Dark mode is Bento's
 * near-black with the same tiles at full strength. Fonts are bundled variable TTFs (SIL OFL,
 * licenses in assets/licenses); the weight axis is set per weight.
 */

@OptIn(ExperimentalTextApi::class) // variable-font weight axis; stable enough, below API 26 it's ignored
private fun variable(res: Int, weight: Int) =
    Font(res, FontWeight(weight), variationSettings = FontVariation.Settings(FontVariation.weight(weight)))

/** Body and UI text. */
val BodyFont = FontFamily(
    variable(R.font.inter, 400),
    variable(R.font.inter, 500),
    variable(R.font.inter, 600),
    variable(R.font.inter, 700),
)

/** Headings, titles, big numbers. */
val DisplayFont = FontFamily(
    variable(R.font.space_grotesk, 400),
    variable(R.font.space_grotesk, 500),
    variable(R.font.space_grotesk, 600),
    variable(R.font.space_grotesk, 700),
)

private val Tight = (-0.5).sp
/** Small text keeps a gentler squeeze so it stays legible at 11–14sp. */
private val TightSmall = (-0.25).sp

private fun TextStyle.with(family: FontFamily, spacing: TextUnit = Tight, weight: FontWeight? = null) =
    copy(fontFamily = family, letterSpacing = spacing, fontWeight = weight ?: fontWeight)

/** Material 3's type scale: Space Grotesk for display/headline/title, Inter for body and labels. */
private val AppTypography = Typography().run {
    copy(
        displayLarge = displayLarge.with(DisplayFont, weight = FontWeight.Bold),
        displayMedium = displayMedium.with(DisplayFont, weight = FontWeight.Bold),
        displaySmall = displaySmall.with(DisplayFont, weight = FontWeight.Bold),
        headlineLarge = headlineLarge.with(DisplayFont, weight = FontWeight.Bold),
        headlineMedium = headlineMedium.with(DisplayFont, weight = FontWeight.Bold),
        headlineSmall = headlineSmall.with(DisplayFont, weight = FontWeight.SemiBold),
        titleLarge = titleLarge.with(DisplayFont, weight = FontWeight.SemiBold),
        titleMedium = titleMedium.with(DisplayFont, weight = FontWeight.SemiBold),
        titleSmall = titleSmall.with(BodyFont, TightSmall, weight = FontWeight.SemiBold),
        bodyLarge = bodyLarge.with(BodyFont, TightSmall),
        bodyMedium = bodyMedium.with(BodyFont, TightSmall),
        bodySmall = bodySmall.with(BodyFont, TightSmall),
        labelLarge = labelLarge.with(BodyFont, TightSmall, weight = FontWeight.SemiBold),
        labelMedium = labelMedium.with(BodyFont, TightSmall, weight = FontWeight.Medium),
        labelSmall = labelSmall.with(BodyFont, TightSmall),
    )
}

/** Chunky Bento radii: cards 24, controls 16, chips/pills fully round where drawn as CircleShape. */
private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF0F172A), onPrimary = Color.White,
    // User bubbles, selected chips: solid ink.
    primaryContainer = Color(0xFF0F172A), onPrimaryContainer = Color.White,
    inversePrimary = Color(0xFFD4F36B),
    secondary = Color(0xFF5B6474), onSecondary = Color.White,
    secondaryContainer = Color(0xFFE6E8EC), onSecondaryContainer = Color(0xFF0F172A),
    // Permission prompts and other "needs you" cards: peach tile.
    tertiary = Color(0xFF9A3412), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFD3B5), onTertiaryContainer = Color(0xFF4A2208),
    background = Color(0xFFF2F3F5), onBackground = Color(0xFF0F172A),
    surface = Color(0xFFF2F3F5), onSurface = Color(0xFF0F172A),
    surfaceVariant = Color(0xFFEEF0F4), onSurfaceVariant = Color(0xFF5B6474),
    surfaceTint = Color.Transparent,
    surfaceBright = Color.White, surfaceDim = Color(0xFFE6E8EC),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF7F8FA),
    surfaceContainer = Color.White, // cards
    surfaceContainerHigh = Color(0xFFEEF0F4), // tinted fills, composer
    surfaceContainerHighest = Color(0xFFE6E8EC),
    outline = Color(0xFF94A3B8), outlineVariant = Color(0xFFE3E6EB),
    error = Color(0xFFDC2626), onError = Color.White,
    errorContainer = Color(0xFFFEE2E2), onErrorContainer = Color(0xFF991B1B),
    inverseSurface = Color(0xFF0F172A), inverseOnSurface = Color(0xFFF4F4F5),
    scrim = Color(0x99000000),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFF4F4F5), onPrimary = Color(0xFF0C0C0F),
    primaryContainer = Color(0xFFF4F4F5), onPrimaryContainer = Color(0xFF0C0C0F),
    inversePrimary = Color(0xFFD4F36B),
    secondary = Color(0xFFA1A1AA), onSecondary = Color(0xFF0C0C0F),
    secondaryContainer = Color(0xFF27272A), onSecondaryContainer = Color(0xFFF4F4F5),
    tertiary = Color(0xFFFFB98C), onTertiary = Color(0xFF2B1306),
    tertiaryContainer = Color(0xFFFFB98C), onTertiaryContainer = Color(0xFF2B1306),
    background = Color(0xFF0C0C0F), onBackground = Color(0xFFF4F4F5),
    surface = Color(0xFF0C0C0F), onSurface = Color(0xFFF4F4F5),
    surfaceVariant = Color(0xFF18181D), onSurfaceVariant = Color(0xFFA1A1AA),
    surfaceTint = Color.Transparent,
    surfaceBright = Color(0xFF27272A), surfaceDim = Color(0xFF0C0C0F),
    surfaceContainerLowest = Color(0xFF09090B),
    surfaceContainerLow = Color(0xFF111114),
    surfaceContainer = Color(0xFF18181D),
    surfaceContainerHigh = Color(0xFF27272A),
    surfaceContainerHighest = Color(0xFF303036),
    outline = Color(0xFF52525B), outlineVariant = Color(0xFF27272A),
    error = Color(0xFFF87171), onError = Color(0xFF2B0A0A),
    errorContainer = Color(0xFF3B1D1D), onErrorContainer = Color(0xFFFCA5A5),
    inverseSurface = Color(0xFFF4F4F5), inverseOnSurface = Color(0xFF0C0C0F),
    scrim = Color(0x99000000),
)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    MaterialTheme(colorScheme = if (dark) DarkColors else LightColors, typography = AppTypography, shapes = AppShapes) {
        CompositionLocalProvider(
            LocalStatusColors provides if (dark) DarkStatus else LightStatus,
            LocalTiles provides if (dark) DarkTiles else LightTiles,
        ) {
            // Surface supplies the background and default content (text) color.
            Surface(color = MaterialTheme.colorScheme.background, content = content)
        }
    }
}

/** Semantic colors Material 3 lacks: success (green) and warning (amber), per theme. */
data class StatusColors(
    val success: Color,
    val onSuccessContainer: Color,
    val successContainer: Color,
    val warning: Color,
    val onWarning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
)

private val DarkStatus = StatusColors(
    success = Color(0xFF4ADE80),
    onSuccessContainer = Color(0xFFCDEFD8),
    successContainer = Color(0xFF182A20),
    warning = Color(0xFFFFB98C),
    onWarning = Color(0xFF2B1306),
    warningContainer = Color(0xFF2E1D12),
    onWarningContainer = Color(0xFFFFD3B5),
)

private val LightStatus = StatusColors(
    success = Color(0xFF15803D),
    onSuccessContainer = Color(0xFF0F3D1E),
    successContainer = Color(0xFFDCF5E3),
    warning = Color(0xFFC2410C),
    onWarning = Color.White,
    warningContainer = Color(0xFFFFE4D2),
    onWarningContainer = Color(0xFF4A2208),
)

/** A color-blocked Bento tile: fill plus the text/icon color that reads on it. */
data class Tile(val fill: Color, val on: Color)

/** The four tile colors: lime (highlight, send, success), lilac (agents, branches), peach (attention), sky (info). */
data class Tiles(val lime: Tile, val lilac: Tile, val peach: Tile, val sky: Tile)

private val LightTiles = Tiles(
    lime = Tile(Color(0xFFD4F36B), Color(0xFF1A2005)),
    lilac = Tile(Color(0xFFDCD3FF), Color(0xFF2A1F5C)),
    peach = Tile(Color(0xFFFFD3B5), Color(0xFF4A2208)),
    sky = Tile(Color(0xFFC7E9FF), Color(0xFF0B3550)),
)

private val DarkTiles = Tiles(
    lime = Tile(Color(0xFFD4F36B), Color(0xFF10120A)),
    lilac = Tile(Color(0xFFC9B8FF), Color(0xFF17112B)),
    peach = Tile(Color(0xFFFFB98C), Color(0xFF2B1306)),
    sky = Tile(Color(0xFF8FD6FF), Color(0xFF062033)),
)

val LocalStatusColors = staticCompositionLocalOf { DarkStatus }
val LocalTiles = staticCompositionLocalOf { DarkTiles }

/** `AppColors.status.warning`, `AppColors.tiles.lime.fill` etc. */
object AppColors {
    val status: StatusColors @Composable get() = LocalStatusColors.current
    val tiles: Tiles @Composable get() = LocalTiles.current
}
