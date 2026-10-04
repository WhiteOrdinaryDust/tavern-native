package com.tavern.domain.theme

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 对应 Flet 版 `tavern/theme.py` 与 tests/test_core.py 的外观部分。 */
class ThemeTest {

    @Test
    fun normalizeClampsBadValues() {
        val out = Theme.normalize(
            Appearance(
                theme = "不存在",
                fontFamily = "comic-sans",
                fontScale = 99.0,
                bubbleOpacity = 5.0,
                bgFit = "乱七八糟",
                bgAlign = "左边",
                bgX = 5.0,
                bgY = -9.0,
                bgZoom = 100.0,
                bgDim = 9.0,
                messageWidth = 0.1,
            ),
        )
        assertEquals("dark", out.theme)
        assertEquals("", out.fontFamily)
        assertEquals(SCALE_MAX, out.fontScale)
        assertEquals(1.0, out.bubbleOpacity)
        assertEquals("cover", out.bgFit)
        assertEquals("center", out.bgAlign)
        assertEquals(1.0, out.bgX)
        assertEquals(-1.0, out.bgY)
        assertEquals(BG_ZOOM_MAX, out.bgZoom)
        assertEquals(0.85, out.bgDim)
        assertEquals(0.5, out.messageWidth)
    }

    @Test
    fun normalizeClampsLowSideAndRounds() {
        val out = Theme.normalize(Appearance(fontScale = 1.234, bgX = 0.987, messageWidth = 0.999))
        assertEquals(1.23, out.fontScale)
        assertEquals(0.99, out.bgX)
        assertEquals(1.0, out.messageWidth)
        assertEquals(SCALE_MIN, Theme.normalize(Appearance(fontScale = 0.1)).fontScale)
    }

    @Test
    fun clampFallsBackOnNonNumbers() {
        assertEquals(1.0, Theme.clamp("不是数字", 0.0, 2.0))
        assertEquals(2.0, Theme.clamp(5, 0.0, 2.0))
        assertEquals(0.5, Theme.clamp("0.5", 0.0, 2.0), "字符串里的数字也认")
        assertEquals(7.0, Theme.clamp(null, 0.0, 10.0, default = 7.0))
    }

    @Test
    fun normalizeColorAcceptsOnlyColorForms() {
        assertEquals("#AABBCC", Theme.normalizeColor("#abc"))
        assertEquals("#FF0000", Theme.normalizeColor("ff0000"), "没有 # 也认")
        assertEquals("#FF0000", Theme.normalizeColor("  #FF0000  "))
        assertEquals("#11223344", Theme.normalizeColor("#11223344"), "带 alpha 的写法保留")
        assertEquals("", Theme.normalizeColor("#12345"))
        assertEquals("", Theme.normalizeColor("#GGGGGG"))
        assertEquals("", Theme.normalizeColor("红色"))
        assertEquals("", Theme.normalizeColor(null))
    }

    @Test
    fun everyPaletteCarriesItsOwnMode() {
        // 踩过的坑：主题名单硬编码，加了浅绿后弹窗还是深色（字看不清）
        assertEquals("dark", Theme.themeMode("dark"))
        assertEquals("light", Theme.themeMode("light"))
        assertEquals("light", Theme.themeMode("sepia"))
        assertEquals("light", Theme.themeMode("green"))
        for ((key, _) in THEMES) {
            val colors = Theme.palette(key)
            assertTrue(colors.containsKey("mode"), key)
            assertEquals(colors.getValue("mode"), Theme.themeMode(key), key)
        }
        assertEquals(Theme.palette("dark"), Theme.palette("不存在这套主题"))
    }

    @Test
    fun colorSchemeBindsSurfaceToTheme() {
        val scheme = Theme.colorSchemeValues(Appearance(theme = "sepia"))
        assertEquals(Theme.palette("sepia").getValue("card"), scheme.getValue("surface"))
        assertEquals(Theme.palette("sepia").getValue("text"), scheme.getValue("on_surface"))
        assertEquals("#FFFFFF", scheme.getValue("on_primary"))
    }

    @Test
    fun toastColorsFollowTheme() {
        val (bg, fg) = Theme.toastColors(Appearance(theme = "green"), error = false)
        assertEquals(Theme.palette("green").getValue("card_hi"), bg)
        assertEquals(Theme.palette("green").getValue("text"), fg)
        val (errBg, errFg) = Theme.toastColors(Appearance(theme = "green"), error = true)
        assertEquals(DANGER, errBg)
        assertEquals("#FFFFFF", errFg)
    }

