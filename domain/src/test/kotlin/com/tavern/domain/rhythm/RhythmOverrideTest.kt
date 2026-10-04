package com.tavern.domain.rhythm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 七个按钮（时间粒度 4 + 动作权限 3）的提示词覆盖，以及输出字数规则。 */
class RhythmOverrideTest {

    @Test
    fun keysCoverSevenButtons() {
        val keys = Rhythm.overrideKeys()
        assertEquals(7, keys.size)
        assertEquals(4, keys.count { it.first.startsWith("time:") })
        assertEquals(3, keys.count { it.first.startsWith("authority:") })
    }

    @Test
    fun defaultTextMatchesBuiltinRules() {
        // 界面里输入框的初值 = 内置文案，用户不改就保持原样
        assertTrue(Rhythm.defaultText("time:scene").isNotEmpty())
        assertTrue(Rhythm.defaultText("authority:strict").isNotEmpty())
        // 未知档位会归一化到默认档（这是有意的：配置写坏也不该丢规则）
        assertEquals(
            Rhythm.defaultText("time:${Rhythm.DEFAULT_TIME}"),
            Rhythm.defaultText("time:不存在的档位"),
        )
    }

    @Test
    fun overrideReplacesBuiltinText() {
        val block = Rhythm.buildRhythmBlock(
            "scene",
            "strict",
            overrides = mapOf("time:scene" to "每次只写一小段", "authority:strict" to "只许写玩家做过的事"),
        )
        assertTrue(block.contains("每次只写一小段"), block)
        assertTrue(block.contains("只许写玩家做过的事"), block)
        assertTrue(block.contains("【本轮节奏】"), block)
    }

    @Test
    fun blankOverrideFallsBack() {
        val block = Rhythm.buildRhythmBlock("scene", "strict", overrides = mapOf("time:scene" to "   "))
        assertTrue(block.contains(Rhythm.defaultText("time:scene")), block)
    }

    @Test
    fun disabledStillReturnsEmpty() {
        assertEquals("", Rhythm.buildRhythmBlock("scene", "strict", enabled = false))
    }

    @Test
    fun jsonRoundTripKeepsOnlyFilled() {
        val json = Rhythm.overridesToJson(
            mapOf("time:scene" to "短一点", "time:day" to "", "authority:loose" to "随意"),
        )
        val back = Rhythm.parseOverrides(json)
        assertEquals("短一点", back["time:scene"])
        assertEquals("随意", back["authority:loose"])
        assertFalse(back.containsKey("time:day"))
        assertEquals(emptyMap(), Rhythm.parseOverrides("{}"))
        assertEquals(emptyMap(), Rhythm.parseOverrides("这不是 JSON"))
        assertEquals(emptyMap(), Rhythm.parseOverrides(null))
    }

    @Test
    fun lengthRuleCombinations() {
        assertEquals("", Rhythm.lengthRule(0, 0))
        assertEquals("回复长度不超过 300 字。", Rhythm.lengthRule(0, 300))
        assertEquals("回复长度不少于 200 字。", Rhythm.lengthRule(200, 0))
        assertEquals("回复长度控制在 200–400 字之间。", Rhythm.lengthRule(200, 400))
        assertEquals("回复长度控制在 400 字左右。", Rhythm.lengthRule(400, 400))
        // 写反了也不要给出荒谬规则
        assertEquals("回复长度不少于 300 字、不超过 500 字。", Rhythm.lengthRule(500, 300))
    }
}
