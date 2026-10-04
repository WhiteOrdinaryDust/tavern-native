package com.tavern.domain.rhythm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 与 Flet 版 tests/test_core.py 的 [节奏控制] 一节逐条对应。 */
class RhythmTest {

    @Test
    fun blockHasBothLines() {
        val block = Rhythm.buildRhythmBlock("instant", "strict")
        assertTrue(block.startsWith("【本轮节奏】"), block)
        assertTrue(block.contains("时间粒度：瞬档"), block)
        assertTrue(block.contains("动作权限：严格回合"), block)
        assertTrue(block.contains("不能超过玩家输入所暗示的范围"), block)
        assertTrue(block.contains("停下"), "要明确要求写完就停")
    }

    @Test
    fun disabledGivesEmpty() {
        assertEquals("", Rhythm.buildRhythmBlock("instant", "strict", enabled = false))
    }

    @Test
    fun normalizeFallsBackToDefaults() {
        assertEquals(Rhythm.DEFAULT_TIME, Rhythm.normalizeTime("nonsense"))
        assertEquals(Rhythm.DEFAULT_AUTHORITY, Rhythm.normalizeAuthority(null))
        assertEquals("scene", Rhythm.normalizeTime("scene"))
        assertEquals("director", Rhythm.normalizeAuthority("director"))
    }

    @Test
    fun labelsCoverAllGears() {
        for ((key, label) in Rhythm.TIME_GEARS) assertEquals(label, Rhythm.timeLabel(key))
        for ((key, label) in Rhythm.AUTHORITIES) assertEquals(label, Rhythm.authorityLabel(key))
        assertEquals("场", Rhythm.timeLabel("没这个档"))
        assertEquals("严格回合", Rhythm.authorityLabel("没这个档"))
    }

    @Test
    fun everyGearHasRuleAndHint() {
        for ((key, _) in Rhythm.TIME_GEARS) {
            assertTrue(Rhythm.TIME_HINTS.containsKey(key), key)
            assertTrue(Rhythm.buildRhythmBlock(key, "strict").isNotEmpty(), key)
        }
        for ((key, _) in Rhythm.AUTHORITIES) {
            assertTrue(Rhythm.AUTHORITY_HINTS.containsKey(key), key)
            assertTrue(Rhythm.buildRhythmBlock("scene", key).isNotEmpty(), key)
        }
    }
}
