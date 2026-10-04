package com.tavern.domain.theme

import com.tavern.domain.util.pyRound
import kotlinx.serialization.Serializable

/** 四套主题。 */
val THEMES: List<Pair<String, String>> = listOf(
    "dark" to "深色（黑）",
    "light" to "浅色（白）",
    "sepia" to "护眼（米黄）",
    "green" to "护眼（浅绿）",
)

/** 配色只在这里定义。每套都带 `mode`，避免"主题是浅色但弹窗还是深色"的老问题。 */
val PALETTES: Map<String, Map<String, String>> = mapOf(
    "dark" to mapOf(
        "mode" to "dark", "bg" to "#121212", "card" to "#1b1e26", "card_hi" to "#262a34",
        "text" to "#f7f8fa", "muted" to "#c6ccd6", "accent" to "#a9b8ff",
        "char_bubble" to "#22252d", "user_bubble" to "#3a2f6e", "dim" to "#000000",
    ),
    "light" to mapOf(
        "mode" to "light", "bg" to "#f4f5f7", "card" to "#ffffff", "card_hi" to "#eceef2",
        "text" to "#1c1f26", "muted" to "#6b7280", "accent" to "#6d4bff",
        "char_bubble" to "#e9ecf2", "user_bubble" to "#dcd4ff", "dim" to "#000000",
    ),
    "sepia" to mapOf(
        "mode" to "light", "bg" to "#f3ead7", "card" to "#fbf5e6", "card_hi" to "#efe4cd",
        "text" to "#463d31", "muted" to "#8b7d68", "accent" to "#b06a3b",
        "char_bubble" to "#f0e5cf", "user_bubble" to "#e6d2ae", "dim" to "#3a2f1f",
    ),
    "green" to mapOf(
        "mode" to "light", "bg" to "#e9f2e4", "card" to "#f6fbf3", "card_hi" to "#dcebd4",
        "text" to "#2f3d2c", "muted" to "#6f8567", "accent" to "#3f7d4f",
        "char_bubble" to "#e6f1e0", "user_bubble" to "#cbe4c0", "dim" to "#22301c",
    ),
)

const val DANGER = "#e5484d"
const val OK = "#30a46c"

val FONT_OPTIONS: List<Pair<String, String>> = listOf(
    "" to "跟随系统",
    "sans-serif" to "黑体",
    "sans-serif-light" to "细黑",
    "sans-serif-medium" to "中黑",
    "serif" to "衬线（宋体风）",
    "monospace" to "等宽",
    "casual" to "手写风",
)

/** 字号档：相邻档要能一眼看出差别（0.9→1.0 只差 10%），所以拉开成 0.85/1.0/1.25/1.55/2.0。 */
const val SCALE_MIN = 0.85
const val SCALE_MAX = 2.0
val FONT_SCALES: List<Pair<String, String>> = listOf(
    "0.85" to "小", "1.0" to "标准", "1.25" to "大", "1.55" to "特大", "2.0" to "超大",
)

val OPACITIES: List<Pair<String, String>> = listOf(
    "1.0" to "不透明", "0.85" to "轻微透明", "0.7" to "半透明",
    "0.55" to "很透明", "0.0" to "完全透明",
)

/** 背景图的缩放方式。 */
val BG_FITS: List<Pair<String, String>> = listOf(
    "cover" to "填充裁切（铺满）",
    "contain" to "完整显示（留边）",
    "fill" to "拉伸铺满",
    "repeat" to "平铺重复",
    "center" to "原尺寸居中",
)

val BG_ALIGNS: List<Pair<String, String>> = listOf(
    "center" to "居中", "top" to "顶部", "bottom" to "底部",
)

/** 位置预设（对应 Alignment 的 x/y，取 -1~1）：粗粒化点选，再配合滑杆微调。 */
val BG_POSITIONS: List<Triple<Double, Double, String>> = listOf(
    Triple(0.0, 0.0, "正中"),
    Triple(0.0, -1.0, "上"),
    Triple(0.0, 1.0, "下"),
    Triple(-1.0, 0.0, "左"),
    Triple(1.0, 0.0, "右"),
    Triple(-1.0, -1.0, "左上"),
    Triple(1.0, -1.0, "右上"),
    Triple(-1.0, 1.0, "左下"),
    Triple(1.0, 1.0, "右下"),
)

const val BG_ZOOM_MIN = 1.0
const val BG_ZOOM_MAX = 4.0

/** 取色板：不懂色号就点一下。 */
val BUBBLE_SWATCHES: List<String> = listOf(
    "#3A2F6E", "#2B5278", "#1F6F5C", "#7A4E2D", "#6B3A5A",
    "#3F4652", "#D9D2FF", "#CFE6C6", "#F0E5CF", "#E9ECF2",
)

