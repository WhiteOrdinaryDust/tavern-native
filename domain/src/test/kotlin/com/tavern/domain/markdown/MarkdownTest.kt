package com.tavern.domain.markdown

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 极简 Markdown 的解析行为（渲染层直接用这些块）。 */
class MarkdownTest {

    @Test
    fun emptyInput() {
        assertEquals(emptyList(), Markdown.parse(""))
        assertEquals(emptyList(), Markdown.parse(null))
        assertEquals(emptyList(), Markdown.parse("   \n  \n"))
    }

    @Test
    fun paragraphsKeepSingleNewlines() {
        // 这是刻意保留的行为：单换行不能被吃掉
        val blocks = Markdown.parse("第一行\n第二行\n\n第二段")
        assertEquals(2, blocks.size)
        assertEquals(MdBlock.Paragraph("第一行\n第二行"), blocks[0])
        assertEquals(MdBlock.Paragraph("第二段"), blocks[1])
    }

    @Test
    fun headings() {
        val blocks = Markdown.parse("# 标题一\n### 标题三\n普通文字")
        assertEquals(MdBlock.Heading(1, "标题一"), blocks[0])
        assertEquals(MdBlock.Heading(3, "标题三"), blocks[1])
        assertEquals(MdBlock.Paragraph("普通文字"), blocks[2])
    }

    @Test
    fun bulletsAreGrouped() {
        val blocks = Markdown.parse("- 甲\n- 乙\n* 丙")
        assertEquals(1, blocks.size)
        assertEquals(MdBlock.Bullets(listOf("甲", "乙", "丙")), blocks[0])
    }

    @Test
    fun numberedAreGroupedSeparately() {
        val blocks = Markdown.parse("1. 甲\n2. 乙\n\n- 丙")
        assertEquals(MdBlock.Numbered(listOf("甲", "乙")), blocks[0])
        assertEquals(MdBlock.Bullets(listOf("丙")), blocks[1])
    }

    @Test
    fun codeFence() {
        val blocks = Markdown.parse("说明：\n```kotlin\nval a = 1\nval b = 2\n```\n后面的话")
        assertEquals(MdBlock.Paragraph("说明："), blocks[0])
        assertEquals(MdBlock.Code("kotlin", "val a = 1\nval b = 2"), blocks[1])
        assertEquals(MdBlock.Paragraph("后面的话"), blocks[2])
    }

    @Test
    fun unterminatedFenceStillProducesCode() {
        val blocks = Markdown.parse("```\n只有开头")
        assertEquals(MdBlock.Code("", "只有开头"), blocks.single())
    }

    @Test
    fun quote() {
        val blocks = Markdown.parse("> 第一句\n> 第二句\n普通")
        assertEquals(MdBlock.Quote("第一句\n第二句"), blocks[0])
        assertEquals(MdBlock.Paragraph("普通"), blocks[1])
    }

    @Test
    fun table() {
        val blocks = Markdown.parse(
            "| 名字 | 年龄 |\n| --- | ---: |\n| 林月 | 24 |\n| 阿伟 | 30 |",
        )
        val table = blocks.single() as MdBlock.Table
        assertEquals(listOf("名字", "年龄"), table.header)
        assertEquals(listOf(listOf("林月", "24"), listOf("阿伟", "30")), table.rows)
    }

    @Test
    fun pipeWithoutSeparatorIsNotATable() {
        val blocks = Markdown.parse("| 这不是表格 |")
        assertTrue(blocks.single() is MdBlock.Paragraph, blocks.toString())
    }

    @Test
    fun inlineBoldItalicAndCode() {
        assertEquals(
            listOf(
                MdSpan("她笑了", italic = true),
                MdSpan(" 说："),
                MdSpan("真的吗", bold = true),
                MdSpan(" 然后 ", italic = false),
                MdSpan("code", code = true),
            ),
            Markdown.inline("*她笑了* 说：**真的吗** 然后 `code`"),
        )
    }

    @Test
    fun inlineCodeIsNotParsedForEmphasis() {
        // 代码里的星号必须原样保留
        assertEquals(listOf(MdSpan("a*b*c", code = true)), Markdown.inline("`a*b*c`"))
        assertEquals(listOf(MdSpan("落单的 * 星号")), Markdown.inline("落单的 * 星号"))
    }

    @Test
    fun mixedDocument() {
        val doc = """
            # 开场
            她抬头看你。

            - 要点一
            - 要点二

            > 旁白

            ```
            代码
            ```
        """.trimIndent()
        val kinds = Markdown.parse(doc).map { it::class.simpleName }
        assertEquals(listOf("Heading", "Paragraph", "Bullets", "Quote", "Code"), kinds)
    }
    @Test
    fun horizontalRule() {
        val blocks = Markdown.parse("上文\n\n---\n\n下文")
        assertEquals(listOf("Paragraph", "Rule", "Paragraph"), blocks.map { it::class.simpleName })
        assertEquals(MdBlock.Rule, Markdown.parse("***").single())
    }

    @Test
    fun strikethrough() {
        assertEquals(
            listOf(MdSpan("正常 "), MdSpan("废弃", strike = true), MdSpan(" 正常")),
            Markdown.inline("正常 ~~废弃~~ 正常"),
        )
        // 单个波浪线不受影响
        assertEquals(listOf(MdSpan("a~b")), Markdown.inline("a~b"))
    }
}
