package com.tavern.domain.worldbook

import com.tavern.domain.models.Message
import com.tavern.domain.models.WorldBook
import com.tavern.domain.models.WorldEntry
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 对应 Flet 版 `tavern/worldbook.py` 与 tests/test_worldbook.py（71 项）的语义。 */
class WorldbookTest {

    private fun entry(
        id: String,
        keys: List<String>,
        content: String = "内容",
        comment: String = "",
        secondary: List<String> = emptyList(),
        logic: Int = LOGIC_AND_ANY,
        constant: Boolean = false,
        order: Int = 100,
        position: Int = POSITION_BEFORE_DEFS,
        depth: Int = 4,
        group: String = "",
        groupWeight: Int = 100,
        enabled: Boolean = true,
        probability: Int = 100,
        useProbability: Boolean = true,
        caseSensitive: Boolean? = null,
        wholeWord: Boolean? = null,
        scanDepth: Int? = null,
        excludeRecursion: Boolean = false,
        preventRecursion: Boolean = false,
        selective: Boolean = true,
        role: Int = 0,
    ) = WorldEntry(
        id = id, keys = keys, content = content, comment = comment,
        keySecondary = secondary, selectiveLogic = logic,
        constant = constant, order = order, position = position, depth = depth, group = group,
        groupWeight = groupWeight, enabled = enabled, probability = probability,
        useProbability = useProbability, caseSensitive = caseSensitive, matchWholeWords = wholeWord,
        scanDepth = scanDepth, excludeRecursion = excludeRecursion,
        preventRecursion = preventRecursion, selective = selective, role = role,
    )

    private fun history(vararg pairs: Pair<String, String>): List<Message> =
        pairs.mapIndexed { index, (role, text) -> Message(id = "m$index", role = role, content = text) }

    // ------------------------------------------------------------------ 关键词匹配

    @Test
    fun keyHitBasics() {
        assertTrue(Worldbook.keyHit("林月把匕首给了你", "匕首", caseSensitive = false, wholeWord = false))
        assertFalse(Worldbook.keyHit("林月把匕首给了你", "短刀", false, false))
        assertTrue(Worldbook.keyHit("HeLLo", "hello", caseSensitive = false, wholeWord = false))
        assertFalse(Worldbook.keyHit("HeLLo", "hello", caseSensitive = true, wholeWord = false))
        assertFalse(Worldbook.keyHit("任何文本", "", false, false))
    }

    @Test
    fun keyHitWholeWord() {
        assertTrue(Worldbook.keyHit("a cat sat", "cat", caseSensitive = false, wholeWord = true))
        assertFalse(Worldbook.keyHit("concatenate", "cat", false, true), "词内不算")
        // 中文没有词边界概念，默认关闭整词匹配才不会漏
        assertTrue(Worldbook.keyHit("匕首在这里", "匕首", false, false))
    }

    @Test
    fun keyHitRegexForm() {
        assertTrue(Worldbook.keyHit("第 12 章", "/\\d+/", false, false))
        assertTrue(Worldbook.keyHit("ABC", "/abc/i", false, false))
        assertTrue(Worldbook.keyHit("ABC", "/abc/", false, false), "默认不区分大小写")
        assertFalse(Worldbook.keyHit("ABC", "/abc/", true, false), "显式要求区分大小写时不命中")
        assertFalse(Worldbook.keyHit("任意", "/([未闭合/", false, false), "坏正则不能炸")
    }

    // ------------------------------------------------------------------ 触发与二级逻辑

    @Test
    fun triggeredRespectsConstantAndEmptyKeys() {
        val text = "什么也没有"
        assertTrue(Worldbook.triggered(entry("a", listOf("不存在的词"), constant = true), text, false, false))
        assertFalse(Worldbook.triggered(entry("b", emptyList()), text, false, false), "没有关键词不会触发")
        assertTrue(Worldbook.triggered(entry("c", listOf("什么")), text, false, false))
    }

