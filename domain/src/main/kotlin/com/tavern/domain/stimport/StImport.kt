package com.tavern.domain.stimport

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.roundToInt

/**
 * 导入 SillyTavern 的预设与正则脚本。
 *
 * 只做能**无损映射**的部分，映射不了的在提示里说明清楚 —— 避免"看起来导进来了、实际没生效"。
 * 与 Flet 版 `tavern/st_import.py` 行为一致。
 */
object StImport {

    /** ST「应用位置」枚举：1=用户输入 2=AI 回复 3=斜杠命令 4=世界书 5=推理内容。 */
    const val PLACEMENT_AI_OUTPUT = 2

    private val SLASH_REGEX = Regex("^/(?<body>.*)/(?<flags>[a-z]*)$", RegexOption.DOT_MATCHES_ALL)

    /** ST 预设里本 App 认识的采样参数（其余一律忽略并提示）。 */
    private val SUPPORTED_SAMPLERS: Map<String, List<String>> = linkedMapOf(
        "temperature" to listOf("temp", "temperature"),
        "top_p" to listOf("top_p"),
        "max_tokens" to listOf("openai_max_tokens", "max_tokens", "max_length"),
    )

    fun loadJson(data: ByteArray): JsonElement? = try {
        Json.parseToJsonElement(String(data, Charsets.UTF_8))
    } catch (_: Exception) {
        null
    }

    /**
     * 把 ST 的 `/pattern/flags` 拆成 (pattern, flags)。
     * JS 的 `g` 在这里不需要（Kotlin 的 replace 默认全局替换）。
     */
    fun parseRegexLiteral(value: String?): Pair<String, String> {
        val text = (value ?: "").trim()
        val match = SLASH_REGEX.matchEntire(text)
        val body = match?.groups?.get("body")?.value ?: text
        val rawFlags = match?.groups?.get("flags")?.value ?: ""
        var flags = ""
        for (flag in rawFlags) {
            if (flag in "imsx" && !flags.contains(flag)) flags += flag
        }
        return body to flags
    }

    /** 把一个 ST 正则脚本转成一行清洗规则；第二个返回值是说明/跳过原因。 */
    fun scriptToRule(script: JsonElement?): Pair<String?, String> {
        val obj = script as? JsonObject ?: return null to "不是有效的脚本对象"
        if (obj.truthy("disabled")) return null to "脚本已禁用"
        if (obj.truthy("promptOnly")) return null to "只作用于提示词（本 App 的清洗只处理回复正文）"

        val placement = obj["placement"] as? JsonArray
        if (placement != null && placement.isNotEmpty()) {
            val values = placement.mapNotNull { (it as? JsonPrimitive)?.content?.toIntOrNull() }
            if (PLACEMENT_AI_OUTPUT !in values) return null to "只作用于用户输入或其它位置"
        }

        val (pattern, flags) = parseRegexLiteral((obj["findRegex"] as? JsonPrimitive)?.content ?: "")
        if (pattern.isEmpty()) return null to "没有 findRegex"

        val body = if (flags.isNotEmpty()) "/$pattern/$flags" else pattern
        val replacement = (obj["replaceString"] as? JsonPrimitive)?.content ?: ""
        val rule = if (replacement.isEmpty()) body else "$body => $replacement"

        val notes = mutableListOf<String>()
        if (obj.truthy("trimStrings")) notes += "trimStrings 未支持"
        if (obj.truthy("markdownOnly")) notes += "原脚本只用于显示层（这里会写进消息正文）"
        return rule to notes.joinToString("；")
    }

    /**
     * Python 的「真值」判断：true/非空字符串/非空数组对象/非零数字都算真。
     * 不能用 booleanOrNull —— 那样 `trimStrings: [...]` 会被当成 false（真机导过 ST 预设的都会踩）。
     */
    private fun JsonObject.truthy(key: String): Boolean {
        val value = this[key] ?: return false
        return when (value) {
            is JsonPrimitive -> when {
                value.isString -> value.content.isNotEmpty()
                value.content == "true" -> true
                value.content == "false" -> false
                else -> value.content.isNotEmpty() && value.content != "0"
            }
            is JsonArray -> value.isNotEmpty()
            is JsonObject -> value.isNotEmpty()
        }
    }

    private fun iterScripts(raw: JsonElement?): List<JsonElement> {
        if (raw is JsonArray) return raw
        if (raw !is JsonObject) return emptyList()
        (raw["scripts"] as? JsonArray)?.let { return it }
        (raw["regex_scripts"] as? JsonArray)?.let { return it }
        val extensions = raw["extensions"] as? JsonObject
        (extensions?.get("regex_scripts") as? JsonArray)?.let { return it }
        return emptyList()
    }

    /** 把 ST 的正则脚本集合转成规则行列表（`raw` 可以是脚本数组或完整预设）。 */
    fun scriptsToRules(raw: JsonElement?): Pair<List<String>, List<String>> {
        val rules = mutableListOf<String>()
        val notes = mutableListOf<String>()
        for (script in iterScripts(raw)) {
            val (rule, note) = scriptToRule(script)
            if (rule != null) rules += rule
            if (note.isNotEmpty()) {
                val name = when (script) {
                    is JsonObject -> listOf("scriptName", "name")
                        .mapNotNull { (script[it] as? JsonPrimitive)?.content }
                        .firstOrNull { it.isNotBlank() } ?: ""
                    else -> ""
                }
                notes += "${name.ifEmpty { "未命名脚本" }}：$note"
            }
        }
        return rules to notes
    }

    private fun samplerSources(raw: JsonObject): List<JsonObject> {
        val sources = mutableListOf(raw)
        for (key in listOf("settings", "params", "sampler", "samplers")) {
            (raw[key] as? JsonObject)?.let { sources += it }
        }
        return sources
    }

    /** 从 ST 预设里取出本 App 支持的采样参数。 */
    fun extractSamplers(raw: JsonElement?): Pair<Map<String, Double>, List<String>> {
        val obj = raw as? JsonObject ?: return emptyMap<String, Double>() to emptyList()
        val sources = samplerSources(obj)

        fun pick(names: List<String>): Double? {
            for (source in sources) {
                for (name in names) {
                    val primitive = source[name] as? JsonPrimitive ?: continue
                    if (primitive.isString) continue
                    primitive.doubleOrNull?.let { return it }
                }
            }
            return null
        }

        val out = linkedMapOf<String, Double>()
        for ((field, names) in SUPPORTED_SAMPLERS) {
            val value = pick(names) ?: continue
            out[field] = if (field == "max_tokens") value.roundToInt().toDouble() else value
        }

        val known = SUPPORTED_SAMPLERS.values.flatten().toSet()
        val unsupported = obj.entries
            .filter { (key, value) ->
                key !in known && (value as? JsonPrimitive)?.let { !it.isString && it.doubleOrNull != null } == true
            }
            .map { it.key }
            .sorted()
        val notes = mutableListOf<String>()
        if (unsupported.isNotEmpty()) {
            notes += "这些采样参数本 App 不支持，已忽略：" + unsupported.take(8).joinToString("、")
        }
        return out to notes
    }
}
