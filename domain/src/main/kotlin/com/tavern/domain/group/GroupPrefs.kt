package com.tavern.domain.group

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 群成员的「用户 / NPC」分类 + 「AI 可以扮演谁」的规则。
 *
 * 新模型里没有全局的"用户"：每个会话里你随时扮演某个成员。
 *
 * 规则：
 *  - 「用户」分类：默认**不**由 AI 扮演（想让 AI 演他 → 把他移进 NPC）
 *  - 「NPC」分类：AI 可扮演（仍可单独禁言）
 *  - 你当前扮演的人：AI 绝不扮演（否则会自己回自己）
 *  - 分类只用于归纳，与禁言是两件事
 */
object GroupPrefs {

    const val USER = "user"
    const val NPC = "npc"

    /** 解析 `{"characterId":"user"|"npc"}`；解析不了就当空表（= 全部 NPC）。 */
    fun parseKinds(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        val obj = runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return emptyMap()
        val out = mutableMapOf<String, String>()
        for ((k, v) in obj) {
            val kind = runCatching { v.jsonPrimitive.content }.getOrNull() ?: continue
            out[k] = if (kind == USER) USER else NPC
        }
        return out
    }

    fun toJson(kinds: Map<String, String>): String = buildJsonObject {
        for ((k, v) in kinds) put(k, JsonPrimitive(if (v == USER) USER else NPC))
    }.toString()

    /** 某个成员属于哪一栏；没标记的默认 NPC（保持旧行为：AI 可以演他）。 */
    fun kindOf(kinds: Map<String, String>, characterId: String): String =
        if (kinds[characterId] == USER) USER else NPC

    fun isUser(kinds: Map<String, String>, characterId: String): Boolean =
        kindOf(kinds, characterId) == USER

    /** 某栏里的成员（保持群内原有顺序）。 */
    fun inKind(members: List<String>, kinds: Map<String, String>, kind: String): List<String> =
        members.filter { kindOf(kinds, it) == kind }

    /**
     * 「依次都回」时 AI 实际应该表演谁：
     * 只取 NPC 栏、且没被禁言、且不是你当前扮演的人。
     */
    fun aiSpeakers(
        members: List<String>,
        kinds: Map<String, String>,
        muted: List<String>,
        playingAs: String?,
    ): List<String> = members.filter { id ->
        kindOf(kinds, id) == NPC && id !in muted && id != playingAs
    }

    /**
     * 禁言菜单里该显示谁 —— 与「NPC」栏实时一致。
     * （你想让 AI 演某个用户角色，就把他移进 NPC 栏，他会自动出现在禁言菜单里。）
     */
    fun muteMenuMembers(members: List<String>, kinds: Map<String, String>): List<String> =
        inKind(members, kinds, NPC)

    /** 切换「用户 / NPC」。 */
    fun withKind(kinds: Map<String, String>, characterId: String, kind: String): Map<String, String> =
        kinds.toMutableMap().apply { put(characterId, if (kind == USER) USER else NPC) }

    /**
     * 打开会话时是否必须先选扮演者：没有记录、或记录的人已经不在群里 → 必须选。
     */
    fun needsPlayAsChoice(playingAs: String?, members: List<String>): Boolean =
        playingAs == null || playingAs.isEmpty() || playingAs !in members
}