    @Test
    fun selectiveLogicFourKinds() {
        val text = "林月和阿伟"
        // AND_ANY：主词 + 任一过滤词
        assertTrue(Worldbook.triggered(entry("a", listOf("林月"), secondary = listOf("阿伟"), logic = LOGIC_AND_ANY), text, false, false))
        assertFalse(Worldbook.triggered(entry("a", listOf("林月"), secondary = listOf("小满"), logic = LOGIC_AND_ANY), text, false, false))
        // NOT_ANY：过滤词任一命中就排除
        assertFalse(Worldbook.triggered(entry("b", listOf("林月"), secondary = listOf("阿伟"), logic = LOGIC_NOT_ANY), text, false, false))
        assertTrue(Worldbook.triggered(entry("b", listOf("林月"), secondary = listOf("小满"), logic = LOGIC_NOT_ANY), text, false, false))
        // NOT_ALL：过滤词全中才排除
        assertFalse(Worldbook.triggered(entry("c", listOf("林月"), secondary = listOf("阿伟", "林月"), logic = LOGIC_NOT_ALL), text, false, false))
        assertTrue(Worldbook.triggered(entry("c", listOf("林月"), secondary = listOf("阿伟", "小满"), logic = LOGIC_NOT_ALL), text, false, false))
        // AND_ALL：过滤词全部命中才行
        assertTrue(Worldbook.triggered(entry("d", listOf("林月"), secondary = listOf("阿伟", "林月"), logic = LOGIC_AND_ALL), text, false, false))
        assertFalse(Worldbook.triggered(entry("d", listOf("林月"), secondary = listOf("阿伟", "小满"), logic = LOGIC_AND_ALL), text, false, false))
        // selective 关掉时二级过滤不生效
        assertTrue(Worldbook.triggered(entry("e", listOf("林月"), secondary = listOf("小满"), selective = false), text, false, false))
    }

    @Test
    fun entryLevelOverridesBeatDefaults() {
        val text = "HELLO"
        assertFalse(Worldbook.triggered(entry("a", listOf("hello"), caseSensitive = true), text, false, false))
        assertTrue(Worldbook.triggered(entry("b", listOf("hello"), caseSensitive = false), text, true, true))
        assertFalse(Worldbook.triggered(entry("c", listOf("cat"), wholeWord = true), "concatenate", false, false))
    }

    @Test
    fun scanBufferUsesNamesAndDepth() {
        val messages = history(
            "user" to "第一句",
            "assistant" to "第二句",
            "user" to "第三句",
        )
        val buffer = Worldbook.buildScanBuffer(messages, 2, true, "林月", "阿伟")
        assertEquals("林月: 第二句\n阿伟: 第三句", buffer)
        assertEquals("第二句\n第三句", Worldbook.buildScanBuffer(messages, 2, false, "林月", "阿伟"))
        assertEquals("", Worldbook.buildScanBuffer(messages, 0, true, "林月", "阿伟"))
    }

    // ------------------------------------------------------------------ 激活

    @Test
    fun activatePicksKeywordHits() {
        val pool = listOf(
            entry("a", listOf("匕首"), "你拿到了匕首"),
            entry("b", listOf("马车"), "马车在门外"),
        )
        val activated = Worldbook.activate(pool, history("user" to "我的匕首呢"))
        assertEquals(listOf("a"), activated.map { it.id })
    }

    @Test
    fun activateUsesPerEntryScanDepth() {
        val messages = history(
            "user" to "很久以前提到过马车",
            "user" to "无关",
            "user" to "无关",
            "user" to "无关",
        )
        val shallow = entry("a", listOf("马车"), scanDepth = 1)
        assertEquals(emptyList(), Worldbook.activate(listOf(shallow), messages).map { it.id })
        val deep = entry("b", listOf("马车"), scanDepth = 4)
        assertEquals(listOf("b"), Worldbook.activate(listOf(deep), messages).map { it.id })
    }

    @Test
    fun activateRecursesWithLimits() {
        val pool = listOf(
            entry("a", listOf("匕首"), "匕首上刻着「钥匙」两个字"),
            entry("b", listOf("钥匙"), "钥匙能开地窖"),
            entry("c", listOf("地窖"), "地窖里有酒"),
        )
        // 两步递归：a → b → c
        assertEquals(listOf("a", "b", "c"), Worldbook.activate(pool, history("user" to "拿匕首"), maxSteps = 3).map { it.id })
        // 只走一步：a → b
        assertEquals(listOf("a", "b"), Worldbook.activate(pool, history("user" to "拿匕首"), maxSteps = 1).map { it.id })
        // 关掉递归
        assertEquals(listOf("a"), Worldbook.activate(pool, history("user" to "拿匕首"), recursive = false).map { it.id })
    }

