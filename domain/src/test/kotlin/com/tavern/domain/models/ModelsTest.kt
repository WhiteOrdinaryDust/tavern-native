package com.tavern.domain.models

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 对应 Flet 版 `tavern/models.py` 的行为。 */
class ModelsTest {

    @Test
    fun newIdIsTwelveHexChars() {
        val id = newId()
        assertEquals(12, id.length, id)
        assertTrue(id.all { it in "0123456789abcdef" }, id)
        assertTrue(newId() != newId())
    }

    @Test
    fun characterGreetingIsTrimmed() {
        assertEquals("你好", Character(firstMes = "  你好\n").greeting)
        assertEquals("", Character(firstMes = "   ").greeting)
        assertEquals("未命名角色", Character().name)
    }

    @Test
    fun messageTextFollowsSelectedVariant() {
        val msg = Message(role = "assistant", content = "一")
        assertEquals("一", msg.text)
        assertEquals(1, msg.variantCount)

        msg.addVariant("二")
        assertEquals(2, msg.variantCount)
        assertEquals(1, msg.variantIndex)
        assertEquals("二", msg.text)
        assertEquals("二", msg.content, "content 要跟着当前候选走")

        assertTrue(msg.swipe(-1))
        assertEquals(0, msg.variantIndex)
        assertEquals("一", msg.text)
        // 循环：再往前一格回到最后一条
        assertTrue(msg.swipe(-1))
        assertEquals(1, msg.variantIndex)

        msg.replaceCurrent("二改")
        assertEquals("二改", msg.text)
        assertEquals("二改", msg.content)
    }

    @Test
    fun singleVariantCannotSwipe() {
        val msg = Message(content = "只有一条")
        assertFalse(msg.swipe(1))
    }

    @Test
    fun variantIndexIsClamped() {
        val msg = Message(content = "x", variants = mutableListOf("a", "b"), variantIndex = 99)
        msg.ensureVariants()
        assertEquals(1, msg.variantIndex)
        assertEquals("b", msg.content)
    }

    @Test
    fun worldEntryTitlesAndTriggers() {
        assertEquals("未命名条目", WorldEntry().title)
        assertEquals("匕首", WorldEntry(keys = listOf("匕首", "短刀")).title)
        assertEquals("线索", WorldEntry(comment = " 线索 ").title)
        assertEquals("常驻", WorldEntry(constant = true).triggerSummary)
        assertEquals("无关键词（不会触发）", WorldEntry(keys = emptyList()).triggerSummary)
        assertEquals("匕首、短刀 + 过滤 1 个", WorldEntry(keys = listOf("匕首", "短刀"), keySecondary = listOf("x")).triggerSummary)
    }

    @Test
    fun groupMuteSemantics() {
        val group = Group(name = "群", members = listOf("a", "b", "c"), muted = listOf("b"))
        assertEquals(listOf("a", "c"), group.activeMembers())
        assertTrue(group.isMember("a"))
        assertTrue(group.isMuted("b"))
        assertFalse(group.isMuted("a"))
        assertEquals("群", group.title)
        assertEquals("新群聊", Group(name = "  ").title)
    }

    @Test
    fun folderAndBookLabels() {
        val folder = Folder(name = "  ")
        assertEquals("新建文件夹", folder.title)
        assertTrue(Folder().isRoot)
        assertFalse(Folder(parentId = "p").isRoot)

        assertEquals("全局", WorldBook().scopeLabel)
        assertEquals("本角色", WorldBook(characterId = "c1").scopeLabel)
        assertEquals("未命名世界书", WorldBook(name = " ").title)
    }

    @Test
    fun apiSettingsConfigured() {
        assertTrue(ApiSettings(baseUrl = "http://x/v1", model = "m").configured)
        assertFalse(ApiSettings(baseUrl = "  ", model = "m").configured)
        assertFalse(ApiSettings(baseUrl = "http://x/v1", model = "").configured)
        // 与 Flet 版默认值一致
        val s = ApiSettings()
        assertEquals(6000, s.contextTokens)
        assertEquals(4096, s.maxTokens)
        assertEquals(40, s.summaryInterval)
        assertEquals(1200, s.worldTokenBudget)
    }

    @Test
    fun messageToOpenAiUsesCurrentText() {
        val msg = Message(role = "assistant", content = "一")
        msg.addVariant("二")
        assertEquals(mapOf("role" to "assistant", "content" to "二"), msg.toOpenAi())
    }
}
