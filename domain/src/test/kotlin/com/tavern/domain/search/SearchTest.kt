package com.tavern.domain.search

import com.tavern.domain.models.ApiSettings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** 对应 Flet 版 `tavern/search.py` 的解析与拼装部分。 */
class SearchTest {

    private fun parse(text: String) = Json.parseToJsonElement(text)

    @Test
    fun endpointsPerProvider() {
        assertEquals(TAVILY_URL, Search.endpointFor(ApiSettings(searchProvider = "tavily")))
        assertEquals(BOCHA_URL, Search.endpointFor(ApiSettings(searchProvider = "bocha")))
        assertEquals(
            "https://my.api/search",
            Search.endpointFor(ApiSettings(searchProvider = "custom", searchUrl = " https://my.api/search ")),
        )
        assertEquals("Tavily", Search.providerLabel("tavily"))
        assertEquals("自定义 JSON 端点", Search.providerLabel("不认识的服务商"))
    }

    @Test
    fun buildRequestTavily() {
        val (url, headers, body) = Search.buildRequest(
            ApiSettings(searchProvider = "tavily", searchApiKey = " k123 "),
            "今天天气",
            5,
        )
        assertEquals(TAVILY_URL, url)
        assertEquals("Bearer k123", headers["Authorization"])
        assertEquals("application/json", headers["Content-Type"])
        assertEquals("今天天气", (body["query"] as JsonPrimitive).content)
        assertEquals(5, (body["max_results"] as JsonPrimitive).content.toInt())
        assertEquals("basic", (body["search_depth"] as JsonPrimitive).content)
        assertEquals("k123", (body["api_key"] as JsonPrimitive).content, "老版本参数也带上")
        // 没有 key 时不带 Authorization 与 api_key
        val (_, noKeyHeaders, noKeyBody) = Search.buildRequest(ApiSettings(searchProvider = "tavily"), "x")
        assertTrue(!noKeyHeaders.containsKey("Authorization"))
        assertTrue(noKeyBody["api_key"] == null)
    }

    @Test
    fun buildRequestClampsAndValidates() {
        val (_, _, many) = Search.buildRequest(ApiSettings(searchProvider = "custom", searchUrl = "u"), "q", 99)
        assertEquals(10, (many["limit"] as JsonPrimitive).content.toInt(), "最多 10 条")
        val (_, _, few) = Search.buildRequest(ApiSettings(searchProvider = "custom", searchUrl = "u"), "q", 0)
        assertEquals(1, (few["limit"] as JsonPrimitive).content.toInt(), "至少 1 条")
        val (_, _, bocha) = Search.buildRequest(ApiSettings(searchProvider = "bocha"), "q", 3)
        assertEquals(3, (bocha["count"] as JsonPrimitive).content.toInt())
        assertEquals(true, (bocha["summary"] as JsonPrimitive).content.toBoolean())
        assertFailsWith<SearchException> {
            Search.buildRequest(ApiSettings(searchProvider = "custom", searchUrl = "  "), "q")
        }
    }

    @Test
    fun parseTavilyShape() {
        val results = Search.parseResults(
            parse("""{"results":[{"title":"标题","url":"https://a","content":"摘要"},{"title":"第二条"}]}"""),
            5,
        )
        assertEquals(2, results.size)
        assertEquals("标题", results[0].title)
        assertEquals("https://a", results[0].url)
        assertEquals("摘要", results[0].snippet)
        assertEquals("第二条", results[1].title)
        assertEquals("", results[1].url)
    }

    @Test
    fun parseBochaNestedShape() {
        val results = Search.parseResults(
            parse("""{"data":{"webPages":{"value":[{"name":"名字","link":"https://b","summary":"概要"}]}}}"""),
            5,
        )
        assertEquals(1, results.size)
        assertEquals("名字", results[0].title)
        assertEquals("https://b", results[0].url)
        assertEquals("概要", results[0].snippet)
    }

    @Test
    fun parseHandlesTopLevelArrayAndGarbage() {
        val results = Search.parseResults(parse("""[{"title":"直接是数组"}]"""), 5)
        assertEquals("直接是数组", results[0].title)
        assertTrue(Search.parseResults(parse("""{"foo":"bar"}""")).isEmpty())
        assertTrue(Search.parseResults(JsonPrimitive("不是对象")).isEmpty())
        assertTrue(Search.parseResults(null).isEmpty())
        // 既没标题也没摘要的条目丢掉
        assertTrue(Search.parseResults(parse("""{"results":[{"url":"https://x"}]}""")).isEmpty())
    }

    @Test
    fun parseRespectsLimit() {
        val results = Search.parseResults(
            parse("""{"results":[{"title":"a"},{"title":"b"},{"title":"c"}]}"""),
            2,
        )
        assertEquals(2, results.size)
    }

    @Test
    fun formatContextBuildsBlock() {
        val text = Search.formatContext(
            listOf(
                SearchResult("标题一", "https://a", "摘要一"),
                SearchResult("", "", "只有摘要"),
            ),
        )
        val lines = text.split("\n")
        assertEquals("【联网检索结果】", lines[0])
        assertTrue(lines[1].contains("只在与当前对话相关时自然引用"))
        assertTrue(text.contains("1. 标题一（https://a）"), text)
        assertTrue(text.contains("   摘要一"), text)
        assertTrue(text.contains("2. （无标题）"), text)
    }

    @Test
    fun formatContextTruncates() {
        assertEquals("", Search.formatContext(emptyList()))
        val long = List(6) { SearchResult("标题$it", "", "字".repeat(200)) }
        val text = Search.formatContext(long, maxChars = 300)
        assertTrue(text.contains("（后续结果略）"), text)
        // 第一条一定留一点内容，不能只剩"后续结果略"
        assertTrue(text.contains("1. 标题0"), text)
        assertTrue(text.length < 700, "整体要被限制住，实际 ${text.length}")
    }
}
