package com.sportfit.sync.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.sportfit.sync.R
import com.sportfit.sync.domain.Platform
import com.sportfit.sync.ui.theme.LocalHeroPalette
import com.sportfit.sync.ui.theme.NumericSmallTextStyle
import com.sportfit.sync.ui.theme.PlatformPalette

/**
 * 页面的「底图」。
 *
 * 三层叠出来：顶部一层主色淡染 → 中间过渡到页面底色 → 右上角一圈同心圆环装饰。
 * 圆环用低透明度（[graphicsLayer] 的 alpha），只做氛围，不会抢内容的注意力。
 */
@Composable
fun AppBackground(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    0f to scheme.primaryContainer.copy(alpha = 0.65f),
                    0.28f to scheme.background,
                    1f to scheme.background,
                ),
            ),
    ) {
        Image(
            painter = painterResource(R.drawable.ic_decor_rings),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(280.dp)
                .offset(x = 96.dp, y = (-72).dp)
                .graphicsLayer { alpha = 0.55f },
        )
        content()
    }
}

/**
 * 页面顶部标题。
 *
 * 标题左边带一个圆形图标底座，页面之间一眼能看出「换了个地方」。
 */
@Composable
fun ScreenHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(22.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
        }
        Column {
            Text(
                title,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * 首页头图卡。
 *
 * 深色渐变底 + 右侧插画（SVG），左边放标题和状态。
 * 深色卡上文字一律用浅色，所以它不跟主题的明暗走 —— 两套配色都验证过。
 */
@Composable
fun HeroCard(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    illustration: Int = R.drawable.ic_hero_sync,
    trailing: @Composable (ColumnScope.() -> Unit)? = null,
) {
    val hero = LocalHeroPalette.current
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = Color.Transparent,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Brush.linearGradient(listOf(hero.start, hero.end)))
                .padding(20.dp),
        ) {
            Column(modifier = Modifier.fillMaxWidth(0.68f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.headlineMedium,
                    color = hero.onHero,
                )
                if (!subtitle.isNullOrBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = hero.onHeroSoft,
                    )
                }
                if (trailing != null) {
                    Spacer(Modifier.height(14.dp))
                    trailing()
                }
            }
            Image(
                painter = painterResource(illustration),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .size(116.dp)
                    .graphicsLayer { alpha = 0.95f },
            )
        }
    }
}

/**
 * 统计小卡：一个数字 + 一个标签。
 *
 * 数字用等宽数字样式（[NumericSmallTextStyle]），刷新时不会左右跳。
 */
@Composable
fun StatTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    tone: StatusTone = StatusTone.NEUTRAL,
) {
    val accent = tone.accent()
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(value, style = NumericSmallTextStyle, color = accent)
            Spacer(Modifier.height(2.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 一行等宽的统计小卡。 */
@Composable
fun StatRow(
    tiles: List<Pair<String, String>>,
    modifier: Modifier = Modifier,
    tone: StatusTone = StatusTone.NEUTRAL,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        tiles.forEach { (label, value) ->
            StatTile(
                label = label,
                value = value,
                tone = tone,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * 平台图标徽章。
 *
 * 每个平台一个固定颜色，账号页和方向选择里一眼能认出是哪个平台。
 */
@Composable
fun PlatformAvatar(
    platform: Platform,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
) {
    val color = platform.accentColor()
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(size / 3))
            .background(color.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            platformIcon(platform),
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(size * 0.55f),
        )
    }
}

/** 平台固定色。 */
fun Platform.accentColor(): Color = when (this) {
    Platform.GARMIN -> PlatformPalette.garmin
    Platform.COROS -> PlatformPalette.coros
    Platform.STRAVA -> PlatformPalette.strava
    Platform.IGPSPORT -> PlatformPalette.igpsport
    Platform.ONELAP -> PlatformPalette.onelap
    Platform.DEMO -> PlatformPalette.demo
    Platform.LOCAL_FILES -> PlatformPalette.local
}

/**
 * 空状态：插画 + 一句话说明。
 *
 * 插画是 SVG（VectorDrawable），用 `painterResource` 直接画。
 */
@Composable
fun EmptyState(
    illustration: Int,
    title: String,
    modifier: Modifier = Modifier,
    message: String? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(
            painter = painterResource(illustration),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.size(112.dp),
        )
        Spacer(Modifier.height(10.dp))
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (!message.isNullOrBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** 卡片里的一行小标题（带图标）。 */
@Composable
fun SectionTitle(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        trailing?.invoke()
    }
}

/** 一条细分隔线，比 M3 默认的更淡。 */
@Composable
fun HairLine(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
    )
}

/** 圆角标签（平台名、方向等）。 */
@Composable
fun SoftChip(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    icon: ImageVector? = null,
) {
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(
                icon,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(5.dp))
        }
        Text(text, style = MaterialTheme.typography.labelMedium, color = color)
    }
}

/** 细边框容器（比阴影更干净）。 */
@Composable
fun BorderedSurface(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
        content = { content() },
    )
}
