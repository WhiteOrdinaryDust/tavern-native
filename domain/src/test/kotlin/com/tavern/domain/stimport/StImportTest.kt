package com.tavern.domain.stimport

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 对应 Flet 版 `tavern/st_import.py`，并补上 `/pattern/flags` 的正确处理。 */
class StImportTest {

    @Test
    fun parseRegexLiteral() {
        assertEquals("foo" to "", StImport.parseRegexLiteral("foo"))
        assertEquals("foo" to "i", StImport.parseRegexLiteral("/foo/i"))
        assertEquals("a/b" to "m", StImport.parseRegexLiteral("/a/b/m"))
        // 按出现顺序保留 i/m/s/x（g 不需要、y 不支持），与 Python 版一致
        assertEquals("x" to "ism", StImport.parseRegexLiteral("/x/igsmy"))
        assertEquals("" to "", StImport.parseRegexLiteral(null))
    }

    private fun script(
        find: String,
        replace: String = "",
        disabled: Boolean = false,
        promptOnly: Boolean = false,
        placement: List<Int>? = null,
        name: String = "脚本",
    ): JsonObject = buildJsonObject {
        put("scriptName", name)
        put("findRegex", find)
        put("replaceString", replace)
        if (disabled) put("disabled", true)
        if (promptOnly) put("promptOnly", true)
        if (placement != null) putJsonArray("placement") { placement.forEach { add(JsonPrimitive(it)) } }
    }

    @Test
    fun scriptToRuleBuildsRuleLine() {
        assertEquals("foo => bar" to "", StImport.scriptToRule(script("foo", "bar")))
        // 没有替换串 = 删除匹配内容
        assertEquals("foo" to "", StImport.scriptToRule(script("foo")))
        // 带 flags 的写成 /pattern/flags，替换串保持 JS 的 $1
        assertEquals("/a(b)/i => $1!" to "", StImport.scriptToRule(script("/a(b)/i", "$1!")))
    }

    @Test
    fun scriptToRuleSkipsWhatCannotMap() {
        assertNull(StImport.scriptToRule(script("a", disabled = true)).first)
        assertTrue(StImport.scriptToRule(script("a", disabled = true)).second.contains("禁用"))
        assertNull(StImport.scriptToRule(script("a", promptOnly = true)).first)
        assertNull(StImport.scriptToRule(script("a", placement = listOf(1))).first, "只作用用户输入")
        assertNull(StImport.scriptToRule(script("")).first, "没有 findRegex")
        assertEquals(
            StImport.PLACEMENT_AI_OUTPUT,
            2,
        )
    }

    @Test
    fun scriptToRuleKeepsAiOutputPlacement() {
        assertEquals("a => b", StImport.scriptToRule(script("a", "b", placement = listOf(2))).first)
        // 同时作用于输入与回复 → 也要导入
        assertEquals("a => b", StImport.scriptToRule(script("a", "b", placement = listOf(1, 2))).first)
    }

    @Test
    fun scriptToRuleNotesExtraFeatures() {
        val obj = buildJsonObject {
            put("findRegex", "a")
            put("replaceString", "b")
            put("trimStrings", JsonArray(listOf(JsonPrimitive("x"))))
            put("markdownOnly", true)
        }
        val note = StImport.scriptToRule(obj).second
        assertTrue(note.contains("trimStrings"), note)
        assertTrue(note.contains("显示层"), note)
    }

    @Test
    fun scriptsToRulesAcceptsSeveralShapes() {
        val scripts = buildJsonArray {
            add(script("a", "1", name = "一号"))
            add(script("b", disabled = true, name = "二号"))
        }
        val (rules, notes) = StImport.scriptsToRules(scripts)
        assertEquals(listOf("a => 1"), rules)
        assertEquals(1, notes.size)
        assertTrue(notes[0].startsWith("二号："), notes[0])

        // 完整预设：scripts / regex_scripts / extensions.regex_scripts
        assertTrue(StImport.scriptsToRules(buildJsonObject { put("scripts", scripts) }).first.isNotEmpty())
        assertTrue(StImport.scriptsToRules(buildJsonObject { put("regex_scripts", scripts) }).first.isNotEmpty())
        assertTrue(
            StImport.scriptsToRules(
                buildJsonObject { putJsonObject("extensions") { put("regex_scripts", scripts) } }
            ).first.isNotEmpty()
        )
        assertTrue(StImport.scriptsToRules(JsonPrimitive("乱七八糟")).first.isEmpty())
    }

    @Test
    fun extractSamplers() {
        val preset = buildJsonObject {
            put("temp", 0.75)
            put("top_p", 0.9)
            put("openai_max_tokens", 2048)
            put("top_k", 40)
            put("min_p", 0.05)
            put("frequency_penalty", 0.1)
        }
        val (samplers, notes) = StImport.extractSamplers(preset)
        assertEquals(0.75, samplers["temperature"])
        assertEquals(0.9, samplers["top_p"])
        assertEquals(2048.0, samplers["max_tokens"])
        assertEquals(1, notes.size)
        assertTrue(notes[0].contains("frequency_penalty"), notes[0])
        assertTrue(notes[0].contains("top_k"), notes[0])
    }

    @Test
    fun extractSamplersReadsNestedSettings() {
        val preset = buildJsonObject {
            putJsonObject("settings") { put("temperature", 1.25) }
            putJsonObject("samplers") { put("max_length", 512) }
        }
        val (samplers, notes) = StImport.extractSamplers(preset)
        assertEquals(1.25, samplers["temperature"])
        assertEquals(512.0, samplers["max_tokens"])
        assertTrue(notes.isEmpty(), notes.toString())
    }

    @Test
    fun extractSamplersIgnoresNonNumbers() {
        val preset = buildJsonObject { put("temperature", "很高") }
        val (samplers, notes) = StImport.extractSamplers(preset)
        assertTrue(samplers.isEmpty())
        assertTrue(notes.isEmpty(), notes.toString())
        assertTrue(StImport.extractSamplers(null).first.isEmpty())
    }

    @Test
    fun loadJson() {
        assertEquals(
            "林月",
            ((StImport.loadJson("{\"name\":\"林月\"}".toByteArray()) as JsonObject)["name"] as JsonPrimitive).content,
        )
        assertNull(StImport.loadJson("不是 JSON".toByteArray()))
    }
}
