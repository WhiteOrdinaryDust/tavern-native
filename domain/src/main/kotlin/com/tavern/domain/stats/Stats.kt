package com.tavern.domain.stats

import com.tavern.domain.models.Message
import com.tavern.domain.models.WorldEntry
import com.tavern.domain.prompt.Prompt
import com.tavern.domain.util.pyRound

const val CONTEXT_WARN = 70
const val CONTEXT_DANGER = 90

/** 下一轮请求的上下文占用（口径与 Flet 版 `stats.context_usage` 一致）。 */
data class ContextUsage(
    val used: Int,
    val budget: Int,
    val percent: Int,
    val remaining: Int,
    val messages: Int,
)

data class ConversationStats(
    val messages: Int,
    val userMessages: Int,
    val assistantMessages: Int,
    val userChars: Int,
    val assistantChars: Int,
    val chars: Int,
    val reasoningChars: Int,
    val tokensEstimate: Int,
    val promptTokens: Int,
    val completionTokens: Int,
    val cachedTokens: Int,
    val reasoningTokens: Int,
    val measuredReplies: Int,
)

data class WorldBookStats(
    val selected: Int,
    val candidates: Int,
    val percent: Int,
    val tokens: Int,
)

object Stats {

    fun contextUsage(
        messages: List<Message>,
        systemText: String = "",
        budget: Int = 6000,
        extraText: String = "",
    ): ContextUsage {
        val safeBudget = maxOf(1, budget)
        var used = Prompt.estimateTokens(systemText) + Prompt.estimateTokens(extraText)
        for (msg in messages) used += Prompt.estimateTokens(msg.text) + 4
        return ContextUsage(
            used = used,
            budget = safeBudget,
            percent = pyRound(used * 100.0 / safeBudget),
            remaining = maxOf(0, safeBudget - used),
            messages = messages.size,
        )
    }

    /** 给界面用的颜色档：ok / warn / danger。 */
    fun usageLevel(percent: Int): String = when {
        percent >= CONTEXT_DANGER -> "danger"
        percent >= CONTEXT_WARN -> "warn"
        else -> "ok"
    }

    fun conversationStats(messages: List<Message>): ConversationStats {
        val userChars = messages.filter { it.role == "user" }.sumOf { it.text.length }
        val charChars = messages.filter { it.role == "assistant" }.sumOf { it.text.length }
        val reasoningChars = messages.sumOf { it.reasoning.length }
        var promptTokens = 0
        var completionTokens = 0
        var cachedTokens = 0
        var reasoningTokens = 0
        var measured = 0
        for (msg in messages) {
            val usage = msg.usage
            if (usage.isEmpty()) continue
            measured += 1
            promptTokens += usage["prompt_tokens"] ?: 0
            completionTokens += usage["completion_tokens"] ?: 0
            reasoningTokens += usage["reasoning_tokens"] ?: 0
            cachedTokens += usage["prompt_cache_hit_tokens"] ?: 0
        }
        return ConversationStats(
            messages = messages.size,
            userMessages = messages.count { it.role == "user" },
            assistantMessages = messages.count { it.role == "assistant" },
            userChars = userChars,
            assistantChars = charChars,
            chars = userChars + charChars,
            reasoningChars = reasoningChars,
            tokensEstimate = Prompt.estimateTokens(messages.joinToString("") { it.text }),
            promptTokens = promptTokens,
            completionTokens = completionTokens,
            cachedTokens = cachedTokens,
            reasoningTokens = reasoningTokens,
            measuredReplies = measured,
        )
    }

    /** 上下文缓存命中率（%）。DeepSeek 会返回 prompt_cache_hit_tokens。 */
    fun cacheHitRate(promptTokens: Int, cachedTokens: Int): Int {
        if (promptTokens <= 0) return 0
        return pyRound(minOf(cachedTokens, promptTokens) * 100.0 / promptTokens)
    }

    /** 世界书命中情况：本轮注入了几条 / 一共有几条。 */
    fun worldBookStats(entries: List<WorldEntry>, selected: List<WorldEntry>): WorldBookStats {
        val candidates = entries.count { it.enabled && it.content.trim().isNotEmpty() }
        val hit = selected.size
        return WorldBookStats(
            selected = hit,
            candidates = candidates,
            percent = if (candidates > 0) pyRound(hit * 100.0 / candidates) else 0,
            tokens = selected.sumOf { Prompt.estimateTokens(it.content) },
        )
    }

    /**
     * 把服务端返回的 usage 归一成我们统计用的几个字段。
     *
     * DeepSeek 用 `prompt_cache_hit_tokens`，OpenAI 用 `prompt_tokens_details.cached_tokens`，
     * 思考 token 在 `completion_tokens_details.reasoning_tokens`；全为 0 时返回空。
     */
    fun normalizeUsage(raw: Map<String, Any?>?): Map<String, Int> {
        if (raw == null) return emptyMap()

        fun asInt(value: Any?): Int = when (value) {
            null -> 0
            is Number -> value.toInt()
            is String -> value.toIntOrNull() ?: 0
            else -> 0
        }

        var cached: Any? = raw["prompt_cache_hit_tokens"]
        if (cached == null) {
            val details = raw["prompt_tokens_details"]
            if (details is Map<*, *>) cached = details["cached_tokens"]
        }
        var reasoning = 0
        val completionDetails = raw["completion_tokens_details"]
        if (completionDetails is Map<*, *>) {
            reasoning = asInt(completionDetails["reasoning_tokens"])
        }
        if (reasoning == 0) reasoning = asInt(raw["reasoning_tokens"])

        val out = linkedMapOf(
            "prompt_tokens" to asInt(raw["prompt_tokens"]),
            "completion_tokens" to asInt(raw["completion_tokens"]),
            "total_tokens" to asInt(raw["total_tokens"]),
            "prompt_cache_hit_tokens" to asInt(cached),
            "reasoning_tokens" to reasoning,
        )
        return if (out.values.any { it != 0 }) out else emptyMap()
    }

    /** 把一条 usage 变成一行可读文字。 */
    fun summarizeUsage(usage: Map<String, Int>): String {
        if (usage.isEmpty()) return "（服务端未返回用量）"
        val parts = mutableListOf<String>()
        usage["prompt_tokens"]?.takeIf { it != 0 }?.let { parts += "输入 $it" }
        usage["completion_tokens"]?.takeIf { it != 0 }?.let { parts += "输出 $it" }
        usage["reasoning_tokens"]?.takeIf { it != 0 }?.let { parts += "其中思考 $it" }
        if (usage.containsKey("prompt_cache_hit_tokens") && (usage["prompt_tokens"] ?: 0) != 0) {
            val rate = cacheHitRate(usage["prompt_tokens"] ?: 0, usage["prompt_cache_hit_tokens"] ?: 0)
            parts += "缓存命中 ${usage["prompt_cache_hit_tokens"] ?: 0}（$rate%）"
        }
        return if (parts.isEmpty()) "（服务端未返回用量）" else parts.joinToString(" · ")
    }
}
