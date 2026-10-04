package com.tavern.domain.search

import com.tavern.domain.models.ApiSettings
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

val PROVIDERS: List<Pair<String, String>> = listOf(
    "tavily" to "Tavily",
    "bocha" to "博查 Bocha",
    "custom" to "自定义 JSON 端点",
)

const val DEFAULT_TIMEOUT = 20.0
const val TAVILY_URL = "https://api.tavily.com/search"
const val BOCHA_URL = "https://api.bochaai.com/v1/web-search"

/** 检索失败（调用方自己决定要不要忽略这一轮）。 */
class SearchException(message: String) : RuntimeException(message)

data class SearchResult(val title: String, val url: String, val snippet: String)

/**
 * 联网检索：没有服务端，所以走「检索 → 注入提示词」这条路。
 * 解析器都是纯函数（HTTP 那层放在 `:net`），行为与 Flet 版 `tavern/search.py` 一致。
 */
object Search {

    fun providerLabel(value: String): String =
        PROVIDERS.toMap()[value] ?: "自定义 JSON 端点"

    fun endpointFor(settings: ApiSettings): String = when (settings.searchProvider) {
        "tavily" -> TAVILY_URL
        "bocha" -> BOCHA_URL
        else -> settings.searchUrl.trim()
    }

    /** 构造检索请求：返回 (url, headers, json_body)。 */
    fun buildRequest(
        settings: ApiSettings,
        query: String,
        limit: Int = 5,
    ): Triple<String, Map<String, String>, JsonObject> {
        val url = endpointFor(settings)
        if (url.isEmpty()) throw SearchException("没有填检索接口地址")
        val key = settings.searchApiKey.trim()
        val headers = mutableMapOf("Content-Type" to "application/json")
        if (key.isNotEmpty()) headers["Authorization"] = "Bearer $key"
        val count = maxOf(1, minOf(10, limit))
        val body = buildJsonObject {
            when (settings.searchProvider) {
                "tavily" -> {
                    put("query", query)
                    put("max_results", count)
                    put("search_depth", "basic")
                    if (key.isNotEmpty()) put("api_key", key)  // 老版本参数，带上更兼容
                }
                "bocha" -> {
                    put("query", query)
                    put("count", count)
                    put("summary", true)
                }
                else -> {
                    put("query", query)
                    put("limit", count)
                }
            }
        }
        return Triple(url, headers, body)
    }

    private val DEFAULT_LIST_KEYS = listOf("results", "items", "value", "webPages", "data", "list")

    /** 在常见的返回结构里找结果数组（不同服务商字段名不一样）。 */
    private fun firstList(data: JsonElement?, keys: List<String> = emptyList()): List<JsonElement> {
        if (data is JsonArray) return data
        if (data !is JsonObject) return emptyList()
        for (key in keys.ifEmpty { DEFAULT_LIST_KEYS }) {
            when (val value = data[key]) {
                is JsonArray -> if (value.isNotEmpty()) return value
                is JsonObject -> {
                    val found = firstList(value, keys)
                    if (found.isNotEmpty()) return found
                }
                else -> Unit
            }
        }
        return emptyList()
    }

    private fun text(item: JsonObject, vararg keys: String): String {
        for (key in keys) {
            val value = item[key]
            if (value is JsonPrimitive) {
                val content = value.content
                if (content.isNotBlank()) return content.trim()
            }
        }
        return ""
    }

    /** 把检索响应解析成 [{title, url, snippet}]。 */
    fun parseResults(data: JsonElement?, limit: Int = 5): List<SearchResult> {
        val items = firstList(data)
        val out = mutableListOf<SearchResult>()
        for (item in items.take(maxOf(1, limit))) {
            if (item !is JsonObject) continue
            val title = text(item, "title", "name")
            val url = text(item, "url", "link")
            val snippet = text(item, "content", "snippet", "summary", "description")
            if (title.isEmpty() && snippet.isEmpty()) continue
            out += SearchResult(title, url, snippet)
        }
        return out
    }

    /** 把检索结果拼成给模型看的资料块（超长截断）。 */
    fun formatContext(results: List<SearchResult>, maxChars: Int = 1500): String {
        if (results.isEmpty()) return ""
        val lines = mutableListOf(
            "【联网检索结果】",
            "以下是刚刚联网查到的资料，可能不完整或有误；只在与当前对话相关时自然引用，不相关就忽略。",
        )
        var used = 0
        val limit = maxOf(200, maxChars)
        var added = 0
        for ((index, item) in results.withIndex()) {
            val number = index + 1
            val title = item.title.ifEmpty { "（无标题）" }
            var block = "$number. $title"
            if (item.url.isNotEmpty()) block += "（${item.url}）"
            if (item.snippet.isNotEmpty()) block += "\n   ${item.snippet}"
            if (added > 0 && used + block.length > limit) {
                lines += "（后续结果略）"
                break
            }
            if (used + block.length > limit) {
                // 第一条无论如何都留一点，别让模型只看到"后续结果略"
                block = block.take(maxOf(0, limit - used)).trimEnd() + "…"
            }
            lines += block
            used += block.length
            added += 1
        }
        return lines.joinToString("\n")
    }
}