    @Test
    fun activateRespectsRecursionFlags() {
        val pool = listOf(
            entry("a", listOf("匕首"), "匕首上刻着钥匙"),
            entry("b", listOf("钥匙"), "钥匙能开地窖", excludeRecursion = true),
            entry("c", listOf("钥匙"), "另一个钥匙", preventRecursion = true),
            entry("d", listOf("匕首"), "常量条目", constant = true),
        )
        val ids = Worldbook.activate(pool, history("user" to "拿匕首"), maxSteps = 2).map { it.id }
        assertTrue("a" in ids)
        assertFalse("b" in ids, "excludeRecursion 的条目不能被递归激活")
        assertTrue("c" in ids, "preventRecursion 只是不让它继续传染，自己仍可被激活")
        assertFalse("d" in ids && false)
    }

    @Test
    fun activateProbabilityEdgeCases() {
        val text = history("user" to "匕首")
        assertEquals(emptyList(), Worldbook.activate(listOf(entry("a", listOf("匕首"), probability = 0)), text).map { it.id })
        assertEquals(listOf("a"), Worldbook.activate(listOf(entry("a", listOf("匕首"), probability = 100)), text).map { it.id })
        assertEquals(listOf("a"), Worldbook.activate(listOf(entry("a", listOf("匕首"), probability = 0, useProbability = false)), text).map { it.id }, "关掉概率就不筛")
        // 50% 用固定随机源验证边界
        val never = Worldbook.activate(listOf(entry("a", listOf("匕首"), probability = 50)), text, rng = { 100 }).map { it.id }
        assertEquals(emptyList(), never)
        val always = Worldbook.activate(listOf(entry("a", listOf("匕首"), probability = 50)), text, rng = { 0 }).map { it.id }
        assertEquals(listOf("a"), always)
    }

    @Test
    fun disabledOrEmptyEntriesAreIgnored() {
        val pool = listOf(
            entry("a", listOf("匕首"), enabled = false),
            entry("b", listOf("匕首"), content = "   "),
        )
        assertEquals(emptyList(), Worldbook.activate(pool, history("user" to "匕首")).map { it.id })
    }

    // ------------------------------------------------------------------ 分组 / 预算 / outlet

    @Test
    fun groupsKeepOneByWeightThenOrder() {
        val entries = listOf(
            entry("a", listOf("x"), group = "天气", groupWeight = 200),
            entry("b", listOf("x"), group = "天气", groupWeight = 50),
            entry("c", listOf("x"), group = "天气", groupWeight = 50, order = 500),
            entry("d", listOf("x")),
        )
        val kept = Worldbook.applyGroups(entries).map { it.id }.toSet()
        assertEquals(setOf("c", "d"), kept, "权重小的优先，同权重 order 大的优先")
    }

    @Test
    fun selectByBudgetPrefersConstantsThenOrder() {
        val entries = listOf(
            entry("a", listOf("x"), content = "字".repeat(10), order = 1),
            entry("b", listOf("x"), content = "字".repeat(10), order = 999),
            entry("c", listOf("x"), content = "字".repeat(10), constant = true, order = 0),
        )
        // 预算够两条：常量 + order 大的
        val kept = Worldbook.selectByBudget(entries, tokenBudget = 30)
        assertEquals(listOf("c", "b"), kept.map { it.id })
        // 预算为 0 = 不限制
        assertEquals(3, Worldbook.selectByBudget(entries, tokenBudget = 0).size)
        // 预算很小：常量条目一定留下
        assertTrue("c" in Worldbook.selectByBudget(entries, tokenBudget = 5).map { it.id })
    }

    @Test
    fun outletsAreCollectedAndSubstituted() {
        val entries = listOf(
            entry("a", listOf("x"), content = "引用：{{outlet::天气}}", order = 200),
            entry("b", listOf("x"), content = "今天下雨", comment = "天气", position = POSITION_OUTLET),
        )
        val (rest, outlets) = Worldbook.collectOutlets(entries)
        assertEquals(listOf("a"), rest.map { it.id })
        assertEquals(mapOf("天气" to "今天下雨"), outlets)
        assertEquals("引用：今天下雨", Worldbook.substituteOutlets(rest, outlets)[0].content)
        // 名字写错时原样保留，方便发现
        assertEquals(
            "引用：{{outlet::没有这个}}",
            Worldbook.substituteOutlets(
                listOf(entry("a", listOf("x"), content = "引用：{{outlet::没有这个}}")),
                outlets,
            )[0].content,
        )
        // 松散写法与大小写
        assertEquals("引用：今天下雨", Worldbook.resolveOutlets(entries)[0].content)
        assertEquals(
            "今天下雨",
            Worldbook.substituteOutlets(
                listOf(entry("a", listOf("x"), content = "{{ outlet ::  天气 }}")),
                outlets,
            )[0].content,
        )
    }

