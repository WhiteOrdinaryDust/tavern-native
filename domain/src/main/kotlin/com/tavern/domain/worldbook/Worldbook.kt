package com.tavern.domain.worldbook

import com.tavern.domain.models.Message
import com.tavern.domain.models.WorldBook
import com.tavern.domain.models.WorldEntry
import com.tavern.domain.models.newId
import com.tavern.domain.prompt.Prompt
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 插入位置（对应 ST 的 position 字段）。 */
const val POSITION_BEFORE_DEFS = 0
const val POSITION_AFTER_DEFS = 1
const val POSITION_AT_DEPTH = 4
const val POSITION_OUTLET = 7

/** ST 源码里的 selectiveLogic 枚举。 */
const val LOGIC_AND_ANY = 0
const val LOGIC_NOT_ALL = 1
const val LOGIC_NOT_ANY = 2
const val LOGIC_AND_ALL = 3

val LOGIC_OPTIONS: List<Pair<Int, String>> = listOf(
    LOGIC_AND_ANY to "主词 + 任一过滤词",
    LOGIC_NOT_ALL to "排除：过滤词全中",
    LOGIC_NOT_ANY to "排除：过滤词任一命中",
    LOGIC_AND_ALL to "主词 + 全部过滤词",
)

val POSITION_OPTIONS: List<Pair<Int, String>> = listOf(
    POSITION_BEFORE_DEFS to "角色设定之前",
    POSITION_AFTER_DEFS to "角色设定之后",
    POSITION_AT_DEPTH to "对话中（@深度）",
    POSITION_OUTLET to "不插入（作为引用素材 outlet）",
)

val ROLE_NAMES: Map<Int, String> = mapOf(0 to "system", 1 to "user", 2 to "assistant")

const val DEFAULT_SCAN_DEPTH = 4
const val DEFAULT_TOKEN_BUDGET = 1200
const val MAX_RECURSION_STEPS = 2
const val MAX_OUTLET_PASSES = 4

/** 可注入的随机源（概率触发用），便于测试复现。 */
fun interface Rng {
    fun nextInt(bound: Int): Int
}

/** `{{outlet::名字}}` —— 大小写不敏感，`outlet :: 名字` 这种松散写法也认。 */
private val OUTLET_REF = Regex(
    """\{\{\s*outlet\s*::\s*(?<name>[^}]+?)\s*\}\}""",
    RegexOption.IGNORE_CASE,
)

private val REGEX_KEY = Regex("^/(.*)/([a-z]*)$", RegexOption.DOT_MATCHES_ALL)

/** 世界书文件/角色卡里的书级设置。 */
data class BookMeta(
    val name: String = "",
    val description: String = "",
    val scanDepth: Int? = null,
    val tokenBudget: Int? = null,
    val recursive: Boolean? = null,
)

/**
 * 世界书（World Info / Lorebook）引擎，按 SillyTavern 的语义实现关键部分：
 * 触发扫描、二级过滤、常驻、递归、预算、位置与插入顺序。
 */
object Worldbook {

    // ------------------------------------------------------------------ 解析

    private fun asStrList(value: JsonElement?): List<String> = when (value) {
        is JsonPrimitive -> if (value.isString) {
            value.content.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        } else {
            emptyList()
        }
        is JsonArray -> value.mapNotNull { element ->
            val primitive = element as? JsonPrimitive ?: return@mapNotNull null
            if (!primitive.isString) return@mapNotNull null
            primitive.content.trim().takeIf { it.isNotEmpty() }
        }
        else -> emptyList()
    }

    private fun asOptBool(value: JsonElement?): Boolean? =
        (value as? JsonPrimitive)?.booleanOrNull

    private fun asInt(value: JsonElement?, default: Int): Int =
        (value as? JsonPrimitive)?.let { if (it.isString) it.content.toIntOrNull() else it.intOrNull }
            ?: default

    private fun pick(raw: JsonObject, vararg names: String): JsonElement? {
        for (name in names) {
            val value = raw[name]
            if (value != null) return value
        }
        return null
    }

    private fun pickString(raw: JsonObject, vararg names: String): String {
        val value = pick(raw, *names) ?: return ""
        return (value as? JsonPrimitive)?.content ?: ""
    }

