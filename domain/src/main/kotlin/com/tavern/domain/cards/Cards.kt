package com.tavern.domain.cards

import com.tavern.domain.models.Character
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Base64
import java.util.zip.CRC32
import java.util.zip.Inflater

/**
 * SillyTavern 角色卡读写：PNG(tEXt/iTXt) 与 JSON，兼容 V1 / V2 规范。
 *
 * 只用 JDK 自带能力（Base64 / CRC32 / Inflater）+ JSON 库，不依赖图片库，Android 上也一样。
 * 与 Flet 版 `tavern/cards.py` 行为一致。
 */
object Cards {

    val PNG_SIG: ByteArray = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
    )
    val CARD_KEYWORDS = listOf("chara", "ccv3")

    private val pretty = Json { prettyPrint = true; prettyPrintIndent = "  " }

    // ------------------------------------------------------------------ 字节小工具

    fun hasPngSignature(data: ByteArray): Boolean {
        if (data.size < PNG_SIG.size) return false
        for (i in PNG_SIG.indices) if (data[i] != PNG_SIG[i]) return false
        return true
    }

    private fun beInt(data: ByteArray, pos: Int): Long {
        var value = 0L
        for (i in 0 until 4) value = (value shl 8) or (data[pos + i].toLong() and 0xFF)
        return value
    }

    /** 遍历 PNG 块（类型, 数据）。 */
    fun iterPngChunks(data: ByteArray): List<Pair<String, ByteArray>> {
        val chunks = mutableListOf<Pair<String, ByteArray>>()
        if (!hasPngSignature(data)) return chunks
        var pos = 8
        while (pos + 12 <= data.size) {
            val length = beInt(data, pos).toInt()
            val ctype = String(data, pos + 4, 4, Charsets.ISO_8859_1)
            val start = pos + 8
            val end = minOf(start + length, data.size)
            chunks += ctype to data.copyOfRange(start, end)
            pos += 12 + length
            if (ctype == "IEND") break
        }
        return chunks
    }

    // ------------------------------------------------------------------ 读

    private fun parseObject(text: String): JsonObject? = try {
        Json.parseToJsonElement(text) as? JsonObject
    } catch (_: Exception) {
        null
    }

    private fun decodePayload(raw: ByteArray): JsonObject? {
        val text = String(raw, Charsets.UTF_8).trim()
        if (text.isEmpty()) return null
        // 角色卡通常是 base64 编码的 JSON；也容忍直接是 JSON 的情况
        val compact = text.filterNot { it.isWhitespace() }
        val padded = compact + "=".repeat((4 - compact.length % 4) % 4)
        try {
            val decoded = String(Base64.getMimeDecoder().decode(padded), Charsets.UTF_8)
            parseObject(decoded)?.let { return it }
        } catch (_: Exception) {
            // 不是 base64，继续按纯 JSON 试
        }
        return parseObject(text)
    }

    /** 从 PNG 字节里取出角色卡 JSON；不是角色卡 PNG 则返回 null。 */
    fun extractCardJson(data: ByteArray): JsonObject? {
        for ((ctype, body) in iterPngChunks(data)) {
            when (ctype) {
                "tEXt" -> {
                    val keyword = chunkKeyword(body)
                    if (keyword.lowercase() in CARD_KEYWORDS) {
                        val sep = body.indexOf(0.toByte())
                        if (sep < 0) continue
                        val card = decodePayload(body.copyOfRange(sep + 1, body.size))
                        if (card != null) return card
                    }
                }
                "iTXt" -> {
                    val keyword = chunkKeyword(body)
                    if (keyword.lowercase() !in CARD_KEYWORDS) continue
                    val sep = body.indexOf(0.toByte())
                    if (sep < 0 || sep + 3 > body.size) continue
                    val compressed = body[sep + 1].toInt() == 1
                    var rest = body.copyOfRange(sep + 3, body.size)  // 跳过压缩标志与压缩方法
                    rest = afterNul(rest)                            // 语言标签
                    rest = afterNul(rest)                            // 翻译后的关键词
                    val text = if (compressed) {
                        try {
                            inflate(rest)
                        } catch (_: Exception) {
                            continue
                        }
                    } else {
                        rest
                    }
                    val card = decodePayload(text)
                    if (card != null) return card
                }
            }
        }
        return null
    }

    private fun chunkKeyword(body: ByteArray): String {
        val sep = body.indexOf(0.toByte())
        val end = if (sep < 0) body.size else sep
        return String(body, 0, end, Charsets.UTF_8).trim()
    }

    private fun afterNul(data: ByteArray): ByteArray {
        val sep = data.indexOf(0.toByte())
        return if (sep < 0) ByteArray(0) else data.copyOfRange(sep + 1, data.size)
    }

    private fun inflate(data: ByteArray): ByteArray {
        val inflater = Inflater()
        inflater.setInput(data)
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        try {
            while (!inflater.finished()) {
                val n = inflater.inflate(buffer)
                if (n == 0 && inflater.needsInput()) break
                out.write(buffer, 0, n)
            }
        } finally {
            inflater.end()
        }
        return out.toByteArray()
    }

    /** 从文件字节里解析角色卡：先按 PNG 试，再按 JSON 试。 */
    fun loadCardBytes(data: ByteArray): JsonObject? {
        if (hasPngSignature(data)) return extractCardJson(data)
        val text = String(data, Charsets.UTF_8)
        return when (val element = tryParse(text)) {
            is JsonArray -> element.firstOrNull() as? JsonObject
            is JsonObject -> element
            else -> null
        }
    }

    private fun tryParse(text: String): JsonElement? = try {
        Json.parseToJsonElement(text)
    } catch (_: Exception) {
        null
    }

    // ------------------------------------------------------------------ 规范化

    private fun firstString(data: JsonObject, vararg keys: String, default: String = ""): String {
        for (key in keys) {
            val value = data[key]
            if (value is JsonPrimitive && value.isString && value.content.isNotBlank()) {
                return value.content
            }
        }
        return default
    }

    private fun stringList(value: JsonElement?): List<String> {
        val array = value as? JsonArray ?: return emptyList()
        return array.mapNotNull { element ->
            val primitive = element as? JsonPrimitive ?: return@mapNotNull null
            if (!primitive.isString) return@mapNotNull null
            primitive.content.takeIf { it.isNotBlank() }
        }
    }

    /** 把 V1 扁平结构或 V2 `{spec, data}` 结构统一成 Character。 */
    fun normalizeCard(raw: JsonObject): Character {
        val inner = raw["data"] as? JsonObject
        val useInner = inner != null && (
            raw["spec"] != null || firstString(inner, "name").isNotEmpty() ||
                firstString(inner, "description").isNotEmpty()
            )
        val data = if (useInner) inner!! else raw

        return Character(
            name = (firstString(data, "name").ifEmpty { firstString(raw, "name") }.ifEmpty { "未命名角色" }).trim(),
            description = firstString(data, "description"),
            personality = firstString(data, "personality"),
            scenario = firstString(data, "scenario"),
            firstMes = firstString(data, "first_mes", "first_message"),
            mesExample = firstString(data, "mes_example", "example_dialogue"),
            systemPrompt = firstString(data, "system_prompt"),
            postHistoryInstructions = firstString(data, "post_history_instructions"),
            creator = firstString(data, "creator"),
            creatorNotes = firstString(data, "creator_notes", "creatorcomment"),
            tags = stringList(data["tags"]),
            alternateGreetings = stringList(data["alternate_greetings"]),
            raw = raw.toPlainMap(),
        )
    }

    /** 只保留能放进 Map<String, Any?> 的标量/嵌套结构（供 room 存原始 JSON）。 */
    private fun JsonObject.toPlainMap(): Map<String, Any?> =
        entries.associate { (key, value) -> key to value.toPlainValue() }

    private fun JsonElement.toPlainValue(): Any? = when (this) {
        is JsonPrimitive -> when {
            isString -> content
            content == "true" -> true
            content == "false" -> false
            else -> content.toDoubleOrNull() ?: content
        }
        is JsonObject -> toPlainMap()
        is JsonArray -> map { it.toPlainValue() }
        else -> null
    }

    // ------------------------------------------------------------------ 写

    /** 生成符合 V2 规范的角色卡 JSON（尽量保留导入时的原始字段）。 */
    fun buildV2Card(char: Character, book: JsonObject? = null): JsonObject {
        val base = rawToJson(char.raw)
        val data = buildJsonObject {
            (base["data"] as? JsonObject)?.let { inner -> inner.forEach { (k, v) -> put(k, v) } }
                ?: base.forEach { (k, v) -> put(k, v) }
            put("name", char.name)
            put("description", char.description)
            put("personality", char.personality)
            put("scenario", char.scenario)
            put("first_mes", char.firstMes)
            put("mes_example", char.mesExample)
            put("creator", char.creator)
            put("creator_notes", char.creatorNotes)
            put("tags", JsonArray(char.tags.map { JsonPrimitive(it) }))
            put("alternate_greetings", JsonArray(char.alternateGreetings.map { JsonPrimitive(it) }))
            put("character_version", (base["data"] as? JsonObject)?.get("character_version") ?: JsonPrimitive("1.0"))
            if (char.systemPrompt.isNotEmpty()) put("system_prompt", char.systemPrompt)
            if (char.postHistoryInstructions.isNotEmpty()) {
                put("post_history_instructions", char.postHistoryInstructions)
            }
            if (book != null) put("character_book", book)
        }
        return buildJsonObject {
            put("spec", "chara_card_v2")
            put("spec_version", "2.0")
            put("data", data)
        }
    }

    private fun rawToJson(raw: Map<String, Any?>): JsonObject = buildJsonObject {
        for ((key, value) in raw) put(key, anyToJson(value))
    }

    private fun anyToJson(value: Any?): JsonElement = when (value) {
        null -> JsonPrimitive("")
        is Boolean -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        is String -> JsonPrimitive(value)
        is Map<*, *> -> buildJsonObject {
            for ((k, v) in value) if (k is String) put(k, anyToJson(v))
        }
        is List<*> -> JsonArray(value.map { anyToJson(it) })
        else -> JsonPrimitive(value.toString())
    }

    fun cardToJsonBytes(char: Character, book: JsonObject? = null): ByteArray =
        pretty.encodeToString(JsonObject.serializer(), buildV2Card(char, book)).toByteArray(Charsets.UTF_8)

    private fun pngChunk(ctype: String, payload: ByteArray): ByteArray {
        val type = ctype.toByteArray(Charsets.ISO_8859_1)
        val crc = CRC32()
        crc.update(type)
        crc.update(payload)
        val out = java.io.ByteArrayOutputStream()
        out.write(byteArrayOf(
            ((payload.size ushr 24) and 0xFF).toByte(),
            ((payload.size ushr 16) and 0xFF).toByte(),
            ((payload.size ushr 8) and 0xFF).toByte(),
            (payload.size and 0xFF).toByte(),
        ))
        out.write(type)
        out.write(payload)
        val value = crc.value
        out.write(byteArrayOf(
            ((value ushr 24) and 0xFF).toByte(),
            ((value ushr 16) and 0xFF).toByte(),
            ((value ushr 8) and 0xFF).toByte(),
            (value and 0xFF).toByte(),
        ))
        return out.toByteArray()
    }

    /** 把角色卡写回 PNG 的 `chara` tEXt 块（插在 IEND 之前），实现与酒馆互导。 */
    fun embedCardInPng(png: ByteArray, char: Character): ByteArray {
        if (!hasPngSignature(png)) throw IllegalArgumentException("不是合法的 PNG 文件")
        val payload = Base64.getEncoder().encode(cardToJsonBytes(char))
        val chunk = pngChunk("tEXt", "chara ".toByteArray(Charsets.ISO_8859_1) + payload)

        val out = java.io.ByteArrayOutputStream()
        out.write(PNG_SIG)
        for ((ctype, body) in iterPngChunks(png)) {
            val keyword = chunkKeyword(body).lowercase()
            val isCardChunk = (ctype == "tEXt" || ctype == "iTXt") && keyword in CARD_KEYWORDS
            if (isCardChunk) continue  // 去掉已有的 chara/ccv3 块，避免重复
            if (ctype == "IEND") out.write(chunk)
            out.write(pngChunk(ctype, body))
        }
        return out.toByteArray()
    }
}
