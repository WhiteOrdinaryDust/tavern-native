package com.tavern.domain.backend

import com.tavern.domain.models.ApiSettings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

const val DEFAULT_TIMEOUT = 180.0

/** 各服务商放思维链的字段名（尽量都认）。 */
val REASONING_KEYS = listOf(
    "reasoning_content", "reasoning", "reasoning_details", "thinking_content", "thinking",
)

val REASONING_EFFORTS = listOf("", "none", "low", "high", "max")
val THINKING_MODES = listOf("", "enabled", "disabled")

/** 流式增量：`kind` 取 `reasoning` / `content` / `usage`。 */
data class Chunk(val kind: String, val text: String = "", val usage: Map<String, Any?>? = null)

/** 带用户可读中文说明的后端错误。 */
class BackendException(message: String) : RuntimeException(message)

/** 一行 SSE 解码的结果。 */
sealed interface StreamEvent {
    /** 空行 / 注释 / 非 data 行。 */
    data object Ignore : StreamEvent

    /** `data: [DONE]`。 */
    data object Done : StreamEvent

    /** 这一行解出来的增量（可能有思考、正文、用量）。 */
    data class Chunks(val chunks: List<Chunk>) : StreamEvent
}

/**
 * OpenAI 兼容后端的核心逻辑（纯函数部分，可单独测试）。
 *
 * 覆盖 DeepSeek / OpenAI / OpenRouter / LM Studio / Ollama / llama.cpp server 等
 * 所有提供 `/v1/chat/completions` 的服务。真正的 HTTP 传输在 `:net`（OkHttp）。
 */
object Backend {

    private val json = Json { ignoreUnknownKeys = true }

    /** 把各种写法的 base_url 归一成完整的 chat/completions 地址。 */
    fun normalizeEndpoint(baseUrl: String?): String {
        val url = (baseUrl ?: "").trim().trimEnd('/')
        if (url.isEmpty()) throw BackendException("还没有填写 API 地址")
        if (url.endsWith("/chat/completions")) return url
        // 只有协议 + 主机（例如 https://api.deepseek.com）时补 /v1
        if (url.count { it == '/' } == 2) return "$url/v1/chat/completions"
        return "$url/chat/completions"
    }

    fun modelsEndpoint(baseUrl: String?): String {
        val full = normalizeEndpoint(baseUrl)
        return full.substring(0, full.length - "/chat/completions".length) + "/models"
    }

    fun headers(settings: ApiSettings): Map<String, String> {
        val headers = mutableMapOf(
            "Content-Type" to "application/json",
            "Accept" to "application/json",
        )
        val key = settings.apiKey.trim()
        if (key.isNotEmpty()) headers["Authorization"] = "Bearer $key"
        return headers
    }

    /** 把 HTTP 错误变成一句能看懂的话（并给出常见原因的提示）。 */
    fun formatHttpError(status: Int, body: String?): String {
        var detail = ""
        try {
            val obj = json.parseToJsonElement(body ?: "") as? JsonObject
            val err = obj?.get("error") ?: obj
            detail = when (err) {
                is JsonObject -> {
                    val message = (err["message"] as? JsonPrimitive)?.content
                    val extra = (err["detail"] as? JsonPrimitive)?.content
                    message ?: extra ?: err.toString()
                }
                is JsonPrimitive -> err.content
                else -> (body ?: "").trim()
            }
        } catch (_: Exception) {
            detail = (body ?: "").trim()
        }
        detail = detail.trim().take(300)

        val hints = mapOf(
            400 to "（请求被拒绝：模型名或参数不被接受；若填了「额外请求参数」，检查是否有服务端不认识的字段）",
            401 to "（API Key 无效或未填写）",
            403 to "（没有权限访问该模型）",
            404 to "（接口路径不对，检查 base_url 是否需要 /v1，或模型名不存在）",
            422 to "（参数不被接受）",
            429 to "（触发限流或额度不足，稍后重试）",
        )
        val hint = hints[status] ?: if (status >= 500) "（服务端错误，稍后重试）" else ""
        return "HTTP $status $hint $detail".trim()
    }

