package com.tavern.domain.group

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 「用户 / NPC」分类与"AI 扮演谁"的规则。 */
class GroupPrefsTest {

    private val a = "char_a"
    private val b = "char_b"
    private val c = "char_c"

    @Test
    fun kindsRoundTrip() {
        val kinds = mapOf(a to GroupPrefs.USER, b to GroupPrefs.NPC)
        assertEquals(kinds, GroupPrefs.parseKinds(GroupPrefs.toJson(kinds)))
    }

    @Test
    fun malformedJsonMeansAllNpc() {
        assertEquals(emptyMap(), GroupPrefs.parseKinds("不是 json"))
        assertEquals(emptyMap(), GroupPrefs.parseKinds(""))
        assertEquals(emptyMap(), GroupPrefs.parseKinds(null))
        assertEquals(GroupPrefs.NPC, GroupPrefs.kindOf(emptyMap(), a))
    }

    @Test
    fun userKindIsNotSpokenByAi() {
        val members = listOf(a, b, c)
        val kinds = mapOf(a to GroupPrefs.USER)
        // a 是"用户"栏 → AI 不演他；b、c 是 NPC → 可演
        assertEquals(listOf(b, c), GroupPrefs.aiSpeakers(members, kinds, emptyList(), playingAs = null))
    }

    @Test
    fun playingAsIsNeverSpokenByAi() {
        val members = listOf(a, b, c)
        val kinds = emptyMap<String, String>()
        // 即使 b 是 NPC，只要我正在演他，AI 也不能再演他（否则自己回自己）
        assertEquals(listOf(a, c), GroupPrefs.aiSpeakers(members, kinds, emptyList(), playingAs = b))
    }

    @Test
    fun mutedNpcIsSkipped() {
        val members = listOf(a, b, c)
        assertEquals(listOf(a, c), GroupPrefs.aiSpeakers(members, emptyMap(), listOf(b), playingAs = null))
    }

    @Test
    fun movingIntoNpcMakesHimSpeakableAndMutedMenuTracksNpc() {
        val members = listOf(a, b)
        var kinds = mapOf(a to GroupPrefs.USER, b to GroupPrefs.NPC)
        assertEquals(listOf(b), GroupPrefs.muteMenuMembers(members, kinds))
        // 把 a 移进 NPC → 禁言菜单里立刻出现他，AI 也可以演他
        kinds = GroupPrefs.withKind(kinds, a, GroupPrefs.NPC)
        assertEquals(listOf(a, b), GroupPrefs.muteMenuMembers(members, kinds))
        assertEquals(listOf(a, b), GroupPrefs.aiSpeakers(members, kinds, emptyList(), playingAs = null))
    }

    @Test
    fun playingAsMustBeChosenAndBeInGroup() {
        val members = listOf(a, b)
        assertTrue(GroupPrefs.needsPlayAsChoice(null, members), "没有记录 → 必须选")
        assertTrue(GroupPrefs.needsPlayAsChoice("", members), "空串 → 必须选")
        assertTrue(GroupPrefs.needsPlayAsChoice(c, members), "记录的人不在群里 → 必须重选")
        assertFalse(GroupPrefs.needsPlayAsChoice(a, members), "已在群里 → 不用再问")
    }

    @Test
    fun inKindKeepsGroupOrder() {
        val members = listOf(c, a, b)
        val kinds = mapOf(a to GroupPrefs.USER, c to GroupPrefs.USER)
        assertEquals(listOf(c, a), GroupPrefs.inKind(members, kinds, GroupPrefs.USER))
        assertEquals(listOf(b), GroupPrefs.inKind(members, kinds, GroupPrefs.NPC))
    }
}
