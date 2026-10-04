package com.tavern.domain.postprocess

import com.tavern.domain.models.Character
import com.tavern.domain.models.Chat
import com.tavern.domain.models.Message
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 与 Flet 版 tests/test_core.py 的呈现/清洗部分逐条对应。 */
class PostprocessTest {

    // ------------------------------------------------------------ 头像文字

    @Test
    fun avatarLinesChinese() {
        assertEquals(listOf("?"), Postprocess.avatarLines(""))
        assertEquals(listOf("林"), Postprocess.avatarLines("林"))
        assertEquals(listOf("林月"), Postprocess.avatarLines("林月"))
        assertEquals(listOf("林月清"), Postprocess.avatarLines("林月清"))
        assertEquals(listOf("林月", "清辉"), Postprocess.avatarLines("林月清辉"))
        assertEquals(listOf("林月", "清辉"), Postprocess.avatarLines("林月清辉上"), "超过 4 字只取前 4 个")
        assertEquals(listOf("林月"), Postprocess.avatarLines(" 林 月 "), "空白要去掉")
    }

    @Test
    fun avatarLinesLatin() {
        assertEquals(listOf("JS"), Postprocess.avatarLines("John Smith"))
        assertEquals(listOf("Jo"), Postprocess.avatarLines("Jo"))
        assertEquals(listOf("ab"), Postprocess.avatarLines("ab"), "单个词保持原样，不做大写")
    }

    @Test
    fun avatarFontRatio() {
        assertEquals(0.42, Postprocess.avatarFontRatio(listOf("A")))
        assertEquals(0.34, Postprocess.avatarFontRatio(listOf("林月")))
        assertEquals(0.23, Postprocess.avatarFontRatio(listOf("林月清")))
        assertEquals(0.23, Postprocess.avatarFontRatio(listOf("林月清辉")))
        assertEquals(0.32, Postprocess.avatarFontRatio(listOf("林月", "清辉")))
        assertEquals(0.42, Postprocess.avatarFontRatio(emptyList()))
    }

    // ------------------------------------------------------------ 样式分段

    @Test
    fun toSpansMarksActionsAndEmphasis() {
        val spans = Postprocess.toSpans("*擦着杯子* 说：**真的吗**")
        assertEquals(3, spans.size)
        assertEquals(Span("擦着杯子", italic = true), spans[0])
        assertEquals(Span(" 说："), spans[1])
        assertEquals(Span("真的吗", bold = true), spans[2])
    }

    @Test
    fun toSpansKeepsStrayAsteriskAndNewlines() {
        val spans = Postprocess.toSpans("a * b\nc")
        assertEquals(listOf(Span("a * b\nc")), spans)
        assertEquals(listOf(Span("")), Postprocess.toSpans(""))
    }

    @Test
    fun toSpansAllowsMultilineAction() {
        val spans = Postprocess.toSpans("*她笑了\n然后走开*")
        assertEquals(listOf(Span("她笑了\n然后走开", italic = true)), spans)
    }

    // ------------------------------------------------------------ 名称前缀

    @Test
    fun stripNamePrefixOnlyAtStart() {
        assertEquals("*擦着杯子* 又来啦？", Postprocess.stripNamePrefix("林月：*擦着杯子* 又来啦？", "林月"))
        assertEquals("你好", Postprocess.stripNamePrefix("**林月**：你好", "林月"))
        assertEquals("我说 林月：你好", Postprocess.stripNamePrefix("我说 林月：你好", "林月"), "只处理开头")
        assertEquals("", Postprocess.stripNamePrefix("林月:", "林月"))
        assertEquals("喂", Postprocess.stripNamePrefix("喂", "林月"))
        assertEquals("大家好", Postprocess.stripNamePrefix("大家好", ""))
    }

    // ------------------------------------------------------------ 清洗规则

    @Test
    fun parseRulesReadsLines() {
        val parsed = Postprocess.parseRules("a=>b\n# 注释\n\n删除我\nc => d")
        assertEquals(3, parsed.rules.size)
        assertEquals("a", parsed.rules[0].first.pattern)
        assertEquals("b", parsed.rules[0].second)
        assertEquals("删除我", parsed.rules[1].first.pattern)
        assertEquals("", parsed.rules[1].second, "省略 => 表示删除")
        assertTrue(parsed.errors.isEmpty(), parsed.errors.toString())
    }

