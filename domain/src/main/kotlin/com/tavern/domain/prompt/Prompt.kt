package com.tavern.domain.prompt

import com.tavern.domain.models.ApiSettings
import com.tavern.domain.models.Character
import com.tavern.domain.models.Message

private val USER_PREFIX = Regex("""^\s*\{\{\s*user\s*\}\}\s*[:：]\s*""", RegexOption.IGNORE_CASE)
private val CHAR_PREFIX = Regex("""^\s*\{\{\s*char\s*\}\}\s*[:：]\s*""", RegexOption.IGNORE_CASE)

private const val DEFAULT_SYSTEM = (
    "你正在进行沉浸式角色扮演。严格遵循角色的设定、语气与说话习惯，" +
        "始终以 {char} 的身份回应，不要跳出角色，不要提到自己是 AI、模型或助手。" +
        "动作、神态、环境描写用星号包裹，例如 *她微微一笑*。"
    )

/**
 * Prompt 组装：角色卡 + 历史 → OpenAI messages。
 *
 * 这是决定"回复像不像角色"的核心，与界面完全解耦。与 Flet 版 `tavern/prompt.py` 一致。
 */
object Prompt {

    /** 粗估 token 数：CJK 按 1 字 1 token，其余按 4 字符 1 token（偏保守）。 */
    fun estimateTokens(text: String?): Int {
        if (text.isNullOrEmpty()) return 0
        var cjk = 0
        for (ch in text) {
            val code = ch.code
            if (code in 0x2E80..0x9FFF ||
                code in 0xAC00..0xD7AF ||
                code in 0xF900..0xFAFF ||
                code in 0xFF00..0xFFEF
            ) {
                cjk += 1
            }
        }
        return cjk + (text.length - cjk + 3) / 4
    }

    /** 替换酒馆通用宏。 */
    fun substitute(text: String?, charName: String, userName: String): String {
        if (text.isNullOrEmpty()) return ""
        return text
            .replace("{{char}}", charName)
            .replace("{{user}}", userName)
            .replace("<BOT>", charName)
            .replace("<USER>", userName)
    }

    /** 把 `mes_example`（few-shot 对话示例）解析成消息列表。 */
    fun parseMesExample(mesExample: String?, charName: String, userName: String): List<Map<String, String>> {
        val messages = mutableListOf<Map<String, String>>()
        var role: String? = null
        var buf = mutableListOf<String>()

        fun flush() {
            val current = role
            if (current != null && buf.isNotEmpty()) {
                val content = substitute(buf.joinToString("\n").trim(), charName, userName)
                if (content.isNotEmpty()) messages += mapOf("role" to current, "content" to content)
            }
            role = null
            buf = mutableListOf()
        }

        for (raw in (mesExample ?: "").split("\n")) {
            val line = raw.trimEnd()
            val stripped = line.trim()
            if (stripped.isEmpty() || stripped.uppercase() == "<START>" ||
                stripped.all { it == '*' || it == '-' || it == '=' }
            ) {
                flush()
                continue
            }
            if (USER_PREFIX.containsMatchIn(line) && USER_PREFIX.find(line)?.range?.first == 0) {
                flush()
                role = "user"
                buf = mutableListOf(USER_PREFIX.replaceFirst(line, ""))
            } else if (CHAR_PREFIX.find(line)?.range?.first == 0) {
                flush()
                role = "assistant"
                buf = mutableListOf(CHAR_PREFIX.replaceFirst(line, ""))
            } else if (role != null) {
                buf.add(line)
            }
        }
        flush()
        return messages
    }

    fun buildSystemPrompt(
        character: Character,
        settings: ApiSettings,
        userName: String,
        summary: String = "",
        rhythm: String = "",
        webContext: String = "",
    ): String {
        val charName = character.name
        val parts = mutableListOf<String>()

        val custom = substitute(character.systemPrompt, charName, userName).trim()
        parts += custom.ifEmpty { DEFAULT_SYSTEM.replace("{char}", charName) }

        val details = mutableListOf<String>()
        if (character.description.trim().isNotEmpty()) {
            details += substitute(character.description, charName, userName).trim()
        }
        if (character.personality.trim().isNotEmpty()) {
            details += "性格特点：" + substitute(character.personality, charName, userName).trim()
        }
        if (character.scenario.trim().isNotEmpty()) {
            details += "当前场景：" + substitute(character.scenario, charName, userName).trim()
        }
        if (details.isNotEmpty()) parts += "角色设定：\n" + details.joinToString("\n")

        val persona = substitute(settings.userPersona, charName, userName).trim()
        if (persona.isNotEmpty()) parts += "$userName 的人设（由玩家扮演）：\n$persona"

        // 滚动摘要：把很久以前的剧情压缩进来，避免长会话里角色忘事
        val digest = substitute(summary, charName, userName).trim()
        if (digest.isNotEmpty()) {
            parts += "目前为止的故事（前情提要，帮你回忆之前发生过什么，不要直接复述）：\n$digest"
        }

        parts += "你扮演 $charName，正在与 $userName 对话。只输出 $charName 的回复内容。"
        if (settings.systemExtra.trim().isNotEmpty()) parts += settings.systemExtra.trim()
        // 联网资料放在节奏协议之前：资料是"事实"，节奏是"怎么写"
        if (webContext.trim().isNotEmpty()) parts += webContext.trim()
        // 节奏协议放在最后：离用户输入最近，最容易生效
        if (rhythm.trim().isNotEmpty()) parts += rhythm.trim()
        return parts.filter { it.trim().isNotEmpty() }.joinToString("\n\n")
    }

