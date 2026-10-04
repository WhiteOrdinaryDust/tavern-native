package com.tavern.domain.markdown

/** 一个块级元素。只做模型输出里最常见的几种，够用且可预期。 */
sealed interface MdBlock {
    data class Paragraph(val text: String) : MdBlock
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Bullets(val items: List<String>) : MdBlock
    data class Numbered(val items: List<String>) : MdBlock
    data class Code(val language: String, val code: String) : MdBlock
    data class Quote(val text: String) : MdBlock
    data class Table(val header: List<String>, val rows: List<List<String>>) : MdBlock

    /** 分隔线（--- / *** / ___）。 */
    data object Rule : MdBlock
}

/** 行内样式片段。 */
data class MdSpan(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val code: Boolean = false,
    val strike: Boolean = false,
)

/**
 * 极简 Markdown：把模型回复切成块 + 行内样式。
 *
 * 刻意**不引第三方库**：口味完全可控、可单测，也不会出现"某个库不支持表格/公式，
 * 渲染出错反而更难看"的情况。支持的语法：
 *
 * - 标题 `#` ~ `######`
 * - 无序列表 `- ` / `* ` / `+ `、有序列表 `1. `
 * - 围栏代码块 ``` 与行内 `code`
 * - 引用 `> `
 * - 表格 `| a | b |`（含 `|---|` 分隔行）
 * - 行内 `**粗**`、`*斜*`
 *
 * 单换行**不会被吃掉**（这正是当初不敢直接用 Markdown 控件的原因）。
 */
object Markdown {

    private val heading = Regex("^(#{1,6})\\s+(.*)$")
    private val bullet = Regex("^[-*+]\\s+(.*)$")
    private val numbered = Regex("^\\d+[.)]\\s+(.*)$")
    private val rule = Regex("^\\s*(-{3,}|\\*{3,}|_{3,})\\s*$")
    private val tableRow = Regex("^\\s*\\|.*\\|?\\s*$")
    private val tableSeparator = Regex("^\\s*\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?\\s*$")

    fun parse(text: String?): List<MdBlock> {
        val source = (text ?: "").replace("\r\n", "\n").replace("\r", "\n")
        if (source.isBlank()) return emptyList()
        val lines = source.split("\n")
        val blocks = mutableListOf<MdBlock>()
        val paragraph = mutableListOf<String>()
        val bullets = mutableListOf<String>()
        val numberedItems = mutableListOf<String>()

        fun flushParagraph() {
            if (paragraph.isNotEmpty()) {
                blocks += MdBlock.Paragraph(paragraph.joinToString("\n"))
                paragraph.clear()
            }
        }

        fun flushBullets() {
            if (bullets.isNotEmpty()) {
                blocks += MdBlock.Bullets(bullets.toList())
                bullets.clear()
            }
        }

        fun flushNumbered() {
            if (numberedItems.isNotEmpty()) {
                blocks += MdBlock.Numbered(numberedItems.toList())
                numberedItems.clear()
            }
        }

        fun flushLists() {
            flushBullets()
            flushNumbered()
        }

        var index = 0
        while (index < lines.size) {
            val line = lines[index]
            val trimmed = line.trim()

            // 围栏代码块
            if (trimmed.startsWith("```")) {
                flushParagraph(); flushLists()
                val language = trimmed.removePrefix("```").trim()
                val body = mutableListOf<String>()
                index += 1
                while (index < lines.size && !lines[index].trim().startsWith("```")) {
                    body += lines[index]
                    index += 1
                }
                if (index < lines.size) index += 1  // 跳过收尾的 ```
                blocks += MdBlock.Code(language, body.joinToString("\n"))
                continue
            }

            // 分隔线（必须放在表格判断之前，否则 --- 会被当成普通段落）
            if (rule.matches(trimmed)) {
                flushParagraph(); flushLists()
                blocks += MdBlock.Rule
                index += 1
                continue
            }

            // 空行 = 分块
            if (trimmed.isEmpty()) {
                flushParagraph(); flushLists()
                index += 1
                continue
            }

            // 表格：本行是 |...| 且下一行是分隔行
            if (tableRow.matches(line) && index + 1 < lines.size && tableSeparator.matches(lines[index + 1])) {
                flushParagraph(); flushLists()
                val header = splitRow(line)
                val rows = mutableListOf<List<String>>()
                index += 2
                while (index < lines.size && tableRow.matches(lines[index])) {
                    rows += splitRow(lines[index])
                    index += 1
                }
                blocks += MdBlock.Table(header, rows)
                continue
            }

            val headingMatch = heading.matchEntire(trimmed)
            if (headingMatch != null) {
                flushParagraph(); flushLists()
                blocks += MdBlock.Heading(
                    headingMatch.groupValues[1].length,
                    headingMatch.groupValues[2].trim(),
                )
                index += 1
                continue
            }

            if (trimmed.startsWith("> ")) {
                flushParagraph(); flushLists()
                val quote = mutableListOf<String>()
                while (index < lines.size && lines[index].trim().startsWith("> ")) {
                    quote += lines[index].trim().removePrefix("> ")
                    index += 1
                }
                blocks += MdBlock.Quote(quote.joinToString("\n"))
                continue
            }

            val bulletMatch = bullet.matchEntire(trimmed)
            if (bulletMatch != null) {
                flushParagraph(); flushNumbered()
                bullets += bulletMatch.groupValues[1].trim()
                index += 1
                continue
            }

            val numberedMatch = numbered.matchEntire(trimmed)
            if (numberedMatch != null) {
                flushParagraph(); flushBullets()
                numberedItems += numberedMatch.groupValues[1].trim()
                index += 1
                continue
            }

            flushLists()
            paragraph += line.trimEnd()
            index += 1
        }
        flushParagraph(); flushLists()
        return blocks
    }