    /** 解析「额外请求参数」JSON；非法时抛出可直接展示给用户的错误。 */
    fun parseExtraParams(raw: String?): JsonObject {
        val text = (raw ?: "").trim()
        if (text.isEmpty()) return JsonObject(emptyMap())
        val element = try {
            json.parseToJsonElement(text)
        } catch (exc: Exception) {
            throw BackendException("额外请求参数不是合法 JSON：${exc.message ?: "格式错误"}")
        }
        return element as? JsonObject
            ?: throw BackendException("""额外请求参数必须是一个 JSON 对象，例如 {"enable_thinking": true}""")
    }

    /** 组装请求体（独立成函数，便于断言参数是否真的带上）。 */
    fun buildPayload(
        settings: ApiSettings,
        messages: List<Map<String, String>>,
        stream: Boolean,
        maxTokens: Int? = null,
    ): JsonObject {
        val messagesJson = JsonArray(
            messages.map { message ->
                buildJsonObject {
                    message.forEach { (key, value) -> put(key, value) }
                }
            },
        )
        return buildJsonObject {
            put("model", settings.model.trim())
            put("messages", messagesJson)
            put("stream", stream)
        // 流式下 OpenAI 兼容接口默认**不回传用量**，必须显式请求；
        // 不回传的话气泡下的 tokens 永远是空的（这是实测踩到的坑）
        if (stream) {
            put("stream_options", buildJsonObject { put("include_usage", true) })
        }
            put("temperature", settings.temperature)
            if (settings.topP > 0) put("top_p", settings.topP)
            val limit = maxTokens ?: settings.maxTokens
            if (limit != 0) put("max_tokens", limit)

            // 思考开关 / 强度：只有显式设置过才发送
            val effort = settings.reasoningEffort.trim()
            if (effort.isNotEmpty()) put("reasoning_effort", effort)
            val thinking = settings.thinking.trim()
            if (thinking.isNotEmpty()) {
                put("thinking", buildJsonObject { put("type", thinking) })
            }

            // 服务商私有参数：合并进来，但不允许覆盖协议关键字段
            val extra = parseExtraParams(settings.extraParams)
            for ((key, value) in extra) {
                if (key == "messages" || key == "stream") continue
                put(key, value)
            }
            put("messages", messagesJson)
            put("stream", stream)
        }
    }

    // ------------------------------------------------------------------ 响应解析

    /** 把分段结构的 content 拆成 (正文, 思考)。 */
    private fun partsSplit(value: JsonElement?): Pair<String, String> {
        if (value !is JsonArray) return "" to ""
        val content = StringBuilder()
        val reasoning = StringBuilder()
        for (part in value) {
            if (part !is JsonObject) continue
            val type = ((part["type"] as? JsonPrimitive)?.content ?: "").lowercase()
            if (type == "thinking" || type == "reasoning" || type == "reasoning_content") {
                val text = textOf(part, "thinking", "text", "content")
                if (text.isNotEmpty()) reasoning.append(text)
            } else {
                val text = textOf(part, "text", "content")
                if (text.isNotEmpty()) content.append(text)
            }
        }
        return content.toString() to reasoning.toString()
    }

    private fun textOf(obj: JsonObject, vararg keys: String): String {
        for (key in keys) {
            val value = obj[key]
            if (value is JsonPrimitive && value.isString && value.content.isNotEmpty()) {
                return value.content
            }
        }
        return ""
    }

    /** 从 message / delta 里取出 (正文, 思考)。 */
    fun splitMessage(obj: JsonElement?): Pair<String, String> {
        if (obj !is JsonObject) return "" to ""
        var content: String
        var reasoning: String
        val raw = obj["content"]
        if (raw is JsonArray) {
            val split = partsSplit(raw)
            content = split.first
            reasoning = split.second
        } else if (raw is JsonPrimitive && raw.isString) {
            content = raw.content
            reasoning = ""
        } else {
            content = ""
            reasoning = ""
        }
        if (content.isEmpty()) {
            val fallback = obj["text"]
            if (fallback is JsonPrimitive && fallback.isString) content = fallback.content
        }

        for (key in REASONING_KEYS) {
            when (val value = obj[key]) {
                is JsonPrimitive -> if (value.isString && value.content.isNotEmpty()) {
                    reasoning += value.content
                }
                is JsonArray -> for (item in value) {
                    when (item) {
                        is JsonObject -> reasoning += textOf(item, "text", "summary", "content")
                        is JsonPrimitive -> if (item.isString) reasoning += item.content
                        else -> Unit
                    }
                }
                else -> Unit
            }
        }
        return content to reasoning
    }