    @Test
    fun parseRulesReportsBadRegex() {
        val parsed = Postprocess.parseRules("([=>x")
        assertTrue(parsed.rules.isEmpty())
        assertEquals(1, parsed.errors.size)
        assertTrue(parsed.errors[0].startsWith("第 1 行正则无效"), parsed.errors[0])
    }

    @Test
    fun parseRulesSubstitutesMacros() {
        val parsed = Postprocess.parseRules("{{char}}：=>\n<BOT>说：=>", charName = "林月", userName = "阿伟")
        assertEquals("林月：", parsed.rules[0].first.pattern)
    }

    @Test
    fun cleanReplyAppliesRulesAndTrims() {
        val out = Postprocess.cleanReply(
            "  林月：你好呀（小声）  ",
            "林月",
            rulesText = "（小声）=>",
        )
        assertEquals("你好呀", out)
    }

    @Test
    fun cleanReplyCanSkipPrefixStripping() {
        assertEquals("林月：你好", Postprocess.cleanReply("林月：你好", "林月", stripPrefix = false))
    }

    // ------------------------------------------------------------ 导出

    @Test
    fun exportMarkdownShape() {
        val character = Character(name = "林月")
        val messages = listOf(
            Message(role = "user", content = "在吗"),
            Message(role = "assistant", content = "在的", reasoning = "想一下"),
        )
        val text = Postprocess.exportMarkdown(character, Chat(title = ""), messages)
        assertTrue(text.startsWith("# 与 林月 的会话"), text)
        assertTrue(text.contains("- 角色：林月"))
        assertTrue(text.contains("- 用户：User"))
        assertTrue(text.contains("- 消息数：2"))
        assertTrue(text.contains("### User"))
        assertTrue(text.contains("### 林月"))
        assertTrue(!text.contains("思考过程"), "默认不导出思考")

        val withReasoning = Postprocess.exportMarkdown(character, Chat(title = "雨夜"), messages, includeReasoning = true)
        assertTrue(withReasoning.startsWith("# 雨夜"))
        assertTrue(withReasoning.contains("<details><summary>思考过程</summary>"))
        assertTrue(withReasoning.endsWith("\n"))
    }

    @Test
    fun exportMarkdownGroupUsesSpeakerNames() {
        val text = Postprocess.exportMarkdown(
            Character(name = "群主"),
            Chat(title = "群聊"),
            listOf(Message(role = "assistant", content = "甲说话", speaker = "a")),
            speakerNames = mapOf("a" to "甲", "b" to "乙"),
        )
        assertTrue(text.contains("- 角色：甲、乙"), text)
        assertTrue(text.contains("### 甲"), text)
    }

    @Test
    fun safeFilename() {
        assertEquals("a_b_c", Postprocess.safeFilename("a/b:c"))
        assertEquals("chat", Postprocess.safeFilename(""))
        assertEquals("chat", Postprocess.safeFilename("   "))
        assertEquals("chat", Postprocess.safeFilename("..."))
        assertEquals(40, Postprocess.safeFilename("x".repeat(80)).length)
    }

    @Test
    fun cleanReplySupportsSlashFlagsRules() {
        // SillyTavern 脚本导入后的规则形如 /pattern/i，忽略大小写要生效
        val out = Postprocess.cleanReply("ABC abc 其他", "林月", rulesText = "/abc/i => X")
        assertEquals("X X 其他", out)
        // 不带 flags 时区分大小写
        assertEquals("ABC X", Postprocess.cleanReply("ABC abc", "林月", rulesText = "abc => X"))
    }

    @Test
    fun cleanReplyExpandsJsStyleGroups() {
        assertEquals("林月是最棒的", Postprocess.cleanReply(
            "最棒的是林月",
            "林月",
            rulesText = "最棒的是(\\S+) => $1是最棒的",
        ))
    }
}
