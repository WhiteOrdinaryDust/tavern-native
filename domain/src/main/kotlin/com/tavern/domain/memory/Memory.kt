package com.tavern.domain.memory

import com.tavern.domain.models.ApiSettings
import com.tavern.domain.models.Character
import com.tavern.domain.models.Message
import com.tavern.domain.prompt.Prompt

/** 攒够多少条新消息才自动刷新前情提要（群聊一轮好几条，默认放宽到 40）。 */
const val DEFAULT_INTERVAL = 40
const val DEFAULT_MAX_INPUT = 6000
const val SUMMARY_MAX_TOKENS = 800

/** 总结用的系统提示（`{limit}` 会被替换成字数上限）。与 Flet 版逐字一致。 */
private const val SUMMARY_SYSTEM = (
    "你是一个剧情记录员。把给你的角色扮演对话浓缩成一段「前情提要」，" +
        "供之后继续剧情时参考。要求：\n" +
        "1. 保留关键事实：人物关系的变化、承诺与约定、获得或失去的物品、重要地点、还没解决的悬念\n" +
        "2. 用第三人称，人物称呼保持对话里的原名，不要改成「他/她」\n" +
        "3. 按时间顺序写，用简洁的短句或分条，总长度控制在 {limit} 字以内\n" +
        "4. 只写已经发生的事：不要编造、不要写建议、不要续写剧情、不要评论\n" +
        "5. 直接输出前情提要正文，不要加「总结：」这类前缀"
    )

/** 滚动摘要记忆：把前面的剧情压缩成「前情提要」，让长会话里的角色不忘事。 */
object Memory {

    /**
     * 返回还没进摘要的消息。
     *
     * `uptoId` 为空表示一条都没摘要过；找不到这条消息（比如被删了）时当作「全部都没摘要」，
     * 避免摘要与历史错位。
     */
    fun unsummarized(messages: List<Message>, uptoId: String): List<Message> {
        if (uptoId.isEmpty()) return messages
        val index = messages.indexOfFirst { it.id == uptoId }
        if (index < 0) return messages
        return messages.subList(index + 1, messages.size).toList()
    }

    /** 按 token 上限截取一批待摘要的消息（从最早未摘要的开始）。 */
    fun collectBatch(
        messages: List<Message>,
        uptoId: String,
        maxInput: Int = DEFAULT_MAX_INPUT,
    ): List<Message> {
        val batch = mutableListOf<Message>()
        var used = 0
        for (msg in unsummarized(messages, uptoId)) {
            if (msg.content.trim().isEmpty()) continue
            val cost = Prompt.estimateTokens(msg.content) + 4
            if (batch.isNotEmpty() && used + cost > maxInput) break
            batch += msg
            used += cost
        }
        return batch
    }

    /** 攒够 `interval` 条新消息就该刷新摘要了。 */
    fun shouldRefresh(
        messages: List<Message>,
        uptoId: String,
        interval: Int = DEFAULT_INTERVAL,
    ): Boolean = interval > 0 && unsummarized(messages, uptoId).size >= interval

    /**
     * 总结用的请求参数：不思考、输出短一点。
     *
     * 只有用户已经配过 `thinking` 时才显式关掉思考（说明该服务商认识这个参数），
     * 否则不带该参数，免得别的服务商直接 400。
     */
    fun summarySettings(settings: ApiSettings): ApiSettings = settings.copy(
        thinking = if (settings.thinking.isNotEmpty()) "disabled" else "",
        reasoningEffort = "",
        maxTokens = SUMMARY_MAX_TOKENS,
    )

    /** 构造「请总结」的请求体。 */
    fun buildSummaryMessages(
        character: Character,
        settings: ApiSettings,
        messages: List<Message>,
        previousSummary: String = "",
    ): List<Map<String, String>> {
        val userName = settings.userName.ifEmpty { "User" }
        val system = SUMMARY_SYSTEM.replace("{limit}", "600")

        val lines = mutableListOf<String>()
        if (previousSummary.trim().isNotEmpty()) {
            lines += "【已有的前情提要】"
            lines += previousSummary.trim()
            lines += ""
            lines += "【需要并进提要的新剧情】"
        }
        for (msg in messages) {
            val speaker = if (msg.role == "user") userName else character.name
            lines += "$speaker：${msg.content.trim()}"
        }
        lines += ""
        lines += "请输出合并后的完整前情提要（包含已有提要里的关键信息）。"
        return listOf(
            mapOf("role" to "system", "content" to system),
            mapOf("role" to "user", "content" to lines.joinToString("\n")),
        )
    }

    /** 清掉模型爱加的前缀与多余空白，并限长。 */
    fun cleanSummary(text: String?, limit: Int = 1200): String {
        var out = (text ?: "").trim()
        for (prefix in listOf("前情提要：", "前情提要:", "总结：", "总结:", "剧情摘要：", "剧情摘要:")) {
            if (out.startsWith(prefix)) out = out.substring(prefix.length).trim()
        }
        val lines = out.split("\n").map { it.trimEnd() }.toMutableList()
        while (lines.isNotEmpty() && lines.first().isBlank()) lines.removeAt(0)
        while (lines.isNotEmpty() && lines.last().isBlank()) lines.removeAt(lines.size - 1)
        out = lines.joinToString("\n")
        if (limit > 0 && out.length > limit) out = out.take(limit).trimEnd() + "…"
        return out
    }

    /** 本次摘要应该推进到哪条消息（用最近一条**有内容**的，保证下次从它之后继续）。 */
    fun nextUpto(messages: List<Message>, uptoId: String, batch: List<Message>): String {
        if (batch.isNotEmpty()) return batch.last().id
        return messages.lastOrNull()?.id ?: uptoId
    }

    fun summaryIsUseful(summary: String?): Boolean = !summary.isNullOrBlank()
}