    /** 把 ST 世界书条目（或卡内 character_book 条目）规范成 WorldEntry。 */
    fun normalizeEntry(raw: JsonObject, characterId: String = "", entryId: String = ""): WorldEntry {
        val uid = entryId.ifEmpty { pickString(raw, "id", "uid").trim() }.ifEmpty { newId() }
        val scanDepth = asInt(pick(raw, "scanDepth", "scan_depth"), 0)
        return WorldEntry(
            id = uid,
            characterId = characterId,
            comment = pickString(raw, "comment", "name", "title").trim(),
            keys = asStrList(pick(raw, "key", "keys")),
            keySecondary = asStrList(pick(raw, "keysecondary", "key_secondary", "secondary_keys")),
            content = pickString(raw, "content"),
            constant = (pick(raw, "constant") as? JsonPrimitive)?.booleanOrNull ?: false,
            selective = (pick(raw, "selective") as? JsonPrimitive)?.booleanOrNull ?: true,
            selectiveLogic = asInt(pick(raw, "selectiveLogic", "selective_logic"), LOGIC_AND_ANY),
            order = asInt(pick(raw, "order", "insertion_order"), 100),
            position = asInt(pick(raw, "position", "insertion_position"), POSITION_BEFORE_DEFS),
            depth = asInt(pick(raw, "depth"), 4),
            group = pickString(raw, "group").trim(),
            groupWeight = asInt(pick(raw, "groupWeight", "group_weight"), 100),
            role = asInt(pick(raw, "role"), 0),
            enabled = !((pick(raw, "disable") as? JsonPrimitive)?.booleanOrNull ?: false) &&
                ((pick(raw, "enabled") as? JsonPrimitive)?.booleanOrNull ?: true),
            probability = asInt(pick(raw, "probability"), 100),
            useProbability = (pick(raw, "useProbability", "use_probability") as? JsonPrimitive)
                ?.booleanOrNull ?: true,
            caseSensitive = asOptBool(pick(raw, "caseSensitive", "case_sensitive")),
            matchWholeWords = asOptBool(pick(raw, "matchWholeWords", "match_whole_words")),
            scanDepth = scanDepth.takeIf { it != 0 },
            excludeRecursion = (pick(raw, "excludeRecursion", "exclude_recursion") as? JsonPrimitive)
                ?.booleanOrNull ?: false,
            preventRecursion = (pick(raw, "preventRecursion", "prevent_recursion") as? JsonPrimitive)
                ?.booleanOrNull ?: false,
            raw = raw.toString().let { mapOf("json" to it) },
        )
    }

    /**
     * 从世界书文件 / 角色卡的 character_book 里取出条目与书级设置。
     * 兼容三种形态：`{entries: {uid: {...}}}`、`{entries: [...]}`、`[...]`。
     */
    fun extractBook(raw: JsonElement?, characterId: String = ""): Pair<List<WorldEntry>, BookMeta> {
        val book: JsonObject = when (raw) {
            is JsonArray -> buildJsonObject { put("entries", raw) }
            is JsonObject -> {
                val inner = raw["character_book"] as? JsonObject
                    ?: (raw["data"] as? JsonObject)?.get("character_book") as? JsonObject
                inner ?: raw
            }
            else -> return emptyList<WorldEntry>() to BookMeta()
        }

        val items = mutableListOf<Pair<String, JsonObject>>()
        when (val entriesRaw = book["entries"]) {
            is JsonObject -> for ((key, value) in entriesRaw) {
                if (value is JsonObject) items += key to value
            }
            is JsonArray -> for (value in entriesRaw) {
                if (value is JsonObject) items += "" to value
            }
            else -> Unit
        }

        val entries = items.map { (key, item) -> normalizeEntry(item, characterId, key) }
        val meta = BookMeta(
            name = pickString(book, "name").trim(),
            description = pickString(book, "description").trim(),
            scanDepth = (book["scan_depth"] as? JsonPrimitive)?.intOrNull,
            tokenBudget = (book["token_budget"] as? JsonPrimitive)?.intOrNull,
            recursive = (book["recursive_scanning"] as? JsonPrimitive)?.booleanOrNull,
        )
        return entries to meta
    }

    // ------------------------------------------------------------------ 匹配