    @Test
    fun withOpacityBuildsAarrggbb() {
        assertEquals("#FFFFFFFF", Theme.withOpacity("#FFFFFF", 1.0))
        assertEquals("#80FFFFFF", Theme.withOpacity("#FFFFFF", 0.5), "127.5 取偶得 128")
        assertEquals("#00FFFFFF", Theme.withOpacity("#FFFFFF", 0.0))
        // 已经有 alpha：替换掉而不是叠加
        assertEquals("#80FFFFFF", Theme.withOpacity("#33FFFFFF", 0.5))
        assertEquals("", Theme.withOpacity("不是颜色", 1.0))
    }

    @Test
    fun bubbleColorUsesCustomThenTheme() {
        assertEquals("#FF3A2F6E", Theme.bubbleColor(Appearance(), isUser = true))
        assertEquals("#FF22252D", Theme.bubbleColor(Appearance(), isUser = false))
        assertEquals(
            "#FF123456",
            Theme.bubbleColor(Appearance(bubbleUser = "#123456"), isUser = true),
        )
        // 自定义写法不认 → 退回主题默认（而不是变成透明）
        assertEquals("#FF3A2F6E", Theme.bubbleColor(Appearance(bubbleUser = "红色"), isUser = true))
        // 透明度 0 → 完全透明
        assertEquals("#003A2F6E", Theme.bubbleColor(Appearance(bubbleOpacity = 0.0), isUser = true))
    }

    @Test
    fun textColorForFallsBack() {
        assertEquals("#E6E8EC", Theme.textColorFor(Appearance(), "#E6E8EC"))
        assertEquals("#FF00FF", Theme.textColorFor(Appearance(textColor = "ff00ff"), "#E6E8EC"))
        assertEquals("#E6E8EC", Theme.textColorFor(Appearance(textColor = "紫"), "#E6E8EC"))
    }

    @Test
    fun scaledLevelsAreVisiblyDifferent() {
        // 正文 14px → 12/14/18/22/28
        assertEquals(12, Theme.scaled(14, 0.85))
        assertEquals(14, Theme.scaled(14, 1.0))
        assertEquals(18, Theme.scaled(14, 1.25))
        assertEquals(22, Theme.scaled(14, 1.55))
        assertEquals(28, Theme.scaled(14, 2.0))
        // 越界的档位按边界处理（以前 1.55/2.0 都被压成 1.4，最大两档一样大）
        assertEquals(28, Theme.scaled(14, 3.0))
        assertEquals(12, Theme.scaled(14, 0.1))
        assertEquals(9, Theme.scaled(10, 0.85), "再小也不低于 9")
    }

    @Test
    fun labelsReadNaturally() {
        assertEquals("居中", Theme.positionLabel(Appearance()))
        assertEquals("靠左、靠上", Theme.positionLabel(Appearance(bgX = -1.0, bgY = -1.0)))
        assertEquals("靠右、靠下", Theme.positionLabel(Appearance(bgX = 0.9, bgY = 0.8)))
        assertEquals("略偏", Theme.positionLabel(Appearance(bgX = -0.2, bgY = 0.0)))
        assertEquals("1.0×", Theme.zoomLabel(Appearance()))
        assertEquals("2.5×", Theme.zoomLabel(Appearance(bgZoom = 2.5)))
    }

    @Test
    fun backgroundFitMapping() {
        assertEquals("COVER", Theme.boxFitName(Appearance(bgFit = "cover")))
        assertEquals("CONTAIN", Theme.boxFitName(Appearance(bgFit = "contain")))
        assertEquals("FILL", Theme.boxFitName(Appearance(bgFit = "fill")))
        assertEquals("NONE", Theme.boxFitName(Appearance(bgFit = "repeat")))
        assertTrue(Theme.repeatsImage(Appearance(bgFit = "repeat")))
        assertTrue(!Theme.repeatsImage(Appearance(bgFit = "cover")))
        assertEquals("TOP_CENTER", Theme.alignmentName(Appearance(bgAlign = "top")))
    }

    @Test
    fun dimOnlyWithBackgroundImage() {
        assertEquals("", Theme.dimColor(Appearance()))
        val dim = Theme.dimColor(Appearance(bgImage = "/tmp/bg.png", bgDim = 0.35))
        assertTrue(dim.startsWith("#"), dim)
        assertTrue(dim.endsWith("000000"), dim)
        assertNull(Theme.fontFamilyOrNull(Appearance()))
        assertEquals("serif", Theme.fontFamilyOrNull(Appearance(fontFamily = "serif")))
    }
}
