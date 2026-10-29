package com.sportfit.sync.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * 浅色主题。
 *
 * 除了 M3 的基础角色，这里把 surfaceContainer* 五个层级都定了下来 ——
 * 卡片、列表行、输入框分别用不同层级，页面才有「层次」而不是一片白。
 */
private val LightColors = lightColorScheme(
    primary = BrandIndigo,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE4FF),
    onPrimaryContainer = Color(0xFF001945),
    inversePrimary = BrandIndigoLight,

    secondary = BrandTeal,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFF9DF0F5),
    onSecondaryContainer = Color(0xFF002022),

    tertiary = BrandAmber,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFDCC0),
    onTertiaryContainer = Color(0xFF2C1700),

    error = BrandRed,
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),

    background = NeutralCanvas,
    onBackground = NeutralInk,
    surface = Color.White,
    onSurface = NeutralInk,
    surfaceVariant = Color(0xFFE2E2EC),
    onSurfaceVariant = Color(0xFF45464F),

    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF0F1F8),
    surfaceContainer = Color(0xFFEAECF4),
    surfaceContainerHigh = Color(0xFFE4E6EE),
    surfaceContainerHighest = Color(0xFFDEE0E8),

    outline = Color(0xFF757780),
    outlineVariant = Color(0xFFC5C6D0),
    scrim = Color(0xFF000000),

    inverseSurface = Color(0xFF2F3033),
    inverseOnSurface = Color(0xFFF1F0F7),
)

/** 深色主题：底色偏冷，容器逐级变亮，和浅色版一一对应。 */
private val DarkColors = darkColorScheme(
    primary = BrandIndigoLight,
    onPrimary = Color(0xFF002D6E),
    primaryContainer = Color(0xFF0D4193),
    onPrimaryContainer = Color(0xFFD6E3FF),
    inversePrimary = BrandIndigo,

    secondary = BrandTealLight,
    onSecondary = Color(0xFF003739),
    secondaryContainer = Color(0xFF004F53),
    onSecondaryContainer = Color(0xFF9DF0F5),

    tertiary = BrandAmberLight,
    onTertiary = Color(0xFF4A2800),
    tertiaryContainer = Color(0xFF693C00),
    onTertiaryContainer = Color(0xFFFFDCC0),

    error = BrandRedLight,
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),

    background = NeutralCanvasDark,
    onBackground = NeutralInkLight,
    surface = Color(0xFF111318),
    onSurface = NeutralInkLight,
    surfaceVariant = Color(0xFF45464F),
    onSurfaceVariant = Color(0xFFC6C5D0),

    surfaceContainerLowest = Color(0xFF0C0E13),
    surfaceContainerLow = Color(0xFF191A1F),
    surfaceContainer = Color(0xFF1D1F24),
    surfaceContainerHigh = Color(0xFF272A2F),
    surfaceContainerHighest = Color(0xFF32343A),

    outline = Color(0xFF90909A),
    outlineVariant = Color(0xFF45464F),
    scrim = Color(0xFF000000),

    inverseSurface = Color(0xFFE3E2E9),
    inverseOnSurface = Color(0xFF2F3033),
)

/** 圆角：卡片大一点、按钮小一点，整体偏「软」。 */
private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/**
 * 头图卡的专用配色。
 *
 * 头图是深色渐变底 + 白字，**不跟主题明暗走**：浅色主题用亮一点的蓝，
 * 深色主题用暗一点的蓝，两套都验证过对比度够。
 */
@Immutable
data class HeroPalette(
    val start: Color,
    val end: Color,
    val onHero: Color,
    val onHeroSoft: Color,
)

private val LightHero = HeroPalette(
    start = Color(0xFF1B3FA0),
    end = Color(0xFF3D74EE),
    onHero = Color.White,
    onHeroSoft = Color(0xFFD6E3FF),
)

private val DarkHero = HeroPalette(
    start = Color(0xFF16296B),
    end = Color(0xFF23408F),
    onHero = Color(0xFFF2F5FF),
    onHeroSoft = Color(0xFFB9C8F5),
)

/** 头图配色，通过 [SportFitTheme] 注入。 */
val LocalHeroPalette = staticCompositionLocalOf { LightHero }

@Composable
fun SportFitTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalHeroPalette provides if (darkTheme) DarkHero else LightHero,
    ) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            typography = AppTypography,
            shapes = AppShapes,
            content = content,
        )
    }
}
