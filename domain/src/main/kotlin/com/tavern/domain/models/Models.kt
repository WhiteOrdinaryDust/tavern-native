package com.tavern.domain.models

import com.tavern.domain.util.nowSeconds
import kotlinx.serialization.Serializable
import java.util.UUID

/** 12 位十六进制 id（与 Flet 版 `uuid4().hex[:12]` 等价）。 */
fun newId(): String = UUID.randomUUID().toString().replace("-", "").take(12)

/** 一个角色。字段名对齐 SillyTavern 角色卡 V2 规范。 */
data class Character(
    val id: String = newId(),
    val name: String = "未命名角色",
    val folderId: String = "",
    val description: String = "",
    val personality: String = "",
    val scenario: String = "",
    val firstMes: String = "",
    val mesExample: String = "",
    val systemPrompt: String = "",
    val postHistoryInstructions: String = "",
    val creator: String = "",
    val creatorNotes: String = "",
    val tags: List<String> = emptyList(),
    val alternateGreetings: List<String> = emptyList(),
    val avatarPath: String = "",
    val raw: Map<String, Any?> = emptyMap(),
    val createdAt: Double = nowSeconds(),
    val updatedAt: Double = nowSeconds(),
) {
    val greeting: String get() = firstMes.trim()

    fun toDict(): Map<String, Any?> = mapOf(
        "id" to id,
        "name" to name,
        "description" to description,
        "personality" to personality,
        "scenario" to scenario,
        "first_mes" to firstMes,
        "mes_example" to mesExample,
        "system_prompt" to systemPrompt,
        "post_history_instructions" to postHistoryInstructions,
        "creator" to creator,
        "creator_notes" to creatorNotes,
        "tags" to tags,
        "alternate_greetings" to alternateGreetings,
        "avatar_path" to avatarPath,
        "raw" to raw,
        "created_at" to createdAt,
        "updated_at" to updatedAt,
    )
}

/** 一个会话。群聊时 [groupId] 非空、[characterId] 为空。 */
data class Chat(
    val id: String = newId(),
    val characterId: String = "",
    val groupId: String = "",
    val title: String = "",
    val summary: String = "",
    val summaryUpto: String = "",
    val rhythmTime: String = "",
    val rhythmAuthority: String = "",
    val webSearch: Boolean = false,
    val bookIds: List<String> = emptyList(),
    val createdAt: Double = nowSeconds(),
    val updatedAt: Double = nowSeconds(),
)

/** 一条消息。可变字段与 Flet 版语义一致（候选、思考、用量就地更新）。 */
data class Message(
    val id: String = newId(),
    val chatId: String = "",
    val role: String = "user",
    val speaker: String = "",
    var content: String = "",
    var reasoning: String = "",
    var variants: MutableList<String> = mutableListOf(),
    var variantIndex: Int = 0,
    var usage: Map<String, Int> = emptyMap(),
    val createdAt: Double = nowSeconds(),
) {
    /** 当前选中的文本。 */
    val text: String
        get() {
            if (variants.isEmpty()) return content
            val index = variantIndex.coerceIn(0, variants.size - 1)
            return variants[index]
        }

    val variantCount: Int get() = if (variants.isEmpty()) 1 else variants.size

    /** 保证 variants 至少有一条，并与 content 对齐。 */
    fun ensureVariants() {
        if (variants.isEmpty()) {
            variants = mutableListOf(content)
            variantIndex = 0
        } else {
            variantIndex = variantIndex.coerceIn(0, variants.size - 1)
            content = variants[variantIndex]
        }
    }

    /** 追加一个候选并选中它（重新生成用）。 */
    fun addVariant(text: String) {
        ensureVariants()
        variants.add(text)
        variantIndex = variants.size - 1
        content = text
    }

    /** 在候选之间循环切换；返回是否有变化。 */
    fun swipe(delta: Int): Boolean {
        if (variantCount <= 1) return false
        variantIndex = ((variantIndex + delta) % variantCount + variantCount) % variantCount
        content = text
        return true
    }

    /** 修改当前选中的那条候选。 */
    fun replaceCurrent(text: String) {
        ensureVariants()
        variants[variantIndex] = text
        content = text
    }

    fun toOpenAi(): Map<String, String> = mapOf("role" to role, "content" to text)
}