    /** 单个关键词是否命中；支持 ST 的 `/regex/flags` 写法。 */
    fun keyHit(text: String, key: String, caseSensitive: Boolean, wholeWord: Boolean): Boolean {
        if (key.isEmpty()) return false
        val match = REGEX_KEY.matchEntire(key)
        if (match != null) {
            val pattern = match.groupValues[1]
            val flags = match.groupValues[2]
            val options = buildSet {
                if ('i' in flags) add(RegexOption.IGNORE_CASE)
                if ('m' in flags) add(RegexOption.MULTILINE)
                if ('s' in flags) add(RegexOption.DOT_MATCHES_ALL)
                if ('x' in flags) add(RegexOption.COMMENTS)
                if (!caseSensitive) add(RegexOption.IGNORE_CASE)
            }
            return try {
                Regex(pattern, options).containsMatchIn(text)
            } catch (_: Exception) {
                false
            }
        }

        val haystack = if (caseSensitive) text else text.lowercase()
        val needle = if (caseSensitive) key else key.lowercase()
        if (wholeWord) {
            val pattern = "(?<!\\w)" + Regex.escape(needle) + "(?!\\w)"
            val options = if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
            return Regex(pattern, options).containsMatchIn(haystack)
        }
        return haystack.contains(needle)
    }

    private fun anyHit(text: String, keys: List<String>, caseSensitive: Boolean, wholeWord: Boolean): Boolean =
        keys.any { keyHit(text, it, caseSensitive, wholeWord) }

    /** 二级过滤（selective）判定。 */
    private fun passesLogic(entry: WorldEntry, text: String, caseSensitive: Boolean, wholeWord: Boolean): Boolean {
        if (!entry.selective || entry.keySecondary.isEmpty()) return true
        val hits = entry.keySecondary.map { keyHit(text, it, caseSensitive, wholeWord) }
        return when (entry.selectiveLogic) {
            LOGIC_AND_ALL -> hits.all { it }
            LOGIC_NOT_ALL -> !hits.all { it }
            LOGIC_NOT_ANY -> !hits.any { it }
            else -> hits.any { it }  // AND_ANY
        }
    }

    fun triggered(
        entry: WorldEntry,
        text: String,
        defaultCaseSensitive: Boolean,
        defaultWholeWord: Boolean,
    ): Boolean {
        val caseSensitive = entry.caseSensitive ?: defaultCaseSensitive
        val wholeWord = entry.matchWholeWords ?: defaultWholeWord
        if (entry.constant) return true
        if (entry.keys.isEmpty()) return false
        if (!anyHit(text, entry.keys, caseSensitive, wholeWord)) return false
        return passesLogic(entry, text, caseSensitive, wholeWord)
    }

    /** 拼出用于匹配的文本缓冲（最近 scanDepth 条消息）。 */
    fun buildScanBuffer(
        history: List<Message>,
        scanDepth: Int = DEFAULT_SCAN_DEPTH,
        includeNames: Boolean = true,
        charName: String = "",
        userName: String = "",
    ): String {
        if (scanDepth <= 0) return ""
        val recent = history.takeLast(scanDepth)
        return recent.joinToString("\n") { msg ->
            val speaker = if (!includeNames) {
                ""
            } else when (msg.role) {
                "user" -> userName
                "assistant" -> charName
                else -> ""
            }
            val text = msg.content
            if (speaker.isNotEmpty()) "$speaker: $text" else text
        }
    }

    private fun passesProbability(entry: WorldEntry, rng: Rng): Boolean {
        if (!entry.useProbability) return true
        if (entry.probability >= 100) return true
        if (entry.probability <= 0) return false
        return rng.nextInt(100) + 1 <= entry.probability
    }

    // ------------------------------------------------------------------ 激活

