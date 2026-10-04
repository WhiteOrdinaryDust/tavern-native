package com.tavern.domain.prompt

import com.tavern.domain.models.ApiSettings
import com.tavern.domain.models.Character
import com.tavern.domain.models.Message
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 对应 Flet 版 `tavern/prompt.py` 与 tests/test_core.py 的提示词部分。 */
class PromptTest {

    private fun history(vararg pairs: Pair<String, String>): List<Message> =
        pairs.mapIndexed { index, (role, text) ->
            Message(id = "m$index", role = role, content = text)
        }

    @Test
    fun substituteReplacesTavernMacros() {
        assertEquals("林月对阿伟说", Prompt.substitute("{{char}}对{{user}}说", "林月", "阿伟"))
        assertEquals("林月阿伟", Prompt.substitute("<BOT><USER>", "林月", "阿伟"))
        assertEquals("", Prompt.substitute(null, "林月", "阿伟"))
    }

    @Test
    fun parseMesExampleGroupsBySpeaker() {
        val mes = """
            {{user}}: 在吗
            {{char}}: 在的
            我很好
            <START>
            {{user}}: 再来一句
        """.trimIndent()
        val parsed = Prompt.parseMesExample(mes, "林月", "阿伟")
        assertEquals(3, parsed.size)
        assertEquals("user" to "在吗", parsed[0]["role"] to parsed[0]["content"])
        assertEquals("assistant", parsed[1]["role"])
        assertEquals("在的\n我很好", parsed[1]["content"], "同一人的续行要接上")
        assertEquals("user" to "再来一句", parsed[2]["role"] to parsed[2]["content"])
    }

    @Test
    fun parseMesExampleHandlesSeparatorsAndMacros() {
        val mes = "{{user}}：你好\n---\n{{char}}：你好，{{user}}\n====\n\n"
        val parsed = Prompt.parseMesExample(mes, "林月", "阿伟")
        assertEquals(2, parsed.size)
        assertEquals("你好", parsed[0]["content"])
        assertEquals("你好，阿伟", parsed[1]["content"], "宏要展开")
        assertEquals(emptyList(), Prompt.parseMesExample("", "林月", "阿伟"))
        assertEquals(emptyList(), Prompt.parseMesExample(null, "林月", "阿伟"))
        // 大小写与中文冒号都认
        assertEquals("user", Prompt.parseMesExample("{{USER}}：嗨", "林月", "阿伟")[0]["role"])
    }

    @Test
    fun systemPromptUsesDefaultThenCustom() {
        val settings = ApiSettings(userName = "阿伟")
        val plain = Prompt.buildSystemPrompt(Character(name = "林月"), settings, "阿伟")
        assertTrue(plain.contains("沉浸式角色扮演"), plain)
        assertTrue(plain.contains("以 林月 的身份回应"), plain)
        assertTrue(plain.contains("你扮演 林月，正在与 阿伟 对话。只输出 林月 的回复内容。"), plain)

        val custom = Prompt.buildSystemPrompt(
            Character(name = "林月", systemPrompt = "你是 {{char}}，说话很冷淡"),
            settings,
            "阿伟",
        )
        assertTrue(custom.contains("你是 林月，说话很冷淡"), custom)
        assertFalse(custom.contains("沉浸式角色扮演"), "自定义了就不该再塞默认的")
    }

    @Test
    fun systemPromptCollectsCardFields() {
        val character = Character(
            name = "林月",
            description = "酒馆老板娘",
            personality = "温柔",
            scenario = "雨夜",
            postHistoryInstructions = "记得 {{user}} 的名字",
        )
        val prompt = Prompt.buildSystemPrompt(character, ApiSettings(userPersona = "旅行者"), "阿伟", summary = "你们见过")
        assertTrue(prompt.contains("角色设定：\n酒馆老板娘\n性格特点：温柔\n当前场景：雨夜"), prompt)
        assertTrue(prompt.contains("阿伟 的人设（由玩家扮演）：\n旅行者"), prompt)
        assertTrue(prompt.contains("目前为止的故事（前情提要"), prompt)
        assertTrue(prompt.contains("你们见过"), prompt)
    }