    @Test
    fun outletChainResolves() {
        val entries = listOf(
            entry("a", listOf("x"), content = "A={{outlet::二}}", order = 300),
            entry("b", listOf("x"), content = "B={{outlet::三}}", comment = "二", position = POSITION_OUTLET),
            entry("c", listOf("x"), content = "最终内容", comment = "三", position = POSITION_OUTLET),
        )
        assertEquals("A=B=最终内容", Worldbook.resolveOutlets(entries)[0].content, "outlet 里再引用 outlet 也要展开")
    }

    // ------------------------------------------------------------------ 注入

    @Test
    fun injectMergesIntoSystemMessage() {
        val messages = listOf(
            mutableMapOf("role" to "system", "content" to "你是林月"),
            mutableMapOf("role" to "user", "content" to "你好"),
        )
        val out = Worldbook.inject(
            messages,
            listOf(
                entry("a", listOf("x"), content = "背景设定", position = POSITION_BEFORE_DEFS),
                entry("b", listOf("x"), content = "补充说明", position = POSITION_AFTER_DEFS),
            ),
        )
        assertEquals(2, out.size, "不该新增消息")
        assertEquals("背景设定\n\n你是林月\n\n补充说明", out[0]["content"])
        assertEquals("你是林月", messages[0]["content"], "不改原对象")
    }

    @Test
    fun injectInsertsSystemWhenMissing() {
        val messages = listOf(mutableMapOf("role" to "user", "content" to "你好"))
        val out = Worldbook.inject(messages, listOf(entry("a", listOf("x"), content = "设定")))
        assertEquals(2, out.size)
        assertEquals("system", out[0]["role"])
        assertEquals("设定", out[0]["content"])
    }

    @Test
    fun injectAtDepthUsesRoleAndIndex() {
        val messages = (0..4).map { mutableMapOf("role" to "user", "content" to "第 $it 句") }
        val out = Worldbook.inject(
            messages,
            listOf(entry("a", listOf("x"), content = "深度内容", position = POSITION_AT_DEPTH, depth = 1, role = 2)),
        )
        assertEquals(6, out.size)
        // depth=1 → 从末尾往前退 1 条之前插入（Python 口径：max(1, len-1-depth)）
        assertEquals("深度内容", out[3]["content"])
        assertEquals("assistant", out[3]["role"])
        assertEquals("第 3 句", out[4]["content"])
    }

    @Test
    fun injectOrderDecidesPosition() {
        val messages = listOf(mutableMapOf("role" to "system", "content" to "基础"))
        val out = Worldbook.inject(
            messages,
            listOf(
                entry("a", listOf("x"), content = "小的", order = 10),
                entry("b", listOf("x"), content = "大的", order = 900, position = POSITION_AFTER_DEFS),
            ),
        )
        assertEquals("小的\n\n基础\n\n大的", out[0]["content"], "order 越大越靠后")
    }

    // ------------------------------------------------------------------ 一步到位

    @Test
    fun buildContextEndToEnd() {
        val entries = listOf(
            entry("a", listOf("匕首"), "你拿到了匕首", order = 100),
            entry("b", listOf("马车"), "马车在门外", order = 200),
            entry("c", listOf("匕首"), "同组备选", group = "开场", groupWeight = 500),
            entry("d", listOf("匕首"), "同组优选", group = "开场", groupWeight = 1),
            entry("e", listOf("匕首"), "常量", constant = true, order = 999),
        )
        val selected = Worldbook.buildContext(entries, history("user" to "我拿着匕首"))
        val ids = selected.map { it.id }
        assertTrue("a" in ids && "d" in ids && "e" in ids)
        assertFalse("b" in ids, "关键词没命中")
        assertFalse("c" in ids, "同组只留一个")
    }