    /** 返回被激活的条目（未做预算裁剪）。 */
    fun activate(
        entries: List<WorldEntry>,
        history: List<Message>,
        scanDepth: Int = DEFAULT_SCAN_DEPTH,
        includeNames: Boolean = true,
        charName: String = "",
        userName: String = "",
        caseSensitive: Boolean = false,
        wholeWord: Boolean = false,
        recursive: Boolean = true,
        maxSteps: Int = MAX_RECURSION_STEPS,
        rng: Rng? = null,
    ): List<WorldEntry> {
        val random = rng ?: Rng { bound -> java.util.Random().nextInt(bound) }
        val pool = entries.filter { it.enabled && it.content.trim().isNotEmpty() }
        if (pool.isEmpty()) return emptyList()

        val activated = linkedMapOf<String, WorldEntry>()
        val buffers = mutableMapOf<Int, String>()

        fun bufferFor(depth: Int): String = buffers.getOrPut(depth) {
            buildScanBuffer(history, depth, includeNames, charName, userName)
        }

        for (entry in pool) {
            val depth = entry.scanDepth ?: scanDepth
            val text = bufferFor(depth)
            if (triggered(entry, text, caseSensitive, wholeWord) && passesProbability(entry, random)) {
                activated[entry.id] = entry
            }
        }

        if (!recursive) return activated.values.toList()

        // 递归：已激活条目的正文里出现别的条目关键词时继续激活
        for (step in 0 until maxOf(0, maxSteps)) {
            val haystack = activated.values
                .filterNot { it.preventRecursion }
                .joinToString("\n") { it.content }
            if (haystack.isEmpty()) break
            var added = false
            for (entry in pool) {
                if (entry.id in activated || entry.excludeRecursion || entry.constant) continue
                if (entry.keys.isEmpty()) continue
                if (triggered(entry, haystack, caseSensitive, wholeWord) && passesProbability(entry, random)) {
                    activated[entry.id] = entry
                    added = true
                }
            }
            if (!added) break
        }
        return activated.values.toList()
    }

    /** 分组互斥：同组只保留一个条目（权重小的优先，同权重 order 大的优先，再按 id 稳定）。 */
    fun applyGroups(entries: List<WorldEntry>): List<WorldEntry> {
        val plain = mutableListOf<WorldEntry>()
        val grouped = linkedMapOf<String, MutableList<WorldEntry>>()
        for (entry in entries) {
            val name = entry.group.trim()
            if (name.isNotEmpty()) grouped.getOrPut(name) { mutableListOf() }.add(entry) else plain.add(entry)
        }
        for (members in grouped.values) {
            plain += members.sortedWith(compareBy({ it.groupWeight }, { -it.order }, { it.id })).first()
        }
        return plain
    }

    // ------------------------------------------------------------------ outlet

    /** 分出 outlet 条目：返回 (要正常注入的条目, {名字: 内容})。 */
    fun collectOutlets(entries: List<WorldEntry>): Pair<List<WorldEntry>, Map<String, String>> {
        val rest = mutableListOf<WorldEntry>()
        val outlets = linkedMapOf<String, String>()
        for (entry in entries) {
            if (entry.position == POSITION_OUTLET) {
                val name = entry.title.trim()
                if (name.isNotEmpty()) outlets[name] = entry.content
                continue
            }
            rest += entry
        }
        return rest to outlets
    }

    /** 把 `{{outlet::名字}}` 换成 outlet 的内容（支持 outlet 再引用 outlet）。 */
    fun substituteOutlets(entries: List<WorldEntry>, outlets: Map<String, String>): List<WorldEntry> {
        if (outlets.isEmpty()) return entries
        return entries.map { entry ->
            var text = entry.content
            for (pass in 0 until MAX_OUTLET_PASSES) {
                val replaced = OUTLET_REF.replace(text) { match ->
                    val name = match.groups["name"]?.value?.trim()?.lowercase() ?: ""
                    outlets.entries.firstOrNull { it.key.lowercase() == name }?.value ?: match.value
                }
                if (replaced == text) break
                text = replaced
            }
            if (text == entry.content) entry else entry.copy(content = text)
        }
    }

    /** 便捷函数：分出 outlet 并完成替换（测试与界面预览用）。 */
    fun resolveOutlets(entries: List<WorldEntry>): List<WorldEntry> {
        val (rest, outlets) = collectOutlets(entries)
        return substituteOutlets(rest, outlets)
    }

    /** 按预算挑选条目：常量优先，其次 order 大的优先。 */
    fun selectByBudget(entries: List<WorldEntry>, tokenBudget: Int = DEFAULT_TOKEN_BUDGET): List<WorldEntry> {
        if (tokenBudget <= 0) return entries
        val ordered = entries.sortedWith(
            compareBy({ if (it.constant) 0 else 1 }, { -it.order }, { it.id }),
        )
        val kept = mutableListOf<WorldEntry>()
        var used = 0
        for (entry in ordered) {
            val cost = Prompt.estimateTokens(entry.content) + 4
            if (kept.isNotEmpty() && used + cost > tokenBudget) continue
            kept += entry
            used += cost
        }
        return kept
    }

