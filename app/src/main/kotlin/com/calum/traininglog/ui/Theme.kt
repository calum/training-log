package com.calum.traininglog.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Semantic colour palette. Use [AppColors] to read it inside composables so dark/light switch
 * automatically. Philosophy: ink-dark canvas, electric mint = progress/primary action,
 * orange = energy/streak, violet = rewards/XP. There is deliberately NO red: nothing in the app
 * should ever look like a failure.
 */
@Immutable
class AppPalette(
    val isDark: Boolean,
    val background: Color,
    val surface: Color,
    val surfaceRaised: Color,
    val surfaceHigh: Color,
    val outline: Color,
    val track: Color,
    val textPrimary: Color,
    val textMuted: Color,
    val primary: Color,
    val primaryDeep: Color,
    val onPrimary: Color,
    val energy: Color,
    val energyDeep: Color,
    val onEnergy: Color,
    val reward: Color,
    val rewardDeep: Color,
    val onReward: Color,
    val info: Color,
    val onHero: Color,
    val gradientHero: Brush,
    val gradientPrimary: Brush,
    val gradientEnergy: Brush,
    val gradientReward: Brush,
)

private val DarkPalette = AppPalette(
    isDark = true,
    background = Color(0xFF0B0E14),
    surface = Color(0xFF131824),
    surfaceRaised = Color(0xFF1B2230),
    surfaceHigh = Color(0xFF252E40),
    outline = Color(0xFF2E384B),
    track = Color(0xFF222B3B),
    textPrimary = Color(0xFFF4F7FB),
    textMuted = Color(0xFF9BA7BA),
    primary = Color(0xFF3DF5B0),
    primaryDeep = Color(0xFF14B987),
    onPrimary = Color(0xFF04130D),
    energy = Color(0xFFFF9A3D),
    energyDeep = Color(0xFFFF6B2C),
    onEnergy = Color(0xFF1F0B00),
    reward = Color(0xFFB08CFF),
    rewardDeep = Color(0xFF7B5CFF),
    onReward = Color(0xFF14083A),
    info = Color(0xFF4CC9F0),
    onHero = Color(0xFFF4F7FB),
    gradientHero = Brush.linearGradient(
        listOf(Color(0xFF0F5E52), Color(0xFF14386B), Color(0xFF2A1F6B)),
        start = Offset(0f, 0f), end = Offset(1100f, 900f),
    ),
    gradientPrimary = Brush.horizontalGradient(listOf(Color(0xFF3DF5B0), Color(0xFF7CF7D0))),
    gradientEnergy = Brush.horizontalGradient(listOf(Color(0xFFFF6B2C), Color(0xFFFFB03D))),
    gradientReward = Brush.horizontalGradient(listOf(Color(0xFF7B5CFF), Color(0xFFC49BFF))),
)

private val LightPalette = AppPalette(
    isDark = false,
    background = Color(0xFFF3F6FA),
    surface = Color(0xFFFFFFFF),
    surfaceRaised = Color(0xFFE9EEF5),
    surfaceHigh = Color(0xFFDCE4EF),
    outline = Color(0xFFC7D1DF),
    track = Color(0xFFDDE5F0),
    textPrimary = Color(0xFF0E1420),
    textMuted = Color(0xFF55627A),
    primary = Color(0xFF0BA77A),
    primaryDeep = Color(0xFF078A64),
    onPrimary = Color(0xFFFFFFFF),
    energy = Color(0xFFE8710A),
    energyDeep = Color(0xFFD65A00),
    onEnergy = Color(0xFFFFFFFF),
    reward = Color(0xFF6B4BEA),
    rewardDeep = Color(0xFF5636CC),
    onReward = Color(0xFFFFFFFF),
    info = Color(0xFF0A86B8),
    onHero = Color(0xFF062B24),
    gradientHero = Brush.linearGradient(
        listOf(Color(0xFFB7F5DD), Color(0xFFBFD6FF), Color(0xFFD9CCFF)),
        start = Offset(0f, 0f), end = Offset(1100f, 900f),
    ),
    gradientPrimary = Brush.horizontalGradient(listOf(Color(0xFF0BA77A), Color(0xFF22C795))),
    gradientEnergy = Brush.horizontalGradient(listOf(Color(0xFFE8710A), Color(0xFFF5A02A))),
    gradientReward = Brush.horizontalGradient(listOf(Color(0xFF5636CC), Color(0xFF8B6CF5))),
)

internal val LocalPalette = staticCompositionLocalOf { DarkPalette }

/** Semantic colours. Read inside composables: `AppColors.energy`. */
object AppColors {
    val palette: AppPalette @Composable @ReadOnlyComposable get() = LocalPalette.current
    val isDark: Boolean @Composable @ReadOnlyComposable get() = LocalPalette.current.isDark
    val background: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.background
    val surface: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.surface
    /** Cards that sit on [surface] or [background] and need to pop (tiles, set rows). */
    val surfaceRaised: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.surfaceRaised
    val surfaceHigh: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.surfaceHigh
    val outline: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.outline
    /** Unfilled part of rings / bars. */
    val track: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.track
    val textPrimary: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.textPrimary
    val textMuted: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.textMuted
    /** Electric mint: progress, done, primary action. */
    val primary: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.primary
    val primaryDeep: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.primaryDeep
    val onPrimary: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.onPrimary
    /** Warm orange: effort, streaks, bonus beyond target. */
    val energy: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.energy
    val energyDeep: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.energyDeep
    val onEnergy: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.onEnergy
    /** Violet: XP, levels, badges, extra-credit. */
    val reward: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.reward
    val rewardDeep: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.rewardDeep
    val onReward: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.onReward
    /** Cool sky blue: neutral data, forecasts, incline. */
    val info: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.info
    /** Text colour guaranteed readable on [gradientHero]. */
    val onHero: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.onHero
    val gradientHero: Brush @Composable @ReadOnlyComposable get() = LocalPalette.current.gradientHero
    val gradientPrimary: Brush @Composable @ReadOnlyComposable get() = LocalPalette.current.gradientPrimary
    val gradientEnergy: Brush @Composable @ReadOnlyComposable get() = LocalPalette.current.gradientEnergy
    val gradientReward: Brush @Composable @ReadOnlyComposable get() = LocalPalette.current.gradientReward
}