val TEXT_SWATCHES: List<String> = listOf(
    "#E6E8EC", "#1C1F26", "#FFD479", "#A5D8FF", "#B2F2BB", "#FFC9C9", "#E5C7FF", "#FFE8CC",
)

/** 外观设置（和接口设置分开存，互不影响）。 */
@Serializable
data class Appearance(
    val theme: String = "dark",
    val fontFamily: String = "",
    val fontScale: Double = 1.0,
    val bubbleUser: String = "",
    val bubbleChar: String = "",
    val bubbleOpacity: Double = 1.0,
    val textColor: String = "",
    val bgImage: String = "",
    val bgFit: String = "cover",
    val bgAlign: String = "center",
    val bgX: Double = 0.0,
    val bgY: Double = 0.0,
    val bgZoom: Double = 1.0,
    val bgDimOn: Boolean = false,   // 压暗是否启用（默认关：背景原色显示）
    val bgDim: Double = 0.35,       // 仅当 bgDimOn 为 true 时生效
    val messageWidth: Double = 0.82,
    val bottomBarAlpha: Double = 1.0,   // 底部信息栏底色透明度（1 = 不透明）
    val topBarAlpha: Double = 1.0,      // 顶部信息栏底色透明度
    val showModel: Boolean = true,
    val showTokens: Boolean = true,
    val showWords: Boolean = true,
    val showTime: Boolean = true,
)

/** 主题与外观的纯函数（不依赖任何界面框架，可单独测试）。 */
object Theme {

    private fun round2(value: Double): Double = Math.rint(value * 100.0) / 100.0

    /** 夹取非法值，避免手改文件把界面搞崩。 */
    fun normalize(appearance: Appearance): Appearance {
        val themeKeys = THEMES.map { it.first }.toSet()
        val fontKeys = FONT_OPTIONS.map { it.first }.toSet()
        val fitKeys = BG_FITS.map { it.first }.toSet()
        val alignKeys = BG_ALIGNS.map { it.first }.toSet()
        return appearance.copy(
            theme = if (appearance.theme in themeKeys) appearance.theme else "dark",
            fontFamily = if (appearance.fontFamily in fontKeys) appearance.fontFamily else "",
            fontScale = round2(clamp(appearance.fontScale, SCALE_MIN, SCALE_MAX)),
            bubbleUser = normalizeColor(appearance.bubbleUser),
            bubbleChar = normalizeColor(appearance.bubbleChar),
            bubbleOpacity = round2(clamp(appearance.bubbleOpacity, 0.0, 1.0)),
            textColor = normalizeColor(appearance.textColor),
            bgImage = appearance.bgImage,
            bgFit = if (appearance.bgFit in fitKeys) appearance.bgFit else "cover",
            bgAlign = if (appearance.bgAlign in alignKeys) appearance.bgAlign else "center",
            bgX = round2(clamp(appearance.bgX, -1.0, 1.0, default = 0.0)),
            bgY = round2(clamp(appearance.bgY, -1.0, 1.0, default = 0.0)),
            bgZoom = round2(clamp(appearance.bgZoom, BG_ZOOM_MIN, BG_ZOOM_MAX)),
            bgDim = round2(clamp(appearance.bgDim, 0.0, 0.85)),
            messageWidth = round2(clamp(appearance.messageWidth, 0.5, 1.0)),
        )
    }

    /** 数字夹取；不是数字就用默认值（对应 Python 里 `float(value)` 失败的情况）。 */
    fun clamp(value: Any?, low: Double, high: Double, default: Double = 1.0): Double {
        val number = when (value) {
            is Number -> value.toDouble()
            is String -> value.toDoubleOrNull()
            else -> null
        } ?: default
        if (number.isNaN()) return default
        return maxOf(low, minOf(high, number))
    }

    /** 只接受 #RGB / #RRGGBB / #AARRGGBB，其他一律当空。 */
    fun normalizeColor(value: String?): String {
        var text = (value ?: "").trim()
        if (text.isEmpty()) return ""
        if (!text.startsWith("#")) text = "#$text"
        var body = text.substring(1)
        if (body.length !in listOf(3, 6, 8)) return ""
        if (body.any { it !in "0123456789abcdefABCDEF" }) return ""
        if (body.length == 3) body = body.map { "$it$it" }.joinToString("")
        return "#" + body.uppercase()
    }

    fun palette(themeName: String): Map<String, String> = PALETTES[themeName] ?: PALETTES.getValue("dark")