    // ------------------------------------------------------------------ 注入

    /** 把已选中的条目插进 messages（返回新列表，不改原对象）。 */
    fun inject(messages: List<Map<String, String>>, entries: List<WorldEntry>): List<Map<String, String>> {
        if (entries.isEmpty()) return messages.map { it.toMap() }
        val out = messages.map { it.toMutableMap() }.toMutableList()

        val before = entries.filter { it.position == POSITION_BEFORE_DEFS }.sortedBy { it.order }
        val after = entries
            .filter { it.position != POSITION_BEFORE_DEFS && it.position != POSITION_AT_DEPTH }
            .sortedBy { it.order }
        val depthEntries = entries.filter { it.position == POSITION_AT_DEPTH }.sortedBy { it.order }

        if (before.isNotEmpty() || after.isNotEmpty()) {
            if (out.isNotEmpty() && out[0]["role"] == "system") {
                val parts = mutableListOf<String>()
                if (before.isNotEmpty()) parts += before.joinToString("\n") { it.content }
                parts += out[0]["content"] ?: ""
                if (after.isNotEmpty()) parts += after.joinToString("\n") { it.content }
                out[0]["content"] = parts.filter { it.isNotEmpty() }.joinToString("\n\n")
            } else {
                val head = (before + after).joinToString("\n") { it.content }
                out.add(0, mutableMapOf("role" to "system", "content" to head))
            }
        }

        // @深度：depth 0 = 紧贴最后一条消息之前；depth N = 往前推 N 条
        val plan = depthEntries.map { entry ->
            maxOf(1, out.size - 1 - maxOf(0, entry.depth)) to entry
        }
        // 从后往前插，避免前面的插入影响后面的位置；同位置内按 order 从大到小插
        val sorted = plan.sortedWith(compareBy({ -it.first }, { -it.second.order }))
        for ((index, entry) in sorted) {
            out.add(
                index,
                mutableMapOf("role" to (ROLE_NAMES[entry.role] ?: "system"), "content" to entry.content),
            )
        }
        return out
    }

    /**
     * 一步到位：激活 + 分组互斥 + 预算裁剪 + outlet 替换，返回最终要插入的条目。
     *
     * `minActivations` > 0 时：按 `scanDepth` 扫出来的条目不够多，就逐步往前扩大扫描范围。
     */
    fun buildContext(
        entries: List<WorldEntry>,
        history: List<Message>,
        scanDepth: Int = DEFAULT_SCAN_DEPTH,
        tokenBudget: Int = DEFAULT_TOKEN_BUDGET,
        includeNames: Boolean = true,
        charName: String = "",
        userName: String = "",
        recursive: Boolean = true,
        rng: Rng? = null,
        minActivations: Int = 0,
    ): List<WorldEntry> {
        val pool = entries.filter { it.enabled && it.content.trim().isNotEmpty() }
        if (pool.isEmpty()) return emptyList()

        fun run(depth: Int): List<WorldEntry> = activate(
            pool, history, scanDepth = depth, includeNames = includeNames,
            charName = charName, userName = userName, recursive = recursive, rng = rng,
        )

        var activated = run(scanDepth)
        if (minActivations > 0 && activated.size < minActivations) {
            var depth = scanDepth
            val limit = history.size
            while (activated.size < minActivations && depth < limit) {
                depth = minOf(limit, depth + maxOf(4, scanDepth))
                activated = run(depth)
            }
        }

        val grouped = applyGroups(activated)
        // outlet 条目本身不注入，也不占预算；但只有被激活的 outlet 才能被引用
        val (injectable, outlets) = collectOutlets(grouped)
        val selected = selectByBudget(injectable, tokenBudget)
        return substituteOutlets(selected, outlets)
    }

    // ------------------------------------------------------------------ 导出

