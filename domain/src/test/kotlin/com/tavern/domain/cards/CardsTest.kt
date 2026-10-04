package com.tavern.domain.cards

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.CRC32
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 对应 Flet 版 `tavern/cards.py` 与 tests/test_core.py 的角色卡部分。 */
class CardsTest {

    // 造一张最小可用的 PNG（只要签名 + 若干块就够，读取逻辑不校验图像数据）
    private fun chunk(type: String, payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        val t = type.toByteArray(Charsets.ISO_8859_1)
        fun be(value: Long) = byteArrayOf(
            ((value ushr 24) and 0xFF).toByte(),
            ((value ushr 16) and 0xFF).toByte(),
            ((value ushr 8) and 0xFF).toByte(),
            (value and 0xFF).toByte(),
        )
        out.write(be(payload.size.toLong()))
        out.write(t)
        out.write(payload)
        val crc = CRC32().apply { update(t); update(payload) }
        out.write(be(crc.value))
        return out.toByteArray()
    }

    private fun png(vararg extra: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(Cards.PNG_SIG)
        out.write(chunk("IHDR", ByteArray(13)))
        for (e in extra) out.write(e)
        out.write(chunk("IDAT", byteArrayOf(1, 2, 3)))
        out.write(chunk("IEND", ByteArray(0)))
        return out.toByteArray()
    }

    private fun v2Card(name: String = "林月"): JsonObject = buildJsonObject {
        put("spec", "chara_card_v2")
        put("spec_version", "2.0")
        putJsonObject("data") {
            put("name", name)
            put("description", "酒馆老板娘")
            put("personality", "温柔")
            put("scenario", "雨夜")
            put("first_mes", "*擦着杯子* 又来啦？")
            put("mes_example", "{{user}}: 在吗")
            put("creator", "某人")
            put("creator_notes", "备注")
            putJsonArray("tags") { add(JsonPrimitive("日常")); add(JsonPrimitive("  ")) }
            putJsonArray("alternate_greetings") { add(JsonPrimitive("另一个开场")) }
        }
    }

    private fun textChunk(keyword: String, json: String, encodeBase64: Boolean = true): ByteArray {
        val payload = if (encodeBase64) {
            Base64.getEncoder().encodeToString(json.toByteArray(Charsets.UTF_8))
        } else {
            json
        }
        // base64 是纯 ASCII；直接塞 JSON 时按 UTF-8 写（读的一侧按 UTF-8 解）
        val charset = if (encodeBase64) Charsets.ISO_8859_1 else Charsets.UTF_8
        return chunk("tEXt", "$keyword\u0000$payload".toByteArray(charset))
    }

    @Test
    fun readsV2CardFromPngTextChunk() {
        val data = png(textChunk("chara", v2Card().toString()))
        val card = Cards.loadCardBytes(data)
        assertNotNull(card)
        val char = Cards.normalizeCard(card)
        assertEquals("林月", char.name)
        assertEquals("酒馆老板娘", char.description)
        assertEquals("温柔", char.personality)
        assertEquals("雨夜", char.scenario)
        assertEquals("*擦着杯子* 又来啦？", char.firstMes)
        assertEquals("某人", char.creator)
        assertEquals("备注", char.creatorNotes)
        assertEquals(listOf("日常"), char.tags, "空白标签要丢掉")
        assertEquals(listOf("另一个开场"), char.alternateGreetings)
        assertTrue(char.raw.isNotEmpty(), "原始卡片要留着")
    }

    @Test
    fun readsCcv3KeywordAndPlainJsonPayload() {
        assertNotNull(Cards.loadCardBytes(png(textChunk("ccv3", v2Card("阿伟").toString()))))
        // 不做 base64、直接塞 JSON 也要认
        val plain = Cards.loadCardBytes(png(textChunk("chara", v2Card("小满").toString(), encodeBase64 = false)))
        assertEquals("小满", Cards.normalizeCard(assertNotNull(plain)).name)
    }