/** Spacing constants. `minTap` is the smallest allowed interactive size (gym gloves, sweaty thumbs). */
object Spacing {
    val xxs = 2.dp
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
    /** Screen edge gutter. */
    val screen = 16.dp
    val minTap = 48.dp
    val bigTap = 64.dp
}

/** Rounded shape scale; everything in this app is generously round. */
object AppShapes {
    val chip = RoundedCornerShape(50)
    val small = RoundedCornerShape(14.dp)
    val medium = RoundedCornerShape(22.dp)
    val large = RoundedCornerShape(30.dp)
    val hero = RoundedCornerShape(36.dp)
}

/**
 * Big numeric styles on top of the Material typography. Tabular figures so digits don't jiggle
 * while counting up.
 */
object AppType {
    private fun num(size: Int, weight: FontWeight = FontWeight.Black) = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = weight,
        fontSize = size.sp,
        lineHeight = (size * 1.05f).sp,
        letterSpacing = (-size * 0.02f).sp,
        fontFeatureSettings = "tnum",
    )

    /** Treadmill speed. */
    val numberHero = num(128)
    val numberXL = num(72)
    val numberL = num(48)
    val numberM = num(32, FontWeight.ExtraBold)
    val numberS = num(22, FontWeight.ExtraBold)
    /** Tiny uppercase caption above values. */
    val eyebrow = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 12.sp,
        lineHeight = 14.sp,
        letterSpacing = 1.2.sp,
    )
}

private fun appTypography() = Typography(
    displayLarge = TextStyle(fontWeight = FontWeight.Black, fontSize = 72.sp, lineHeight = 76.sp, letterSpacing = (-1.5).sp),
    displayMedium = TextStyle(fontWeight = FontWeight.Black, fontSize = 56.sp, lineHeight = 60.sp, letterSpacing = (-1).sp),
    displaySmall = TextStyle(fontWeight = FontWeight.ExtraBold, fontSize = 40.sp, lineHeight = 44.sp, letterSpacing = (-0.5).sp),
    headlineLarge = TextStyle(fontWeight = FontWeight.ExtraBold, fontSize = 32.sp, lineHeight = 38.sp),
    headlineMedium = TextStyle(fontWeight = FontWeight.ExtraBold, fontSize = 28.sp, lineHeight = 34.sp),
    headlineSmall = TextStyle(fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 28.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 20.sp, lineHeight = 26.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 23.sp),
    titleSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontWeight = FontWeight.Normal, fontSize = 17.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 16.sp, lineHeight = 20.sp, letterSpacing = 0.2.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 13.sp, lineHeight = 16.sp, letterSpacing = 0.3.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.Bold, fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.8.sp),
)

private fun AppPalette.toColorScheme(): ColorScheme =
    if (isDark) darkColorScheme(
        primary = primary, onPrimary = onPrimary,
        primaryContainer = Color(0xFF0E4D3B), onPrimaryContainer = primary,
        secondary = energy, onSecondary = onEnergy,
        tertiary = reward, onTertiary = onReward,
        background = background, onBackground = textPrimary,
        surface = surface, onSurface = textPrimary,
        surfaceVariant = surfaceRaised, onSurfaceVariant = textMuted,
        surfaceContainer = surface, surfaceContainerHigh = surfaceRaised, surfaceContainerHighest = surfaceHigh,
        outline = outline, outlineVariant = track,
    ) else lightColorScheme(
        primary = primary, onPrimary = onPrimary,
        primaryContainer = Color(0xFFC2F4E2), onPrimaryContainer = Color(0xFF04382A),
        secondary = energy, onSecondary = onEnergy,
        tertiary = reward, onTertiary = onReward,
        background = background, onBackground = textPrimary,
        surface = surface, onSurface = textPrimary,
        surfaceVariant = surfaceRaised, onSurfaceVariant = textMuted,
        surfaceContainer = surface, surfaceContainerHigh = surfaceRaised, surfaceContainerHighest = surfaceHigh,
        outline = outline, outlineVariant = track,
    )

/**
 * App theme. Dark by default (gym / treadmill use); pass `darkTheme = false` or
 * `isSystemInDarkTheme()` to follow other policies. Wrap the whole app (and every preview) in it.
 */
@Composable
fun TrainingTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit,
) {
    val palette = if (darkTheme) DarkPalette else LightPalette
    CompositionLocalProvider(LocalPalette provides palette) {
        MaterialTheme(
            colorScheme = palette.toColorScheme(),
            typography = appTypography(),
            shapes = Shapes(
                extraSmall = RoundedCornerShape(10.dp),
                small = AppShapes.small,
                medium = AppShapes.medium,
                large = AppShapes.large,
                extraLarge = AppShapes.hero,
            ),
            content = content,
        )
    }
}