    @Test
    fun systemPromptOrdersWebThenRhythmAtEnd() {
        val prompt = Prompt.buildSystemPrompt(
            Character(name = "林月"),
            ApiSettings(systemExtra = "额外要求"),
            "阿伟",
            rhythm = "【本轮节奏】",
            webContext = "【联网检索结果】",
        )
        val extraIndex = prompt.indexOf("额外要求")
        val webIndex = prompt.indexOf("【联网检索结果】")
        val rhythmIndex = prompt.indexOf("【本轮节奏】")
        assertTrue(extraIndex in 0 until webIndex, "补充要求 → 资料")
        assertTrue(webIndex < rhythmIndex, "资料是事实，节奏是怎么写：节奏放最后")
        assertTrue(prompt.endsWith("【本轮节奏】"), prompt)
    }

    @Test
    fun buildMessagesShape() {
        val character = Character(name = "林月", mesExample = "{{char}}：你好")
        val messages = Prompt.buildMessages(
            character,
            ApiSettings(userName = "阿伟"),
            history("user" to "在吗", "assistant" to "在的"),
            userInput = "今天吃什么",
        )
        assertEquals("system", messages[0]["role"])
        assertEquals("assistant", messages[1]["role"], "示例对话紧跟 system")
        assertEquals("你好", messages[1]["content"])
        assertEquals("user" to "在吗", messages[2]["role"] to messages[2]["content"])
        assertEquals("今天吃什么", messages.last()["content"], "最后一条是本次输入")
    }

    @Test
    fun buildMessagesDropsOldestWhenOverBudget() {
        val character = Character(name = "林月")
        val big = "字".repeat(600)
        val messages = Prompt.buildMessages(
            character,
            ApiSettings(contextTokens = 400),
            history("user" to big, "user" to "最近这句"),
        )
        val contents = messages.map { it["content"] }
        assertTrue(contents.contains("最近这句"), contents.toString())
        assertFalse(contents.contains(big), "超预算时从最旧的历史开始丢")
    }

    @Test
    fun buildMessagesKeepsShortHistoryEvenWithTinyBudget() {
        val messages = Prompt.buildMessages(
            Character(name = "林月"),
            ApiSettings(contextTokens = 1),
            history("user" to "短"),
        )
        // 预算下限 256，短历史一定留得住
        assertTrue(messages.any { it["content"] == "短" }, messages.toString())
    }

    @Test
    fun buildMessagesAppendsPostHistoryAsSystem() {
        val character = Character(name = "林月", postHistoryInstructions = "称呼 {{user}} 为老板")
        val messages = Prompt.buildMessages(character, ApiSettings(), history(), userInput = "在吗")
        val post = messages[messages.size - 2]
        assertEquals("system", post["role"])
        assertEquals("称呼 User 为老板", post["content"], "历史之后、输入之前")
        assertEquals("在吗", messages.last()["content"])
    }

    @Test
    fun groupSystemPromptNamesEveryone() {
        val speaker = Character(id = "s", name = "林月")
        val others = listOf(Character(id = "a", name = "阿伟"), Character(id = "b", name = "小满"))
        val prompt = Prompt.buildGroupSystemPrompt(speaker, others, ApiSettings(), "玩家")
        assertTrue(prompt.contains("在场的人：阿伟、小满、玩家（玩家）。"), prompt)
        assertTrue(prompt.contains("你只扮演 林月"), prompt)
        assertTrue(prompt.contains("不要替 玩家 或其他人说话"), prompt)
        assertTrue(prompt.contains("回复短一些"), prompt)
        // 群里只有自己时也要有那句话
        val solo = Prompt.buildGroupSystemPrompt(speaker, emptyList(), ApiSettings(), "玩家")
        assertTrue(solo.contains("在场的人：玩家（玩家）。"), solo)
    }

    @Test
    fun groupMessagesAttributeOtherSpeakersAsUser() {
        val speaker = Character(id = "s", name = "林月")
        val other = Character(id = "a", name = "阿伟")
        val messages = Prompt.buildGroupMessages(
            speaker,
            listOf(speaker, other),
            ApiSettings(userName = "玩家"),
            history(
                "user" to "大家好",
                "assistant" to "我先说",
                "assistant" to "我说完了",
            ).mapIndexed { index, msg ->
                if (index == 2) msg.copy(speaker = "a") else if (index == 1) msg.copy(speaker = "s") else msg
            },
            userInput = "继续",
        )
        val bodies = messages.drop(1).map { it["role"] to it["content"] }
        assertTrue(bodies.contains("assistant" to "我先说"), "自己的话才是 assistant")
        assertTrue(bodies.contains("user" to "阿伟：我说完了"), "别人的话当 user 并署名")
        assertTrue(bodies.contains("user" to "大家好"))
        assertEquals("user" to "继续", bodies.last())
    }
}