    @Test
    fun readsV1FlatCard() {
        val flat = buildJsonObject {
            put("name", "扁平角色")
            put("description", "V1 结构")
            put("first_mes", "你好")
            put("mes_example", "示例")
        }
        val char = Cards.normalizeCard(Cards.loadCardBytes(flat.toString().toByteArray())!!)
        assertEquals("扁平角色", char.name)
        assertEquals("V1 结构", char.description)
        assertEquals("你好", char.firstMes)
    }

    @Test
    fun readsJsonArrayOfCards() {
        val array = "[${v2Card("第一张")}, ${v2Card("第二张")}]"
        val char = Cards.normalizeCard(Cards.loadCardBytes(array.toByteArray())!!)
        assertEquals("第一张", char.name)
    }

    @Test
    fun nonPngNonJsonGivesNull() {
        assertNull(Cards.loadCardBytes("这不是角色卡".toByteArray()))
        assertNull(Cards.loadCardBytes(png()))  // 普通 PNG：没有 chara 块
    }

    @Test
    fun defaultsWhenNameMissing() {
        val char = Cards.normalizeCard(buildJsonObject { put("description", "只有描述") })
        assertEquals("未命名角色", char.name)
        assertEquals("只有描述", char.description)
    }

    @Test
    fun firstMesFallsBackToFirstMessage() {
        val card = buildJsonObject { put("first_message", "老字段名") }
        assertEquals("老字段名", Cards.normalizeCard(card).firstMes)
    }

    @Test
    fun buildV2CardKeepsOriginalFields() {
        val original = Cards.normalizeCard(v2Card())
        val built = Cards.buildV2Card(original)
        assertEquals("chara_card_v2", (built["spec"] as JsonPrimitive).content)
        val data = built["data"] as JsonObject
        assertEquals("林月", (data["name"] as JsonPrimitive).content)
        assertEquals("1.0", (data["character_version"] as JsonPrimitive).content)
        assertNull(data["character_book"], "没给书就不该有 character_book")
    }

    @Test
    fun embedCardRoundTrip() {
        val original = Cards.normalizeCard(v2Card("往返"))
        val withCard = Cards.embedCardInPng(png(), original)
        val readBack = Cards.normalizeCard(assertNotNull(Cards.loadCardBytes(withCard)))
        assertEquals("往返", readBack.name)
        assertEquals("酒馆老板娘", readBack.description)

        // 再嵌一次不该留下两个 chara 块
        val twice = Cards.embedCardInPng(withCard, original)
        val cardChunks = Cards.iterPngChunks(twice).count { (type, body) ->
            (type == "tEXt" || type == "iTXt") && String(body, 0, 4) == "char"
        }
        assertEquals(1, cardChunks)
    }

    @Test
    fun embedRequiresValidPng() {
        assertFailsWith<IllegalArgumentException> {
            Cards.embedCardInPng("not a png".toByteArray(), Cards.normalizeCard(v2Card()))
        }
    }

    @Test
    fun compressedItxtIsRead() {
        val json = v2Card("压缩卡").toString()
        val packed = java.io.ByteArrayOutputStream().also { out ->
            val deflater = java.util.zip.Deflater()
            deflater.setInput(json.toByteArray(Charsets.UTF_8))
            deflater.finish()
            val buffer = ByteArray(1024)
            while (!deflater.finished()) out.write(buffer, 0, deflater.deflate(buffer))
            deflater.end()
        }.toByteArray()
        val payload = "chara\u0000".toByteArray(Charsets.ISO_8859_1) +
            byteArrayOf(1, 0) +           // 压缩标志=1、压缩方法=0
            "zh\u0000".toByteArray(Charsets.ISO_8859_1) +   // 语言标签
            "\u0000".toByteArray(Charsets.ISO_8859_1) +      // 翻译后的关键词
            packed
        val data = png(chunk("iTXt", payload))
        assertEquals("压缩卡", Cards.normalizeCard(assertNotNull(Cards.loadCardBytes(data))).name)
    }
}