    /** 由色板自己的 mode 决定亮/暗（不要硬编码主题名单）。 */
    fun themeMode(themeName: String): String =
        if (palette(themeName)["mode"] == "light") "light" else "dark"

    /** 给 Material 配色用的显式值（避免浅色主题下"浅底浅字"）。 */
    fun colorSchemeValues(appearance: Appearance): Map<String, String> {
        val colors = palette(appearance.theme)
        return mapOf(
            "surface" to colors.getValue("card"),
            "on_surface" to colors.getValue("text"),
            "primary" to colors.getValue("accent"),
            "on_primary" to "#FFFFFF",
            "secondary" to colors.getValue("accent"),
            "on_secondary" to "#FFFFFF",
            "on_surface_variant" to colors.getValue("muted"),
            "outline" to colors.getValue("muted"),
        )
    }

    /** 轻提示的（底色，字色）——字色必须跟着主题走，否则浅色主题上白字看不见。 */
    fun toastColors(appearance: Appearance, error: Boolean): Pair<String, String> {
        if (error) return DANGER to "#FFFFFF"
        val colors = palette(appearance.theme)
        return colors.getValue("card_hi") to colors.getValue("text")
    }

    /** 把颜色变成带透明度的 #AARRGGBB。 */
    fun withOpacity(color: String, opacity: Double): String {
        val base = normalizeColor(color)
        if (base.isEmpty()) return ""
        val alpha = pyRound(clamp(opacity, 0.0, 1.0) * 255)
        var body = base.substring(1)
        if (body.length == 8) body = body.substring(2)  // 已经有 alpha，替换掉
        return "#" + alpha.toString(16).uppercase().padStart(2, '0') + body
    }

    /** 气泡底色：优先用自定义颜色，再套透明度（自定义写法不认就退回主题默认）。 */
    fun bubbleColor(appearance: Appearance, isUser: Boolean): String {
        val colors = palette(appearance.theme)
        val custom = normalizeColor(if (isUser) appearance.bubbleUser else appearance.bubbleChar)
        val base = custom.ifEmpty {
            colors.getValue(if (isUser) "user_bubble" else "char_bubble")
        }
        return withOpacity(base, appearance.bubbleOpacity).ifEmpty { base }
    }

    /** 消息正文字色（没设或写错就用主题默认）。 */
    fun textColorFor(appearance: Appearance, default: String): String =
        normalizeColor(appearance.textColor).ifEmpty { default }

    fun boxFitName(appearance: Appearance): String = when (appearance.bgFit) {
        "cover" -> "COVER"
        "contain" -> "CONTAIN"
        "fill" -> "FILL"
        "repeat", "center" -> "NONE"  // 平铺用原尺寸 + repeat 属性
        else -> "COVER"
    }

    fun repeatsImage(appearance: Appearance): Boolean = appearance.bgFit == "repeat"

    fun alignmentName(appearance: Appearance): String = when (appearance.bgAlign) {
        "top" -> "TOP_CENTER"
        "bottom" -> "BOTTOM_CENTER"
        else -> "CENTER"
    }

    /** 把当前位置讲成人话（例如「居中」「靠左、靠上」）。 */
    fun positionLabel(appearance: Appearance): String {
        val x = appearance.bgX
        val y = appearance.bgY
        if (Math.abs(x) < 0.15 && Math.abs(y) < 0.15) return "居中"
        val parts = mutableListOf<String>()
        if (x <= -0.5) parts += "靠左" else if (x >= 0.5) parts += "靠右"
        if (y <= -0.5) parts += "靠上" else if (y >= 0.5) parts += "靠下"
        return if (parts.isEmpty()) "略偏" else parts.joinToString("、")
    }

    fun zoomLabel(appearance: Appearance): String =
        String.format(java.util.Locale.ROOT, "%.1f×", appearance.bgZoom)

    /** 背景图上的压暗层；没有背景图就不压。 */
    fun dimColor(appearance: Appearance): String {
        if (appearance.bgImage.isEmpty()) return ""
        return withOpacity(palette(appearance.theme).getValue("dim"), appearance.bgDim)
    }

    /**
     * 按字号档缩放，并保证不会小到看不清。
     * 边界只用 SCALE_MIN/SCALE_MAX 一份，避免"最大两档变成一样大"。
     */
    fun scaled(base: Int, scale: Double): Int =
        maxOf(9, pyRound(base * clamp(scale, SCALE_MIN, SCALE_MAX)))

    fun fontFamilyOrNull(appearance: Appearance): String? =
        appearance.fontFamily.ifEmpty { null }
}
