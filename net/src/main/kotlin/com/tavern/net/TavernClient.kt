package com.tavern.net

import com.tavern.domain.backend.Backend
import com.tavern.domain.backend.BackendException
import com.tavern.domain.backend.Chunk
import com.tavern.domain.backend.DEFAULT_TIMEOUT
import com.tavern.domain.backend.StreamEvent
import com.tavern.domain.models.ApiSettings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/** 可取消的请求句柄。 */
fun interface Cancelable {
    fun cancel()
}

/**
 * OpenAI 兼容后端客户端（OkHttp + SSE）。
 *
 * 解析逻辑全部复用 `:domain` 里已经测过的纯函数（`Backend.decodeStreamLine` 等），
 * 这里只负责网络与线程：回调都在后台线程，界面自己切回主线程。
 */
class TavernClient(
    private val client: OkHttpClient = defaultClient(),
) {

    private val json = Json { ignoreUnknownKeys = true }
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    /** 流式对话：思考 / 正文 / 用量逐段回调。 */
    fun streamChat(
        settings: ApiSettings,
        messages: List<Map<String, String>>,
        onChunk: (Chunk) -> Unit,
        onError: (String) -> Unit,
        onDone: () -> Unit,
    ): Cancelable {
        val url = try {
            Backend.normalizeEndpoint(settings.baseUrl)
        } catch (exc: BackendException) {
            onError(exc.message ?: "API 地址不对")
            return Cancelable { }
        }
        val payload = Backend.buildPayload(settings, messages, stream = true)
        val body = json.encodeToString(JsonObject.serializer(), payload).toRequestBody(jsonType)
        val request = Request.Builder()
            .url(url)
            .headers(okhttp3.Headers.Builder().apply {
                Backend.headers(settings).forEach { (k, v) -> add(k, v) }
                // 流式请求要显式声明接受事件流
                set("Accept", "text/event-stream")
            }.build())
            .post(body)
            .build()

        val call = client.newCall(request)
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (call.isCanceled()) return
                onError("网络错误：${e::class.simpleName}: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { resp ->
                    if (resp.code >= 400) {
                        val text = try {
                            resp.body?.string() ?: ""
                        } catch (_: Exception) {
                            ""
                        }
                        onError(Backend.formatHttpError(resp.code, text))
                        return
                    }
                    val contentType = resp.header("Content-Type")?.lowercase() ?: ""
                    val source = resp.body?.source()
                    if (source == null) {
                        onError("服务端没有返回内容")
                        return
                    }
                    try {
                        if ("event-stream" !in contentType) {
                            // 服务端忽略了 stream：整段 JSON 一次解析
                            val text = source.readUtf8()
                            Backend.decodeFullResponse(text).forEach(onChunk)
                            onDone()
                            return
                        }
                        while (true) {
                            val line = source.readUtf8Line() ?: break
                            when (val event = Backend.decodeStreamLine(line)) {
                                is StreamEvent.Ignore -> Unit
                                is StreamEvent.Done -> {
                                    onDone()
                                    return
                                }
                                is StreamEvent.Chunks -> event.chunks.forEach(onChunk)
                            }
                        }
                        onDone()
                    } catch (exc: BackendException) {
                        onError(exc.message ?: "服务端返回错误")
                    } catch (exc: Exception) {
                        if (!call.isCanceled()) onError("读取流失败：${exc.message}")
                    }
                }
            }
        })
        return Cancelable { call.cancel() }
    }

    /** 非流式短请求（测试连接、生成摘要）。 */
    fun complete(
        settings: ApiSettings,
        messages: List<Map<String, String>>,
        maxTokens: Int,
        onResult: (String) -> Unit,
        onError: (String) -> Unit,
    ): Cancelable {
        val url = try {
            Backend.normalizeEndpoint(settings.baseUrl)
        } catch (exc: BackendException) {
            onError(exc.message ?: "API 地址不对")
            return Cancelable { }
        }
        val payload = Backend.buildPayload(settings, messages, stream = false, maxTokens = maxTokens)
        val body = json.encodeToString(JsonObject.serializer(), payload).toRequestBody(jsonType)
        val request = Request.Builder()
            .url(url)
            .headers(okhttp3.Headers.Builder().apply {
                Backend.headers(settings).forEach { (k, v) -> add(k, v) }
            }.build())
            .post(body)
            .build()
        val call = client.newCall(request)
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!call.isCanceled()) onError("网络错误：${e::class.simpleName}: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { resp ->
                    val text = try {
                        resp.body?.string() ?: ""
                    } catch (_: Exception) {
                        ""
                    }
                    if (resp.code >= 400) {
                        onError(Backend.formatHttpError(resp.code, text))
                        return
                    }
                    try {
                        val chunks = Backend.decodeFullResponse(text)
                        onResult(chunks.filter { it.kind == "content" }.joinToString("") { it.text })
                    } catch (exc: BackendException) {
                        onError(exc.message ?: "解析失败")
                    }
                }
            }
        })
        return Cancelable { call.cancel() }
    }

    /**
     * 通用 JSON POST（联网检索这类一次性请求用）。
     *
     * 请求体由调用方给（`Search.buildRequest` 已经算好 url/headers/body），
     * 这里只负责发出去并把响应文本回传。
     */
    fun postJson(
        url: String,
        headers: Map<String, String>,
        body: String,
        onResult: (String) -> Unit,
        onError: (String) -> Unit,
    ): Cancelable {
        val request = Request.Builder()
            .url(url)
            .headers(okhttp3.Headers.Builder().apply {
                headers.forEach { (k, v) -> add(k, v) }
            }.build())
            .post(body.toRequestBody(jsonType))
            .build()
        val call = client.newCall(request)
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!call.isCanceled()) onError("${e::class.simpleName}: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { resp ->
                    val text = try {
                        resp.body?.string() ?: ""
                    } catch (_: Exception) {
                        ""
                    }
                    if (resp.code >= 400) {
                        onError("HTTP ${resp.code}：${text.take(120)}")
                    } else {
                        onResult(text)
                    }
                }
            }
        })
        return Cancelable { call.cancel() }
    }

    /**
     * 阻塞版 JSON POST（联网检索用）。
     *
     * 调用方保证在后台线程执行 —— 这样"先搜再答"不用把整条流式回调链拆开。
     * 失败返回 null（检索失败不该中断这一轮对话）。
     */
    fun postJsonBlocking(url: String, headers: Map<String, String>, body: String): String? = try {
        val request = Request.Builder()
            .url(url)
            .headers(okhttp3.Headers.Builder().apply {
                headers.forEach { (k, v) -> add(k, v) }
            }.build())
            .post(body.toRequestBody(jsonType))
            .build()
        client.newCall(request).execute().use { resp ->
            val text = resp.body?.string() ?: ""
            if (resp.code >= 400) null else text
        }
    } catch (_: Exception) {
        null
    }

    /** 尝试拉模型列表（服务端不支持时给空列表，不算错误）。 */
    fun listModels(settings: ApiSettings, onResult: (List<String>) -> Unit): Cancelable {
        val url = try {
            Backend.modelsEndpoint(settings.baseUrl)
        } catch (_: BackendException) {
            onResult(emptyList())
            return Cancelable { }
        }
        val request = Request.Builder()
            .url(url)
            .headers(okhttp3.Headers.Builder().apply {
                Backend.headers(settings).forEach { (k, v) -> add(k, v) }
            }.build())
            .get()
            .build()
        val call = client.newCall(request)
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = onResult(emptyList())

            override fun onResponse(call: Call, response: Response) {
                response.use { resp ->
                    if (resp.code >= 400) {
                        onResult(emptyList())
                        return
                    }
                    val text = try {
                        resp.body?.string() ?: ""
                    } catch (_: Exception) {
                        ""
                    }
                    onResult(Backend.parseModelIds(text))
                }
            }
        })
        return Cancelable { call.cancel() }
    }

    /** 测试连接：先试 /models，不行再发一个最小请求。 */
    fun probe(settings: ApiSettings, onResult: (Boolean, String) -> Unit): Cancelable {
        val extraError = try {
            Backend.parseExtraParams(settings.extraParams)
            null
        } catch (exc: BackendException) {
            exc.message
        }
        if (extraError != null) {
            onResult(false, extraError)
            return Cancelable { }
        }
        val modelsCall = listModels(settings) { models ->
            if (models.isNotEmpty()) {
                val preview = models.take(5).joinToString("、") + if (models.size > 5) "…" else ""
                onResult(true, "连接正常，可用模型 ${models.size} 个：$preview")
            } else {
                complete(
                    settings, listOf(mapOf("role" to "user", "content" to "ping")), 8,
                    onResult = { text ->
                        onResult(true, "连接正常（模型已响应：${text.trim().take(40).ifEmpty { "空回复" }}）")
                    },
                    onError = { message -> onResult(false, message) },
                )
            }
        }
        return modelsCall
    }

    companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(DEFAULT_TIMEOUT.toLong(), TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}
