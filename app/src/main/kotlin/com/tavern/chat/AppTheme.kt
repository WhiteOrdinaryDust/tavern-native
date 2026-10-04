package com.tavern.chat

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import com.tavern.domain.theme.Appearance
import com.tavern.domain.theme.Theme

/** 当前外观：用 CompositionLocal 传，省得一层层往下传参数。 */
val LocalAppearance = staticCompositionLocalOf { Appearance() }

private fun color(value: String, fallback: Color): Color = try {
    Color(android.graphics.Color.parseColor(value))
} catch (_: Exception) {
    fallback
}

/**
 * 把 `:domain` 的四套配色接到 Material3 上。
 *
 * 配色本身、亮暗判定（`Theme.themeMode` 由色板自己的 mode 决定）都是已经测过的纯函数，
 * 这里只做字符串 → Color 的转换。
 */
@Composable
fun TavernTheme(appearance: Appearance, content: @Composable () -> Unit) {
    val dark = Theme.themeMode(appearance.theme) == "dark"
    val scheme0 = if (dark) darkColorScheme() else lightColorScheme()
    val values = Theme.colorSchemeValues(appearance)
    val scheme = scheme0.copy(
        surface = color(values.getValue("surface"), scheme0.surface),
        onSurface = color(values.getValue("on_surface"), scheme0.onSurface),
        primary = color(values.getValue("primary"), scheme0.primary),
        onPrimary = color(values.getValue("on_primary"), scheme0.onPrimary),
        secondary = color(values.getValue("secondary"), scheme0.secondary),
        onSecondary = color(values.getValue("on_secondary"), scheme0.onSecondary),
        onSurfaceVariant = color(values.getValue("on_surface_variant"), scheme0.onSurfaceVariant),
        outline = color(values.getValue("outline"), scheme0.outline),
        background = color(Theme.palette(appearance.theme).getValue("bg"), scheme0.background),
        surfaceVariant = color(Theme.palette(appearance.theme).getValue("card_hi"), scheme0.surfaceVariant),
        error = color(com.tavern.domain.theme.DANGER, scheme0.error),
    )
    // 字体族：跟随系统 / 黑体 / 细黑 / 中黑 / 衬线 / 等宽 / 手写
    val family = when (appearance.fontFamily) {
        "serif" -> FontFamily.Serif
        "monospace" -> FontFamily.Monospace
        "casual" -> FontFamily.Cursive
        "sans-serif", "sans-serif-light", "sans-serif-medium" -> FontFamily.SansSerif
        else -> FontFamily.Default
    }
    val base = Typography()
    val typography = if (appearance.fontFamily.isEmpty()) {
        base
    } else {
        base.copy(
            titleMedium = base.titleMedium.copy(fontFamily = family),
            bodyMedium = base.bodyMedium.copy(fontFamily = family),
            bodySmall = base.bodySmall.copy(fontFamily = family),
            labelSmall = base.labelSmall.copy(fontFamily = family),
            labelMedium = base.labelMedium.copy(fontFamily = family),
        )
    }
    CompositionLocalProvider(LocalAppearance provides appearance) {
        MaterialTheme(colorScheme = scheme, typography = typography, content = content)
    }
}
