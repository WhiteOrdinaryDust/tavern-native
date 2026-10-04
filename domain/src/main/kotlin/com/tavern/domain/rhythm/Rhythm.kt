package com.tavern.domain.rhythm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * 节奏控制：约束「一轮回复覆盖多长时间」和「能不能替玩家做决定」。
 *
 * 角色扮演最烦人的两种失败：时间跨度失控、越权替玩家做决定。
 * 这里不靠自动检测，而是让用户在输入栏上方直接拨档，每轮组装提示词时注入一段短协议。
 * 与 Flet 版 `tavern/rhythm.py` 行为一致。
 */
object Rhythm {

    /** 时间粒度：一轮回复最多覆盖多长时间。 */
    val TIME_GEARS: List<Pair<String, String>> = listOf(
        "instant" to "瞬",
        "scene" to "场",
        "act" to "幕",
        "span" to "间",
    )

    /** 动作权限：能不能替玩家做决定。 */
    val AUTHORITIES: List<Pair<String, String>> = listOf(
        "strict" to "严格回合",
        "semi" to "半自动",
        "director" to "导演",
    )

    const val DEFAULT_TIME = "scene"
    const val DEFAULT_AUTHORITY = "strict"
    const val DISABLED = "off"

    private val TIME_RULES = mapOf(
        "instant" to "秒级——只描写这一下的即时结果（1~3 句），不要推进到后续回合",
        "scene" to "分钟级——描写这一段互动的完整过程（1~2 段），不要跨到下一件事",
        "act" to "小时级——可以概括一段时间内的进展（2~3 段），细节从简",
        "span" to "天/周级——用一段话概括这段时间发生了什么，不展开细节",
    )

    private val AUTHORITY_RULES = mapOf(
        "strict" to (
            "严格回合——只写你自己（角色）的言行与反应。" +
                "玩家的动作、台词、选择一律由玩家自己决定：不要代替玩家说话、行动或做决定。" +
                "玩家只做了一个动作时，你就只回应这一个动作"
            ),
        "semi" to (
            "半自动——可以描写环境变化和旁观者的反应，也可以顺势做一点衔接（例如玩家伸手时递上东西），" +
                "但不要替玩家做选择、不要替玩家说话或决定行动"
            ),
        "director" to (
            "导演模式——为了推进剧情，可以替玩家做一些无关紧要的小动作与过渡（例如『你顺手把门带上』），" +
                "但重大选择、对话内容和关键行动仍然留给玩家"
        ),
    )

    /** 给界面用的短说明（工具提示太长会挤，这里做成一句话）。 */
    val TIME_HINTS = mapOf(
        "instant" to "一轮只覆盖几秒：战斗单回合、关键对话",
        "scene" to "一轮覆盖几分钟：日常互动、探索",
        "act" to "一轮覆盖几小时：场景过渡、赶路",
        "span" to "一轮概括几天：时间跳跃、休整",
    )

    val AUTHORITY_HINTS = mapOf(
        "strict" to "战斗、谈判、高张力场景：绝不替你做决定",
        "semi" to "日常互动：可以写环境和旁人反应",
        "director" to "过场：允许替你做一些无关紧要的小动作",
    )

    fun normalizeTime(value: String?): String =
        if (TIME_GEARS.any { it.first == value }) value!! else DEFAULT_TIME

    fun normalizeAuthority(value: String?): String =
        if (AUTHORITIES.any { it.first == value }) value!! else DEFAULT_AUTHORITY

    fun timeLabel(value: String?): String =
        TIME_GEARS.toMap()[value] ?: TIME_GEARS.toMap().getValue(DEFAULT_TIME)

    fun authorityLabel(value: String?): String =
        AUTHORITIES.toMap()[value] ?: AUTHORITIES.toMap().getValue(DEFAULT_AUTHORITY)

    /** 生成注入 system prompt 的节奏协议（放在最后，离用户输入最近）。 */
    fun buildRhythmBlock(
        timeGear: String = DEFAULT_TIME,
        authority: String = DEFAULT_AUTHORITY,
        enabled: Boolean = true,
        overrides: Map<String, String> = emptyMap(),
    ): String {
        if (!enabled) return ""
        val gear = normalizeTime(timeGear)
        val level = normalizeAuthority(authority)
        return listOf(
            "【本轮节奏】",
            "时间粒度：${timeLabel(gear)}档 —— ${rule(overrides, "time:$gear", TIME_RULES.getValue(gear))}。",
            "动作权限：${rule(overrides, "authority:$level", AUTHORITY_RULES.getValue(level))}。",
            "你这一轮覆盖的时间不能超过玩家输入所暗示的范围；写完就停下来，等玩家下一轮。",
        ).joinToString("\n")
    }

    /** 覆盖项为空串时视为没写，仍用内置文案。 */
    private fun rule(overrides: Map<String, String>, key: String, fallback: String): String =
        overrides[key]?.takeIf { it.isNotBlank() } ?: fallback

    /** 覆盖项的键（正好是时间粒度 4 个 + 动作权限 3 个 = 七个按钮）。 */
    fun overrideKeys(): List<Pair<String, String>> =
        TIME_GEARS.map { (key, label) -> "time:$key" to "时间·$label" } +
            AUTHORITIES.map { (key, label) -> "authority:$key" to "权限·$label" }

    /** 每个按钮的默认文案（界面里作为输入框的占位/初值）。 */
    fun defaultText(key: String): String = when {
        key.startsWith("time:") -> TIME_RULES[normalizeTime(key.removePrefix("time:"))].orEmpty()
        key.startsWith("authority") -> AUTHORITY_RULES[normalizeAuthority(key.removePrefix("authority:"))].orEmpty()
        else -> ""
    }

    /** 把界面里编辑的覆盖项存成 JSON（空值不写，保持配置精简）。 */
    fun overridesToJson(overrides: Map<String, String>): String {
        val kept = overrides.filterValues { it.isNotBlank() }
        if (kept.isEmpty()) return "{}"
        return kept.entries.joinToString(",", "{", "}") { (k, v) ->
            "\"" + k + "\":" + Json.encodeToString(JsonPrimitive(v))
        }
    }

    /** 读回覆盖项；坏 JSON 一律当成没有覆盖（不因为配置损坏就用不了）。 */
    fun parseOverrides(json: String?): Map<String, String> {
        if (json.isNullOrBlank() || json == "{}") return emptyMap()
        return try {
            Json.parseToJsonElement(json).jsonObject
                .mapNotNull { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { k to it } }
                .toMap()
        } catch (_: Exception) {
            emptyMap()
        }
    }

    /**
     * 输出字数上下限（0 表示不限制）。返回一句可追加到 system prompt 的规则，两端都为 0 时返回空串。
     */
    fun lengthRule(minChars: Int, maxChars: Int): String {
        val low = minChars.coerceAtLeast(0)
        val high = maxChars.coerceAtLeast(0)
        return when {
            low == 0 && high == 0 -> ""
            low == 0 -> "回复长度不超过 $high 字。"
            high == 0 -> "回复长度不少于 $low 字。"
            low > high -> "回复长度不少于 $high 字、不超过 $low 字。"
            low == high -> "回复长度控制在 $low 字左右。"
            else -> "回复长度控制在 $low–$high 字之间。"
        }
    }
}
