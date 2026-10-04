package com.tavern.domain.backend

import com.tavern.domain.models.ApiSettings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** 对应 Flet 版 `tavern/backends.py` 的参数组装与解析部分。 */
class BackendTest {

    private fun parse(text: String) = Json.parseToJsonElement(text)

    @Test
    fun normalizeEndpointFillsMissingPath() {
        assertEquals(
            "https://api.deepseek.com/v1/chat/completions",
            Backend.normalizeEndpoint("https://api.deepseek.com"),
            "只有主机时补 /v1",
        )
        assertEquals(
            "https://api.deepseek.com/v1/chat/completions",
            Backend.normalizeEndpoint("https://api.deepseek.com/v1"),
        )
        assertEquals(
            "http://192.168.1.5:1234/v1/chat/completions",
            Backend.normalizeEndpoint("http://192.168.1.5:1234/v1/"),
            "末尾斜杠要去掉",
        )
        assertEquals(
            "https://x/v1/chat/completions",
            Backend.normalizeEndpoint("https://x/v1/chat/completions"),
            "已经写全了就原样",
        )
        assertFailsWith<BackendException> { Backend.normalizeEndpoint("   ") }
    }

    @Test
    fun modelsEndpointReplacesTail() {
        assertEquals("https://api.deepseek.com/v1/models", Backend.modelsEndpoint("https://api.deepseek.com/v1"))
        assertEquals("https://api.deepseek.com/v1/models", Backend.modelsEndpoint("https://api.deepseek.com"))
    }

    @Test
    fun headersCarryKeyWhenPresent() {
        assertTrue(!Backend.headers(ApiSettings()).containsKey("Authorization"))
        assertEquals("Bearer k1", Backend.headers(ApiSettings(apiKey = " k1 "))["Authorization"])
        assertEquals("application/json", Backend.headers(ApiSettings())["Content-Type"])
    }

    @Test
    fun parseExtraParams() {
        assertTrue(Backend.parseExtraParams("").isEmpty())
        assertEquals(1, Backend.parseExtraParams("""{"enable_thinking":true}""").size)
        val bad = assertFailsWith<BackendException> { Backend.parseExtraParams("{不是 JSON") }
        assertTrue(bad.message!!.contains("不是合法 JSON"), bad.message!!)
        val array = assertFailsWith<BackendException> { Backend.parseExtraParams("[1,2]") }
        assertTrue(array.message!!.contains("必须是一个 JSON 对象"), array.message!!)
    }

    @Test
    fun buildPayloadBasicFields() {
        val payload = Backend.buildPayload(
            ApiSettings(model = " deepseek-flash ", temperature = 1.0, maxTokens = 4096),
            listOf(mapOf("role" to "user", "content" to "你好")),
            stream = true,
        )
        assertEquals("deepseek-flash", (payload["model"] as JsonPrimitive).content)
        assertEquals(true, (payload["stream"] as JsonPrimitive).content.toBoolean())
        assertEquals(1.0, (payload["temperature"] as JsonPrimitive).content.toDouble())
        assertEquals(4096, (payload["max_tokens"] as JsonPrimitive).content.toInt())
        assertTrue(payload["top_p"] == null, "top_p 为 0 时不发送")
        val messages = parse(payload["messages"].toString())
        assertEquals(1, (messages as kotlinx.serialization.json.JsonArray).size)
    }

    @Test
    fun buildPayloadThinkingOnlyWhenExplicit() {
        val plain = Backend.buildPayload(ApiSettings(thinking = "", reasoningEffort = ""), emptyList(), stream = true)
        assertTrue(plain["thinking"] == null && plain["reasoning_effort"] == null, "默认不发送，免得别的服务商 400")

        val on = Backend.buildPayload(
            ApiSettings(thinking = "enabled", reasoningEffort = " high "),
            emptyList(),
            stream = true,
        )
        assertEquals("high", (on["reasoning_effort"] as JsonPrimitive).content)
        assertEquals("enabled", ((on["thinking"] as kotlinx.serialization.json.JsonObject)["type"] as JsonPrimitive).content)
    }

    @Test
    fun buildPayloadTopPAndMaxTokensOverride() {
        val payload = Backend.buildPayload(
            ApiSettings(topP = 0.9, maxTokens = 4096),
            emptyList(),
            stream = false,
            maxTokens = 16,
        )
        assertEquals(0.9, (payload["top_p"] as JsonPrimitive).content.toDouble())
        assertEquals(16, (payload["max_tokens"] as JsonPrimitive).content.toInt(), "总结/测试连接时能覆盖总预算")
    }

    @Test
    fun buildPayloadExtraParamsCannotBreakProtocol() {
        val payload = Backend.buildPayload(
            ApiSettings(extraParams = """{"enable_thinking":true,"stream":false,"messages":[]}"""),
            listOf(mapOf("role" to "user", "content" to "x")),
            stream = true,
        )
        assertEquals(true, (payload["enable_thinking"] as JsonPrimitive).content.toBoolean(), "私有参数要合并进来")
        assertEquals(true, (payload["stream"] as JsonPrimitive).content.toBoolean(), "协议字段不能被覆盖")
        assertEquals(1, (parse(payload["messages"].toString()) as kotlinx.serialization.json.JsonArray).size)
    }