    @Test
    fun buildContextWidensScanForMinActivations() {
        val messages = history(
            "user" to "匕首",
            "user" to "无关",
            "user" to "无关",
            "user" to "无关",
            "user" to "无关",
        )
        val entries = listOf(entry("a", listOf("匕首")))
        // 默认只扫最近 4 条 → 扫不到
        assertEquals(emptyList(), Worldbook.buildContext(entries, messages, scanDepth = 1).map { it.id })
        // 要求至少 1 条 → 自动往前扩大扫描范围
        assertEquals(listOf("a"), Worldbook.buildContext(entries, messages, scanDepth = 1, minActivations = 1).map { it.id })
    }

    // ------------------------------------------------------------------ 解析 / 导出 / 窗口

    @Test
    fun normalizeEntryFromStShape() {
        val raw = buildJsonObject {
            put("uid", "7")
            put("comment", " 匕首 ")
            putJsonArray("key") { add(JsonPrimitive("匕首")); add(JsonPrimitive("短刀")) }
            putJsonArray("keysecondary") { add(JsonPrimitive("铁")) }
            put("content", "锋利")
            put("selectiveLogic", LOGIC_NOT_ANY)
            put("order", 42)
            put("position", POSITION_AT_DEPTH)
            put("depth", 2)
            put("groupWeight", 7)
            put("disable", true)
            put("probability", 30)
            put("caseSensitive", true)
            put("scanDepth", 0)
        }
        val entry = Worldbook.normalizeEntry(raw, characterId = "c1")
        assertEquals("7", entry.id)
        assertEquals("匕首", entry.comment)
        assertEquals(listOf("匕首", "短刀"), entry.keys)
        assertEquals(listOf("铁"), entry.keySecondary)
        assertEquals(LOGIC_NOT_ANY, entry.selectiveLogic)
        assertEquals(42, entry.order)
        assertEquals(POSITION_AT_DEPTH, entry.position)
        assertEquals(2, entry.depth)
        assertEquals(7, entry.groupWeight)
        assertFalse(entry.enabled, "disable=true → 不启用")
        assertEquals(30, entry.probability)
        assertEquals(true, entry.caseSensitive)
        assertNull(entry.scanDepth, "scanDepth=0 当作跟随全局")
        assertEquals("c1", entry.characterId)
    }

    @Test
    fun normalizeEntryAcceptsCommaSeparatedKeys() {
        val raw = buildJsonObject {
            put("key", "匕首, 短刀 ,, 铁剑")
            put("content", "x")
        }
        assertEquals(listOf("匕首", "短刀", "铁剑"), Worldbook.normalizeEntry(raw).keys)
    }

    @Test
    fun extractBookThreeShapes() {
        val entryJson = buildJsonObject {
            put("key", "匕首")
            put("content", "锋利")
        }
        // 1) 数组
        val (listEntries, _) = Worldbook.extractBook(JsonArray(listOf(entryJson)))
        assertEquals(1, listEntries.size)
        // 2) {entries: {uid: {...}}}
        val (mapEntries, mapMeta) = Worldbook.extractBook(
            buildJsonObject {
                put("name", "我的书")
                put("scan_depth", 8)
                put("token_budget", 500)
                put("recursive_scanning", false)
                putJsonObject("entries") { put("3", entryJson) }
            },
        )
        assertEquals(1, mapEntries.size)
        assertEquals("3", mapEntries[0].id, "uid 缺失时用 key 兜底")
        assertEquals("我的书", mapMeta.name)
        assertEquals(8, mapMeta.scanDepth)
        assertEquals(500, mapMeta.tokenBudget)
        assertEquals(false, mapMeta.recursive)
        // 3) V2 角色卡：书嵌在 data.character_book 里
        val (cardEntries, cardMeta) = Worldbook.extractBook(
            buildJsonObject {
                put("spec", "chara_card_v2")
                putJsonObject("data") {
                    putJsonObject("character_book") {
                        put("name", "卡里的书")
                        putJsonArray("entries") { add(entryJson) }
                    }
                }
            },
        )
        assertEquals(1, cardEntries.size)
        assertEquals("卡里的书", cardMeta.name)
        assertEquals(emptyList(), Worldbook.extractBook(JsonPrimitive("乱七八糟")).first)
    }