    /**
     * 组装最终发给模型的 messages。
     *
     * 超出 `contextTokens` 预算时，从最旧的历史开始丢弃。
     */
    fun buildMessages(
        character: Character,
        settings: ApiSettings,
        history: List<Message>,
        userInput: String? = null,
        summary: String = "",
        rhythm: String = "",
        webContext: String = "",
    ): List<Map<String, String>> {
        val charName = character.name
        val userName = settings.userName.ifEmpty { "User" }

        val system = buildSystemPrompt(character, settings, userName, summary, rhythm, webContext)
        val messages = mutableListOf<Map<String, String>>(mapOf("role" to "system", "content" to system))
        messages += parseMesExample(character.mesExample, charName, userName)

        var budget = settings.contextTokens - estimateTokens(system) - estimateTokens(userInput ?: "")
        budget = maxOf(budget, 256)
        val kept = mutableListOf<Message>()
        var used = 0
        for (msg in history.reversed()) {
            val cost = estimateTokens(msg.content) + 4
            if (kept.isNotEmpty() && used + cost > budget) break
            kept += msg
            used += cost
        }
        kept.reverse()
        messages += kept.map { it.toOpenAi() }

        if (character.postHistoryInstructions.trim().isNotEmpty()) {
            messages += mapOf(
                "role" to "system",
                "content" to substitute(character.postHistoryInstructions, charName, userName),
            )
        }
        if (!userInput.isNullOrEmpty()) messages += mapOf("role" to "user", "content" to userInput)
        return messages
    }

    // ------------------------------------------------------------------ 群聊

    /** 群聊里「当前发言者」的 system prompt：它在群里的身份 + 别替别人说话。 */
    fun buildGroupSystemPrompt(
        speaker: Character,
        others: List<Character>,
        settings: ApiSettings,
        userName: String,
        summary: String = "",
        rhythm: String = "",
        webContext: String = "",
    ): String {
        val base = buildSystemPrompt(speaker, settings, userName, summary, rhythm, webContext)
        val names = others.filter { it.name.trim().isNotEmpty() }.joinToString("、") { it.name }
        val lines = listOf(
            if (names.isNotEmpty()) {
                "这是一场多人对话（群聊）。在场的人：$names、$userName（玩家）。"
            } else {
                "这是一场多人对话（群聊）。在场的人：$userName（玩家）。"
            },
            "你只扮演 ${speaker.name}：只写 ${speaker.name} 自己的台词与动作，" +
                "不要替 $userName 或其他人说话，也不要写旁白解说。",
            "回复短一些，像在群里接话那样自然。",
        )
        return base + "\n\n" + lines.joinToString("\n")
    }

    /**
     * 群聊的请求体。
     *
     * 关键点：**只有当前发言者自己的历史是 assistant**，其他人的发言都当作"别人说的话"
     * 用 user 角色喂进去，并在正文前加上说话人名字。
     */
    fun buildGroupMessages(
        speaker: Character,
        members: List<Character>,
        settings: ApiSettings,
        history: List<Message>,
        userInput: String? = null,
        summary: String = "",
        rhythm: String = "",
        webContext: String = "",
    ): List<Map<String, String>> {
        val charName = speaker.name
        val userName = settings.userName.ifEmpty { "User" }
        val others = members.filter { it.id != speaker.id }
        val system = buildGroupSystemPrompt(speaker, others, settings, userName, summary, rhythm, webContext)

        val messages = mutableListOf<Map<String, String>>(mapOf("role" to "system", "content" to system))
        messages += parseMesExample(speaker.mesExample, charName, userName)

        val byId = members.associateBy { it.id }
        var budget = settings.contextTokens - estimateTokens(system) - estimateTokens(userInput ?: "")
        budget = maxOf(budget, 256)
        val kept = mutableListOf<Map<String, String>>()
        var used = 0
        for (msg in history.reversed()) {
            var text = msg.text
            val entry: Map<String, String>
            if (msg.role == "assistant" && msg.speaker == speaker.id) {
                entry = mapOf("role" to "assistant", "content" to text)
            } else {
                if (msg.role == "assistant") {
                    val who = byId[msg.speaker]
                    text = "${who?.name ?: "某人"}：$text"
                }
                entry = mapOf("role" to "user", "content" to text)
            }
            val cost = estimateTokens(text) + 4
            if (kept.isNotEmpty() && used + cost > budget) break
            kept += entry
            used += cost
        }
        kept.reverse()
        messages += kept

        if (speaker.postHistoryInstructions.trim().isNotEmpty()) {
            messages += mapOf(
                "role" to "system",
                "content" to substitute(speaker.postHistoryInstructions, charName, userName),
            )
        }
        if (!userInput.isNullOrEmpty()) messages += mapOf("role" to "user", "content" to userInput)
        return messages
    }
}
