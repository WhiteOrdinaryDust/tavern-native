package com.tavern.domain.prompt

import kotlin.test.Test
import kotlin.test.assertEquals

/** 对应 Flet 版 `prompt.estimate_tokens`：CJK 1 字 1 token，其余 4 字符 1 token。 */
class PromptTokensTest {

    @Test
    fun emptyIsZero() {
        assertEquals(0, Prompt.estimateTokens(null))
        assertEquals(0, Prompt.estimateTokens(""))
    }

    @Test
    fun cjkCountsOnePerChar() {
        assertEquals(2, Prompt.estimateTokens("你好"))
        assertEquals(4, Prompt.estimateTokens("一二三四"))
    }

    @Test
    fun asciiCountsFourPerToken() {
        assertEquals(1, Prompt.estimateTokens("abcd"))
        assertEquals(2, Prompt.estimateTokens("abcdefgh"))
        assertEquals(1, Prompt.estimateTokens("ab"), "不足 4 个也算 1（向上取整）")
    }

    @Test
    fun mixedTextAddsUp() {
        assertEquals(3, Prompt.estimateTokens("你好abcd"))
        assertEquals(3, Prompt.estimateTokens("你好ab"))
    }

    @Test
    fun fullWidthAndKanaAndHangulCountAsCjk() {
        assertEquals(1, Prompt.estimateTokens("Ａ"))
        assertEquals(1, Prompt.estimateTokens("あ"))
        assertEquals(1, Prompt.estimateTokens("한"))
    }
}