    private fun splitRow(line: String): List<String> {
        var body = line.trim()
        if (body.startsWith("|")) body = body.substring(1)
        if (body.endsWith("|")) body = body.dropLast(1)
        return body.split("|").map { it.trim() }
    }

    /** 行内样式：先切 `` `code` ``，再在普通片段里处理粗体/斜体。 */
    fun inline(text: String): List<MdSpan> {
        val spans = mutableListOf<MdSpan>()
        var rest = text
        while (true) {
            val open = rest.indexOf('`')
            if (open < 0) {
                spans += plainSpans(rest)
                break
            }
            val close = rest.indexOf('`', open + 1)
            if (close < 0) {
                spans += plainSpans(rest)
                break
            }
            spans += plainSpans(rest.substring(0, open))
            spans += MdSpan(rest.substring(open + 1, close), code = true)
            rest = rest.substring(close + 1)
        }
        return spans.filter { it.text.isNotEmpty() }
    }

    private val strike = Regex("~~(?<strike>.+?)~~")
    private val emphasis = Regex("\\*\\*(?<bold>.+?)\\*\\*|\\*(?!\\*)(?<italic>.+?)\\*(?!\\*)", RegexOption.DOT_MATCHES_ALL)

    private fun plainSpans(text: String): List<MdSpan> {
        if (text.isEmpty()) return emptyList()
        // 删除线优先于粗体/斜体
        val hit = strike.find(text)
        if (hit != null) {
            val out = mutableListOf<MdSpan>()
            out += plainSpans(text.substring(0, hit.range.first))
            out += MdSpan(hit.groups["strike"]?.value ?: "", strike = true)
            out += plainSpans(text.substring(hit.range.last + 1))
            return out
        }
        val spans = mutableListOf<MdSpan>()
        var position = 0
        for (match in emphasis.findAll(text)) {
            if (match.range.first > position) spans += MdSpan(text.substring(position, match.range.first))
            val bold = match.groups["bold"]?.value
            if (bold != null) {
                spans += MdSpan(bold, bold = true)
            } else {
                spans += MdSpan(match.groups["italic"]?.value ?: "", italic = true)
            }
            position = match.range.last + 1
        }
        if (position < text.length) spans += MdSpan(text.substring(position))
        return spans
    }
}