    @Test
    fun httpErrorMessagesCarryHints() {
        val unauthorized = Backend.formatHttpError(401, """{"error":{"message":"Invalid API key"}}""")
        assertTrue(unauthorized.startsWith("HTTP 401"), unauthorized)
        assertTrue(unauthorized.contains("API Key 无效"), unauthorized)
        assertTrue(unauthorized.contains("Invalid API key"), unauthorized)

        assertTrue(Backend.formatHttpError(404, "").contains("接口路径不对"))
        assertTrue(Backend.formatHttpError(500, "boom").contains("服务端错误"))
        assertEquals("HTTP 418  teapot", Backend.formatHttpError(418, "teapot"), "没见过的状态码只给原始信息")
        assertTrue(Backend.formatHttpError(400, "x".repeat(500)).length < 400, "长正文要截断")
    }

    @Test
    fun splitMessageHandlesShapes() {
        assertEquals("你好" to "", Backend.splitMessage(parse("""{"content":"你好"}""")))
        assertEquals("" to "想一下", Backend.splitMessage(parse("""{"reasoning_content":"想一下"}""")))
        // 分段结构：thinking 归思考，其余归正文
        val parts = Backend.splitMessage(
            parse("""{"content":[{"type":"thinking","text":"先想"},{"type":"text","text":"再说"}]}"""),
        )
        assertEquals("再说" to "先想", parts)
        // reasoning 是数组且元素是对象
        assertEquals(
            "" to "甲乙",
            Backend.splitMessage(parse("""{"reasoning":[{"text":"甲"},{"summary":"乙"}]}""")),
        )
        // content 为空时退回 text 字段
        assertEquals("备用" to "", Backend.splitMessage(parse("""{"text":"备用"}""")))
        assertEquals("" to "", Backend.splitMessage(JsonPrimitive("不是对象")))
    }

    @Test
    fun decodeStreamLineHandlesSseForms() {
        assertEquals(StreamEvent.Ignore, Backend.decodeStreamLine(""))
        assertEquals(StreamEvent.Ignore, Backend.decodeStreamLine(": keep-alive"))
        assertEquals(StreamEvent.Ignore, Backend.decodeStreamLine("event: message"))
        assertEquals(StreamEvent.Done, Backend.decodeStreamLine("data: [DONE]"))
        assertEquals(StreamEvent.Ignore, Backend.decodeStreamLine("data: {坏 JSON"))

        val content = Backend.decodeStreamLine("""data: {"choices":[{"delta":{"content":"你"}}]}""")
        assertEquals(listOf(Chunk("content", "你")), (content as StreamEvent.Chunks).chunks)

        val reasoning = Backend.decodeStreamLine("""data: {"choices":[{"delta":{"reasoning_content":"想"}}]}""")
        assertEquals(listOf(Chunk("reasoning", "想")), (reasoning as StreamEvent.Chunks).chunks)

        // 同一行里既有思考又有正文：先思考后正文
        val both = Backend.decodeStreamLine(
            """data: {"choices":[{"delta":{"reasoning_content":"想","content":"说"}}]}""",
        )
        assertEquals(listOf(Chunk("reasoning", "想"), Chunk("content", "说")), (both as StreamEvent.Chunks).chunks)

        val usage = Backend.decodeStreamLine("""data: {"usage":{"prompt_tokens":10}}""")
        val usageChunk = (usage as StreamEvent.Chunks).chunks.single()
        assertEquals("usage", usageChunk.kind)
        assertEquals(10, usageChunk.usage!!["prompt_tokens"])

        // "error": null 不是错误（Python 的真值语义）
        assertEquals(StreamEvent.Chunks(emptyList()), Backend.decodeStreamLine("""data: {"error":null,"choices":[]}"""))
        val failed = assertFailsWith<BackendException> {
            Backend.decodeStreamLine("""data: {"error":{"message":"额度不足"}}""")
        }
        assertEquals("服务端返回错误：额度不足", failed.message)
    }

    @Test
    fun decodeFullResponseForNonStreamingServer() {
        val chunks = Backend.decodeFullResponse(
            """{"choices":[{"message":{"content":"回答","reasoning_content":"思考"}}],"usage":{"completion_tokens":5}}""",
        )
        assertEquals(listOf("reasoning", "content", "usage"), chunks.map { it.kind })
        assertEquals("思考", chunks[0].text)
        assertEquals("回答", chunks[1].text)
        assertEquals(5, chunks[2].usage!!["completion_tokens"])
        assertTrue(Backend.decodeFullResponse("""{"choices":[{"message":{"content":"只有正文"}}]}""").size == 1)
        assertFailsWith<BackendException> { Backend.decodeFullResponse("不是 JSON") }
    }

    @Test
    fun parseModelIds() {
        val ids = Backend.parseModelIds(
            """{"data":[{"id":"deepseek-chat"},{"id":"deepseek-reasoner"},{"id":"deepseek-chat"},{"no_id":1}]}""",
        )
        assertEquals(listOf("deepseek-chat", "deepseek-reasoner"), ids, "去重并排序")
        assertTrue(Backend.parseModelIds("""{"error":"nope"}""").isEmpty())
        assertTrue(Backend.parseModelIds("不是 JSON").isEmpty())
        assertTrue(Backend.parseModelIds(null).isEmpty())
    }

    @Test
    fun effortAndThinkingOptionLists() {
        assertEquals(listOf("", "none", "low", "high", "max"), REASONING_EFFORTS)
        assertEquals(listOf("", "enabled", "disabled"), THINKING_MODES)
        assertTrue(REASONING_KEYS.contains("reasoning_content"))
    }
}
