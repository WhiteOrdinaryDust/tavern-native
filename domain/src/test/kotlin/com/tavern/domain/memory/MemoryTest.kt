package com.tavern.domain.memory

import com.tavern.domain.models.ApiSettings
import com.tavern.domain.models.Character
import com.tavern.domain.models.Message
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 对应 Flet 版 `tavern/memory.py` 与 tests/test_core.py 的前情提要部分。 */
class MemoryTest {

    private fun history(vararg contents: String): List<Message> =
        contents.mapIndexed { index, text ->
            Message(id = "m$index", role = if (index % 2 == 0) "user" else "assistant", content = text)
        }

    @Test
    fun unsummarizedKeepsEverythingWhenNothingSummarized() {
        val messages = history("一", "二", "三")
        assertEquals(messages, Memory.unsummarized(messages, ""))
        // 找不到这条（被删了）→ 当作全部都没摘要，避免错位
        assertEquals(messages, Memory.unsummarized(messages, "不存在的 id"))
        assertEquals(listOf(messages[2]), Memory.unsummarized(messages, "m1"))
        assertEquals(emptyList(), Memory.unsummarized(messages, "m2"))
    }

    @Test
    fun shouldRefreshOnlyWhenIntervalReached() {
        val messages = history("一", "二", "三")
        assertTrue(Memory.shouldRefresh(messages, "", interval = 3))
        assertFalse(Memory.shouldRefresh(messages, "", interval = 4))
        assertFalse(Memory.shouldRefresh(messages, "", interval = 0), "0 = 关闭自动摘要")
        assertTrue(Memory.shouldRefresh(messages, "m0", interval = 2))
    }

    @Test
    fun collectBatchStopsAtTokenBudget() {
        val messages = history("一二三四五", "六七八九十", "十一")
        // 每条 5 个 CJK = 5 token，加 4 开销 = 9
        val batch = Memory.collectBatch(messages, "", maxInput = 20)
        assertEquals(2, batch.size, "第三条会撑爆预算")
        // 空内容的消息要跳过
        val withEmpty = listOf(
            Message(id = "a", content = "有效"),
            Message(id = "b", content = "   "),
            Message(id = "c", content = "也有效"),
        )
        assertEquals(listOf("a", "c"), Memory.collectBatch(withEmpty, "").map { it.id })
        // 第一条就算超预算也要收进来（否则永远前进不了）
        assertEquals(1, Memory.collectBatch(history("很长很长很长很长"), "", maxInput = 1).size)
    }

    @Test
    fun nextUptoUsesLastBatchMessage() {
        val messages = history("一", "二", "三")
        assertEquals("m1", Memory.nextUpto(messages, "", listOf(messages[0], messages[1])))
        // 没批量时退到最后一条，保证下次能继续
        assertEquals("m2", Memory.nextUpto(messages, "", emptyList()))
        assertEquals("", Memory.nextUpto(emptyList(), "", emptyList()))
    }

    @Test
    fun summarySettingsDisablesThinking() {
        val configured = ApiSettings(thinking = "enabled", reasoningEffort = "high")
        val out = Memory.summarySettings(configured)
        assertEquals("disabled", out.thinking)
        assertEquals("", out.reasoningEffort)
        assertEquals(SUMMARY_MAX_TOKENS, out.maxTokens)

        // 没配过 thinking 的服务商：不要凭空带上这个参数（会被 400）
        val plain = Memory.summarySettings(ApiSettings(thinking = ""))
        assertEquals("", plain.thinking)
    }

    @Test
    fun buildSummaryMessagesShape() {
        val messages = history("在吗", "在的")
        val request = Memory.buildSummaryMessages(
            Character(name = "林月"),
            ApiSettings(userName = "阿伟"),
            messages,
        )
        assertEquals(2, request.size)
        assertEquals("system", request[0]["role"])
        assertTrue(request[0]["content"]!!.contains("剧情记录员"))
        assertTrue(request[0]["content"]!!.contains("600 字以内"))
        assertTrue(request[1]["content"]!!.contains("阿伟：在吗"))
        assertTrue(request[1]["content"]!!.contains("林月：在的"))
        assertTrue(request[1]["content"]!!.endsWith("请输出合并后的完整前情提要（包含已有提要里的关键信息）。"))
    }

    @Test
    fun buildSummaryMessagesIncludesPrevious() {
        val request = Memory.buildSummaryMessages(
            Character(name = "林月"),
            ApiSettings(),
            history("新剧情"),
            previousSummary = "旧提要",
        )
        val body = request[1]["content"]!!
        assertTrue(body.contains("【已有的前情提要】"))
        assertTrue(body.contains("旧提要"))
        assertTrue(body.contains("【需要并进提要的新剧情】"))
    }

    @Test
    fun cleanSummaryStripsPrefixesAndLimits() {
        assertEquals("林月认识了你", Memory.cleanSummary("总结：林月认识了你"))
        assertEquals("林月认识了你", Memory.cleanSummary("  前情提要： 林月认识了你  "))
        assertEquals("第一行\n第二行", Memory.cleanSummary("\n\n第一行\n第二行\n\n"))
        val long = "字".repeat(50)
        val limited = Memory.cleanSummary(long, limit = 10)
        assertEquals(11, limited.length, "截断后加省略号")
        assertTrue(limited.endsWith("…"))
        assertEquals("", Memory.cleanSummary(null))
    }

    @Test
    fun summaryIsUseful() {
        assertTrue(Memory.summaryIsUseful("有内容"))
        assertFalse(Memory.summaryIsUseful(""))
        assertFalse(Memory.summaryIsUseful("   "))
        assertFalse(Memory.summaryIsUseful(null))
    }
}
