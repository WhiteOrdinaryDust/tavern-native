package com.tavern.domain.stats

import com.tavern.domain.models.Message
import com.tavern.domain.models.WorldEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 与 Flet 版 tests/test_core.py 的统计部分逐条对应。 */
class StatsTest {

    @Test
    fun contextUsageMatchesFormula() {
        val usage = Stats.contextUsage(listOf(Message(content = "你好")), budget = 100)
        // 2（你好） + 4（每条开销） = 6
        assertEquals(6, usage.used)
        assertEquals(100, usage.budget)
        assertEquals(6, usage.percent)
        assertEquals(94, usage.remaining)
        assertEquals(1, usage.messages)
    }

    @Test
    fun contextUsageIncludesSystemAndExtra() {
        val usage = Stats.contextUsage(
            listOf(Message(content = "abcd")),
            systemText = "你好",
            budget = 10,
            extraText = "abcd",
        )
        // 2 + 1 + (1 + 4) = 8
        assertEquals(8, usage.used)
        assertEquals(80, usage.percent)
        assertEquals(2, usage.remaining)
    }

    @Test
    fun contextUsageNeverDividesByZeroAndClampsRemaining() {
        val usage = Stats.contextUsage(listOf(Message(content = "很长的一段话")), budget = 0)
        assertEquals(1, usage.budget)
        assertEquals(0, usage.remaining)
        assertTrue(usage.percent > 100)
    }

    @Test
    fun usageLevelBands() {
        assertEquals("ok", Stats.usageLevel(0))
        assertEquals("ok", Stats.usageLevel(69))
        assertEquals("warn", Stats.usageLevel(70))
        assertEquals("warn", Stats.usageLevel(89))
        assertEquals("danger", Stats.usageLevel(90))
        assertEquals("danger", Stats.usageLevel(150))
    }

    @Test
    fun pythonStyleRoundingIsHalfToEven() {
        // "x" 估 1 token，加每条开销 4 → used = 5；5/8 = 62.5% → Python 的 round() 取偶得 62
        val usage = Stats.contextUsage(listOf(Message(content = "x")), budget = 8)
        assertEquals(5, usage.used)
        assertEquals(62, usage.percent, "四舍六入五成双：62.5 → 62（不是 63）")

        // 再加上 system "a"（1 token）→ used = 6；6/8 = 75%
        val usage2 = Stats.contextUsage(listOf(Message(content = "x")), budget = 8, systemText = "a")
        assertEquals(6, usage2.used)
        assertEquals(75, usage2.percent)
    }

    @Test
    fun conversationStatsCountsCharsAndUsage() {
        val messages = listOf(
            Message(role = "user", content = "你好"),
            Message(
                role = "assistant",
                content = "你好呀",
                reasoning = "想一下",
                usage = mapOf("prompt_tokens" to 10, "completion_tokens" to 4, "reasoning_tokens" to 2, "prompt_cache_hit_tokens" to 5),
            ),
            Message(role = "assistant", content = "嗯"),
        )
        val s = Stats.conversationStats(messages)
        assertEquals(3, s.messages)
        assertEquals(1, s.userMessages)
        assertEquals(2, s.assistantMessages)
        assertEquals(2, s.userChars)
        assertEquals(4, s.assistantChars)
        assertEquals(6, s.chars)
        assertEquals(3, s.reasoningChars)
        assertEquals(1, s.measuredReplies)
        assertEquals(10, s.promptTokens)
        assertEquals(4, s.completionTokens)
        assertEquals(5, s.cachedTokens)
        assertEquals(2, s.reasoningTokens)
    }

    @Test
    fun cacheHitRate() {
        assertEquals(0, Stats.cacheHitRate(0, 0))
        assertEquals(0, Stats.cacheHitRate(0, 100))
        assertEquals(25, Stats.cacheHitRate(1000, 250))
        assertEquals(100, Stats.cacheHitRate(100, 500), "命中数不能超过输入数")
    }

    @Test
    fun worldBookStatsCountsCandidates() {
        val entries = listOf(
            WorldEntry(content = "甲"),
            WorldEntry(content = "乙"),
            WorldEntry(content = "丙"),
            WorldEntry(content = "丁", enabled = false),
            WorldEntry(content = "   "),
        )
        val stats = Stats.worldBookStats(entries, listOf(entries[0]))
        assertEquals(1, stats.selected)
        assertEquals(3, stats.candidates)
        assertEquals(33, stats.percent)
        assertTrue(stats.tokens > 0)
        assertEquals(0, Stats.worldBookStats(emptyList(), emptyList()).percent)
    }

    @Test
    fun normalizeUsageHandlesDeepSeekShape() {
        val usage = Stats.normalizeUsage(
            mapOf("prompt_tokens" to 100, "completion_tokens" to 20, "total_tokens" to 120, "prompt_cache_hit_tokens" to 64)
        )
        assertEquals(100, usage["prompt_tokens"])
        assertEquals(20, usage["completion_tokens"])
        assertEquals(120, usage["total_tokens"])
        assertEquals(64, usage["prompt_cache_hit_tokens"])
        assertEquals(0, usage["reasoning_tokens"])
    }

    @Test
    fun normalizeUsageHandlesOpenAiShape() {
        val usage = Stats.normalizeUsage(
            mapOf(
                "prompt_tokens" to 100,
                "completion_tokens" to 20,
                "prompt_tokens_details" to mapOf("cached_tokens" to 30),
                "completion_tokens_details" to mapOf("reasoning_tokens" to 7),
            )
        )
        assertEquals(30, usage["prompt_cache_hit_tokens"])
        assertEquals(7, usage["reasoning_tokens"])
    }

    @Test
    fun normalizeUsageAcceptsTopLevelReasoningTokens() {
        val usage = Stats.normalizeUsage(mapOf("prompt_tokens" to 1, "reasoning_tokens" to 9))
        assertEquals(9, usage["reasoning_tokens"])
    }

    @Test
    fun normalizeUsageReturnsEmptyWhenAllZero() {
        assertTrue(Stats.normalizeUsage(mapOf("prompt_tokens" to 0, "completion_tokens" to 0)).isEmpty())
        assertTrue(Stats.normalizeUsage(null).isEmpty())
        assertTrue(Stats.normalizeUsage(mapOf("prompt_tokens" to "乱七八糟")).isEmpty())
    }

    @Test
    fun summarizeUsageReadsAsOneLine() {
        assertEquals("（服务端未返回用量）", Stats.summarizeUsage(emptyMap()))
        val line = Stats.summarizeUsage(
            mapOf("prompt_tokens" to 100, "completion_tokens" to 20, "reasoning_tokens" to 5, "prompt_cache_hit_tokens" to 50)
        )
        assertEquals("输入 100 · 输出 20 · 其中思考 5 · 缓存命中 50（50%）", line)
    }
}