/** 世界书条目，字段对齐 SillyTavern 的 World Info 规范。 */
data class WorldEntry(
    val id: String = newId(),
    val characterId: String = "",
    val bookId: String = "",
    val comment: String = "",
    val keys: List<String> = emptyList(),
    val keySecondary: List<String> = emptyList(),
    val content: String = "",
    val constant: Boolean = false,
    val selective: Boolean = true,
    val selectiveLogic: Int = 0,
    val order: Int = 100,
    val position: Int = 0,
    val depth: Int = 4,
    val group: String = "",
    val groupWeight: Int = 100,
    val role: Int = 0,
    val enabled: Boolean = true,
    val probability: Int = 100,
    val useProbability: Boolean = true,
    val caseSensitive: Boolean? = null,
    val matchWholeWords: Boolean? = null,
    val scanDepth: Int? = null,
    val excludeRecursion: Boolean = false,
    val preventRecursion: Boolean = false,
    val raw: Map<String, Any?> = emptyMap(),
    val createdAt: Double = nowSeconds(),
    val updatedAt: Double = nowSeconds(),
) {
    val title: String
        get() = comment.trim().ifEmpty { keys.firstOrNull() ?: "未命名条目" }

    val triggerSummary: String
        get() {
            if (constant) return "常驻"
            if (keys.isEmpty()) return "无关键词（不会触发）"
            var text = keys.take(4).joinToString("、")
            if (keySecondary.isNotEmpty()) text += " + 过滤 ${keySecondary.size} 个"
            return text
        }
}

/** 分类文件夹（角色 / 群聊各自一棵树，支持子文件夹）。 */
data class Folder(
    val id: String = newId(),
    val name: String = "新建文件夹",
    val kind: String = "character",
    val parentId: String = "",
    val sortOrder: Int = 100,
    val createdAt: Double = nowSeconds(),
    val updatedAt: Double = nowSeconds(),
) {
    val title: String get() = name.trim().ifEmpty { "新建文件夹" }
    val isRoot: Boolean get() = parentId.isEmpty()
}

/** 一本世界书。 */
data class WorldBook(
    val id: String = newId(),
    val name: String = "未命名世界书",
    val characterId: String = "",
    val enabled: Boolean = true,
    val sortOrder: Int = 100,
    val raw: Map<String, Any?> = emptyMap(),
    val createdAt: Double = nowSeconds(),
    val updatedAt: Double = nowSeconds(),
) {
    val title: String get() = name.trim().ifEmpty { "未命名世界书" }
    val scopeLabel: String get() = if (characterId.isEmpty()) "全局" else "本角色"
}

/** 群聊：你发言后，所有没被禁言的成员依次各回一句。 */
data class Group(
    val id: String = newId(),
    val name: String = "新群聊",
    val folderId: String = "",
    val members: List<String> = emptyList(),
    val muted: List<String> = emptyList(),
    val createdAt: Double = nowSeconds(),
    val updatedAt: Double = nowSeconds(),
) {
    val title: String get() = name.trim().ifEmpty { "新群聊" }

    /** 会发言的成员（按顺序）＝ 没被禁言的那些。 */
    fun activeMembers(): List<String> = members.filterNot { it in muted }

    fun isMember(characterId: String): Boolean = characterId in members

    fun isMuted(characterId: String): Boolean = characterId in muted
}

/** OpenAI 兼容后端设置。 */
@Serializable
data class ApiSettings(
    val baseUrl: String = "https://api.deepseek.com/v1",
    val apiKey: String = "",
    val model: String = "deepseek-flash",
    val temperature: Double = 1.0,
    val topP: Double = 0.0,
    val maxTokens: Int = 4096,
    val contextTokens: Int = 6000,
    val userName: String = "User",
    val userPersona: String = "",
    val userAvatarPath: String = "",
    val systemExtra: String = "",
    val thinking: String = "",
    val reasoningEffort: String = "",
    val showReasoning: Boolean = true,
    val extraParams: String = "",
    val worldScanDepth: Int = 4,
    val worldMinActivations: Int = 0,
    val worldTokenBudget: Int = 1200,
    val worldIncludeNames: Boolean = true,
    val formatActions: Boolean = true,
    val renderMarkdown: Boolean = true,
    val cleanupReply: Boolean = true,
    val cleanupRules: String = "",
    val rhythmOverrides: String = "{}",   // 七个按钮的提示词覆盖（JSON）
    val outputMinChars: Int = 0,          // 输出字数下限，0 = 不限
    val outputMaxChars: Int = 0,          // 输出字数上限，0 = 不限
    val summaryEnabled: Boolean = true,
    val summaryInterval: Int = 40,
    val summaryMaxInput: Int = 6000,
    val searchProvider: String = "tavily",
    val searchApiKey: String = "",
    val searchUrl: String = "",
    val searchResults: Int = 5,
    val searchMaxChars: Int = 1500,
) {
    val configured: Boolean get() = baseUrl.trim().isNotEmpty() && model.trim().isNotEmpty()
}
