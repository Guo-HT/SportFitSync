package com.sportfit.sync.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * SportSync 的品牌色板。
 *
 * 选色思路：这个 App 讲的是「运动数据在不同平台之间流动」，所以主色取
 * 偏蓝的深靛（科技感、可靠），辅色取青绿（同步成功 / 已同步），
 * 第三色取琥珀（提醒 / 失败项），错误色取珊瑚红。
 * 所有颜色都成对给出「容器色 + 容器上的文字色」，浅色深色各一套，
 * 避免在暗色模式下出现「浅底浅字」。
 */

// ---- 品牌基色 ---------------------------------------------------------

/** 主色：靛蓝。按钮、选中态、强调。 */
val BrandIndigo = Color(0xFF2B59C3)
val BrandIndigoLight = Color(0xFFADC6FF)

/** 辅色：青绿。已同步、同步成功。 */
val BrandTeal = Color(0xFF00696E)
val BrandTealLight = Color(0xFF81D4DA)

/** 第三色：琥珀。提醒、失败项、需要注意的状态。 */
val BrandAmber = Color(0xFF8A5100)
val BrandAmberLight = Color(0xFFFFB870)

/** 错误色：珊瑚红。真正的错误。 */
val BrandRed = Color(0xFFBA1A1A)
val BrandRedLight = Color(0xFFFFB4AB)

/** 中性色阶。 */
val NeutralInk = Color(0xFF1A1C1E)
val NeutralInkLight = Color(0xFFE3E2E9)
val NeutralCanvas = Color(0xFFF6F7FB)
val NeutralCanvasDark = Color(0xFF111318)

// ---- 插画用色 ---------------------------------------------------------

/**
 * 插画专用色。
 *
 * 插画画在彩色渐变卡上，颜色要跟着卡片走，所以单独定义一组
 * 「在主色容器上好看」的颜色，不跟文字颜色体系混用。
 */
object Illustration {
    val OnPrimary = Color(0xFFFFFFFF)
    val OnPrimarySoft = Color(0xFFD6E3FF)
    val Accent = Color(0xFF8BE9FD)
    val AccentDeep = Color(0xFF0F6E8C)
    val Muted = Color(0xFFB9C0D6)
    val MutedDark = Color(0xFF555C73)
}

/** 平台图标底色：每个平台一个稳定的颜色，列表里一眼能认出来。 */
object PlatformPalette {
    val garmin = Color(0xFF0B7BD3)
    val coros = Color(0xFFE4572E)
    val strava = Color(0xFFFC5200)
    val igpsport = Color(0xFF12B886)
    val onelap = Color(0xFF5B6CFF)
    val demo = Color(0xFF7A5AF8)
    val local = Color(0xFF6B7280)
}