    fun firstChoice(obj: JsonElement?): JsonObject? {
        val choices = (obj as? JsonObject)?.get("choices") as? JsonArray ?: return null
        return choices.firstOrNull() as? JsonObject
    }

    fun usageOf(obj: JsonElement?): Map<String, Any?>? {
        val usage = (obj as? JsonObject)?.get("usage") as? JsonObject ?: return null
        return usage.mapValues { (_, value) -> primitiveValue(value) }
    }

    private fun primitiveValue(value: JsonElement?): Any? = when (value) {
        is JsonPrimitive -> when {
            value.isString -> value.content
            value.booleanOrNull != null -> value.booleanOrNull
            value.intOrNull != null -> value.intOrNull
            value.doubleOrNull != null -> value.doubleOrNull
            else -> value.content
        }
        is JsonObject -> value.mapValues { (_, v) -> primitiveValue(v) }
        is JsonArray -> value.map { primitiveValue(it) }
        else -> null
    }

    /** 解一行 SSE。行为与 Flet 版逐行处理一致：忽略空行/注释/非 data 行，坏 JSON 跳过。 */
    fun decodeStreamLine(line: String?): StreamEvent {
        val trimmed = (line ?: "").trim()
        if (trimmed.isEmpty() || trimmed.startsWith(":")) return StreamEvent.Ignore
        if (!trimmed.startsWith("data:")) return StreamEvent.Ignore
        val data = trimmed.substring(5).trim()
        if (data == "[DONE]") return StreamEvent.Done
        val obj = try {
            json.parseToJsonElement(data)
        } catch (_: Exception) {
            return StreamEvent.Ignore
        }
        val error = (obj as? JsonObject)?.get("error")
        if (isTruthy(error)) {
            val message = when (error) {
                is JsonObject -> (error["message"] as? JsonPrimitive)?.content ?: error.toString()
                is JsonPrimitive -> error.content
                else -> error.toString()
            }
            throw BackendException("服务端返回错误：$message")
        }

        return StreamEvent.Chunks(chunksOf(obj, "delta"))
    }

    /** Python 式"真值"：null / 空串 / 空对象空数组都算假（`"error": null` 不该当错误）。 */
    private fun isTruthy(value: JsonElement?): Boolean = when (value) {
        null -> false
        is kotlinx.serialization.json.JsonNull -> false
        is JsonPrimitive -> value.content.isNotEmpty()
        is JsonObject -> value.isNotEmpty()
        is JsonArray -> value.isNotEmpty()
    }

    /** 非流式（服务端忽略 stream 参数时）整段 JSON → 增量序列。 */
    fun decodeFullResponse(body: String?): List<Chunk> {
        val obj = try {
            json.parseToJsonElement(body ?: "")
        } catch (exc: Exception) {
            throw BackendException("无法解析服务端返回：${(body ?: "").trim().take(200)}")
        }
        val message = firstChoice(obj)?.get("message")
        return chunksOf(obj, null, message)
    }

    private fun chunksOf(obj: JsonElement?, deltaKey: String?, message: JsonElement? = null): List<Chunk> {
        val chunks = mutableListOf<Chunk>()
        val source = message ?: (deltaKey?.let { firstChoice(obj)?.get(it) })
        val split = splitMessage(source)
        if (split.second.isNotEmpty()) chunks += Chunk("reasoning", split.second)
        if (split.first.isNotEmpty()) chunks += Chunk("content", split.first)
        usageOf(obj)?.let { chunks += Chunk("usage", usage = it) }
        return chunks
    }

    /** 从 /models 响应里取模型 id（服务端不支持时返回空）。 */
    fun parseModelIds(body: String?): List<String> {
        val obj = try {
            json.parseToJsonElement(body ?: "") as? JsonObject
        } catch (_: Exception) {
            null
        } ?: return emptyList()
        val items = obj["data"] as? JsonArray ?: return emptyList()
        return items
            .mapNotNull { (it as? JsonObject)?.get("id") as? JsonPrimitive }
            .filter { it.isString }
            .map { it.content }
            .toSortedSet()
            .toList()
    }
}