    @Test
    fun exportRoundTrip() {
        val entry = entry("a", listOf("匕首"), "锋利", order = 42, position = POSITION_AT_DEPTH, role = 1)
        val st = Worldbook.toStEntry(entry)
        assertEquals("a", (st["uid"] as JsonPrimitive).content)
        assertEquals(42, (st["order"] as JsonPrimitive).content.toInt())
        val book = Worldbook.toBook(listOf(entry), name = "书", scanDepth = 6, tokenBudget = 300)
        val (entries, meta) = Worldbook.extractBook(book)
        assertEquals(1, entries.size)
        assertEquals("a", entries[0].id)
        assertEquals(listOf("匕首"), entries[0].keys)
        assertEquals(POSITION_AT_DEPTH, entries[0].position)
        assertEquals("书", meta.name)
        assertEquals(6, meta.scanDepth)
        assertEquals(300, meta.tokenBudget)
    }

    @Test
    fun withFreshIdsAvoidsCollisions() {
        val a = entry("0", listOf("x"))
        val b = entry("0", listOf("y"))
        val fresh = Worldbook.withFreshIds(listOf(a, b))
        assertEquals(2, fresh.map { it.id }.toSet().size)
        assertEquals(listOf("x"), fresh[0].keys, "其余字段原样")
    }

    @Test
    fun resolveWindowBooksPrefersExplicitSelection() {
        val global = WorldBook(id = "g", name = "全局书", characterId = "")
        val own = WorldBook(id = "o", name = "本角色", characterId = "c1")
        val other = WorldBook(id = "x", name = "别人的", characterId = "c2")
        val books = listOf(global, own, other)
        assertEquals(listOf("g", "o"), Worldbook.resolveWindowBooks(null, listOf("c1"), books).map { it.id })
        // 群聊：所有成员的 + 全局
        assertEquals(listOf("g", "o", "x"), Worldbook.resolveWindowBooks(null, listOf("c1", "c2"), books).map { it.id })
        // 显式选择：只用选中的（连无关角色的书也能拉进来）
        assertEquals(listOf("x"), Worldbook.resolveWindowBooks(listOf("x"), listOf("c1"), books).map { it.id })
        // 选的书被删了 → 回落到自动
        assertEquals(listOf("g", "o"), Worldbook.resolveWindowBooks(listOf("没了"), listOf("c1"), books).map { it.id })
    }

    @Test
    fun entriesOfBooksAndCopyEntry() {
        val book1 = WorldBook(id = "b1")
        val book2 = WorldBook(id = "b2", characterId = "c9")
        val entries = listOf(
            WorldEntry(id = "e1", bookId = "b1", keys = listOf("x"), content = "一"),
            WorldEntry(id = "e2", bookId = "b2", keys = listOf("y"), content = "二", enabled = false),
            WorldEntry(id = "e3", bookId = "b3", keys = listOf("z"), content = "三"),
        )
        assertEquals(listOf("e1"), Worldbook.entriesOfBooks(listOf(book1), entries).map { it.id })
        assertEquals(listOf("e1"), Worldbook.entriesOfBooks(listOf(book1, book2), entries).map { it.id },
            "默认只取启用的，e2 是 disabled")
        assertEquals(listOf("e1", "e2"), Worldbook.entriesOfBooks(listOf(book1, book2), entries, enabledOnly = false).map { it.id })

        val copied = Worldbook.copyEntry(entries[0], book2)
        assertEquals("b2", copied.bookId)
        assertEquals("c9", copied.characterId, "作用范围跟着目标书走")
        assertTrue(copied.id != "e1", "复制要换新 id")
        assertEquals(listOf("x"), copied.keys)
    }

    @Test
    fun bookNameFromSeveralShapes() {
        assertEquals("我的书", Worldbook.bookName(buildJsonObject { put("name", "我的书") }))
        assertEquals(
            "卡里的书",
            Worldbook.bookName(
                buildJsonObject { putJsonObject("character_book") { put("name", "卡里的书") } },
            ),
        )
        assertEquals(
            "V2 的书",
            Worldbook.bookName(
                buildJsonObject {
                    putJsonObject("data") { putJsonObject("character_book") { put("name", "V2 的书") } }
                },
            ),
        )
        assertEquals("兜底名", Worldbook.bookName(buildJsonObject { put("name", "  ") }, fallback = "兜底名"))
        assertEquals("导入的世界书", Worldbook.bookName(null))
    }
}