    /** 导出成 ST 世界书条目结构（字段名按 ST 规范，便于互导）。 */
    fun toStEntry(entry: WorldEntry): JsonObject = buildJsonObject {
        put("uid", entry.id)
        put("comment", entry.comment)
        put("key", JsonArray(entry.keys.map { JsonPrimitive(it) }))
        put("keysecondary", JsonArray(entry.keySecondary.map { JsonPrimitive(it) }))
        put("content", entry.content)
        put("constant", entry.constant)
        put("selective", entry.selective)
        put("selectiveLogic", entry.selectiveLogic)
        put("order", entry.order)
        put("position", entry.position)
        put("depth", entry.depth)
        put("role", entry.role)
        put("group", entry.group)
        put("groupWeight", entry.groupWeight)
        put("disable", !entry.enabled)
        put("probability", entry.probability)
        put("useProbability", entry.useProbability)
        entry.caseSensitive?.let { put("caseSensitive", it) }
        entry.matchWholeWords?.let { put("matchWholeWords", it) }
        entry.scanDepth?.let { put("scanDepth", it) }
        put("excludeRecursion", entry.excludeRecursion)
        put("preventRecursion", entry.preventRecursion)
    }

    /**
     * 导入时给条目换一批新 id。
     *
     * ST 世界书里的 uid 通常是 0/1/2…，只在**一本书内**唯一；直接沿用会覆盖别的书的条目。
     */
    fun withFreshIds(entries: List<WorldEntry>): List<WorldEntry> = entries.map { it.copy(id = newId()) }

    /**
     * 某个会话窗口实际生效的世界书。
     *
     * - `selected` 非空：按用户在这个窗口里选的书（过滤掉已删除的）
     * - `selected` 为空：自动模式 —— 全局书 + 本角色（群聊则是所有成员）的书
     */
    fun resolveWindowBooks(
        selected: List<String>?,
        ownerIds: List<String>,
        books: List<WorldBook>,
    ): List<WorldBook> {
        val byId = books.associateBy { it.id }
        val picked = (selected ?: emptyList()).mapNotNull { byId[it] }
        if (picked.isNotEmpty()) return picked
        val owners = (ownerIds.map { it } + "").toSet()
        return books.filter { it.characterId in owners }
    }

    /** 这些书里的条目（可选只取启用的）。 */
    fun entriesOfBooks(
        books: List<WorldBook>,
        entries: List<WorldEntry>,
        enabledOnly: Boolean = true,
    ): List<WorldEntry> {
        val ids = books.map { it.id }.toSet()
        return entries.filter { it.bookId in ids && (it.enabled || !enabledOnly) }
    }

    /** 把条目复制到另一本书：新 id、跟着目标书的作用范围走，其余原样。 */
    fun copyEntry(entry: WorldEntry, book: WorldBook): WorldEntry {
        val now = System.currentTimeMillis() / 1000.0
        return entry.copy(
            id = newId(),
            bookId = book.id,
            characterId = book.characterId,
            createdAt = now,
            updatedAt = now,
        )
    }

    /** 从世界书 JSON 里取书名（name → character_book.name → data.character_book.name → 兜底）。 */
    fun bookName(raw: JsonElement?, fallback: String = ""): String {
        val candidates = mutableListOf<String?>()
        if (raw is JsonObject) {
            candidates += pickString(raw, "name")
            // 常见的其它写法（不同工具导出的世界书字段名不一样）
            candidates += pickString(raw, "book_name")
            candidates += pickString(raw, "title")
            candidates += pickString(raw, "world_name")
            (raw["character_book"] as? JsonObject)?.let { candidates += pickString(it, "name") }
            (raw["data"] as? JsonObject)?.let { data ->
                candidates += pickString(data, "name")
                candidates += pickString(data, "book_name")
                (data["character_book"] as? JsonObject)?.let { candidates += pickString(it, "name") }
            }
        }
        for (value in candidates) {
            if (!value.isNullOrBlank()) return value.trim()
        }
        return fallback.trim().ifEmpty { "导入的世界书" }
    }

    /** 导出成 ST 世界书文件结构（也可直接作为角色卡的 character_book）。 */
    fun toBook(
        entries: List<WorldEntry>,
        name: String = "",
        description: String = "",
        scanDepth: Int = DEFAULT_SCAN_DEPTH,
        tokenBudget: Int = DEFAULT_TOKEN_BUDGET,
        recursive: Boolean = true,
    ): JsonObject = buildJsonObject {
        put("name", name)
        put("description", description)
        put("scan_depth", scanDepth)
        put("token_budget", tokenBudget)
        put("recursive_scanning", recursive)
        put("entries", buildJsonObject { entries.forEach { put(it.id, toStEntry(it)) } })
    }
}
