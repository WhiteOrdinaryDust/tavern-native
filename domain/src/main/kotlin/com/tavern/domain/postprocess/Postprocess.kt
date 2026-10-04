package com.tavern.domain.postprocess

import com.tavern.domain.models.Character
import com.tavern.domain.models.Chat
import com.tavern.domain.models.Message

const val RULE_SEPARATOR = "=>"

/** 规则里的 `/pattern/imsx` 形式（SillyTavern 脚本导入后的样子）。 */
private val SLASH_RULE = Regex("^/(?<body>.*)/(?<flags>[a-z]*)$", RegexOption.DOT_MATCHES_ALL)

/** 头像没有图片时显示名字，字号按「每行几个字」自适应（相对头像直径的比例）。 */
private val AVATAR_FONT_RATIO = mapOf(1 to 0.42, 2 to 0.34, 3 to 0.23)
private const val AVATAR_TWO_LINE_RATIO = 0.32

private val CJK = Regex("[\u3400-\u4dbf\u4e00-\u9fff\uf900-\ufaff\u3040-\u30ff\uac00-\ud7af]")

/** 一段带样式的文本。 */
data class Span(val text: String, val italic: Boolean = false, val bold: Boolean = false)

object Postprocess {

    // ---------------------------------------------------------------- 头像文字

    /** 头像兜底文字：返回 1~2 行。 */
    fun avatarLines(name: String?): List<String> {
        val raw = (name ?: "").trim()
        val compact = raw.filterNot { it.isWhitespace() }
        if (compact.isEmpty()) return listOf("?")

        if (CJK.containsMatchIn(compact)) {
            val chars = compact.take(4)
            if (chars.length <= 3) return listOf(chars)
            return listOf(chars.substring(0, 2), chars.substring(2, 4))
        }

        val words = raw.split(" ").filter { it.isNotEmpty() }
        if (words.size >= 2) {
            return listOf((words[0][0].toString() + words[1][0]).uppercase())
        }
        return listOf(compact.take(2))
    }

    /** 按行数与每行字数给出字号比例（相对头像直径）。 */
    fun avatarFontRatio(lines: List<String>): Double {
        val rows = lines.filter { it.isNotEmpty() }
        if (rows.isEmpty()) return AVATAR_FONT_RATIO.getValue(1)
        if (rows.size >= 2) return AVATAR_TWO_LINE_RATIO
        val widest = rows.maxOf { it.length }
        return AVATAR_FONT_RATIO[widest] ?: AVATAR_FONT_RATIO.getValue(3)
    }

    // ---------------------------------------------------------------- 样式分段

    // 角色扮演惯例：*动作* 是斜体，**强调** 是粗体。
    // 用非贪婪 + DOTALL，允许动作描写跨行（模型常把动作写成多行）。
    private val EMPHASIS = Regex(
        "\\*\\*(?<bold>.+?)\\*\\*|\\*(?!\\*)(?<italic>.+?)\\*(?!\\*)",
        RegexOption.DOT_MATCHES_ALL,
    )

    /** 把回复切成带样式的片段；落单的 `*` 原样保留，换行原样保留。 */
    fun toSpans(text: String?): List<Span> {
        val source = text ?: ""
        val spans = mutableListOf<Span>()
        var position = 0
        for (match in EMPHASIS.findAll(source)) {
            val start = match.range.first
            val end = match.range.last + 1
            if (start > position) spans += Span(source.substring(position, start))
            val bold = match.groups["bold"]?.value
            if (bold != null) {
                spans += Span(bold, bold = true)
            } else {
                spans += Span(match.groups["italic"]?.value ?: "", italic = true)
            }
            position = end
        }
        if (position < source.length) spans += Span(source.substring(position))
        return spans.ifEmpty { listOf(Span(source)) }
    }

    // ---------------------------------------------------------------- 清洗

    /** 把清洗规则里的酒馆宏替换掉（这样规则能跟着角色/用户名走）。 */
    internal fun substituteMacros(value: String, charName: String, userName: String): String {
        if (value.isEmpty()) return value
        return value
            .replace("{{char}}", charName)
            .replace("{{user}}", userName)
            .replace("<BOT>", charName)
            .replace("<USER>", userName)
    }

    data class Rules(val rules: List<Pair<Regex, String>>, val errors: List<String>)

    /**
     * 解析「自定义清洗规则」：一行一条 `正则 => 替换`；省略 `=>` 表示删除匹配；`#` 开头是注释。
     */
    fun parseRules(text: String?, charName: String = "", userName: String = ""): Rules {
        val rules = mutableListOf<Pair<Regex, String>>()
        val errors = mutableListOf<String>()
        val lines = (text ?: "").split("\n")
        for ((index, raw) in lines.withIndex()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val sep = line.indexOf(RULE_SEPARATOR)
            var pattern: String
            var replacement: String
            if (sep >= 0) {
                pattern = line.substring(0, sep)
                replacement = line.substring(sep + RULE_SEPARATOR.length).trim()
            } else {
                pattern = line
                replacement = ""
            }
            pattern = pattern.trim()
            if (pattern.isEmpty()) continue
            pattern = substituteMacros(pattern, charName, userName)
            replacement = substituteMacros(replacement, charName, userName)
            try {
                rules += compileRule(pattern) to replacement
            } catch (exc: Exception) {
                errors += "第 ${index + 1} 行正则无效：${exc.message ?: "格式错误"}"
            }
        }
        return Rules(rules, errors)
    }

    /**
     * 规则文本可能是 `/pattern/imsx` 形式（SillyTavern 脚本导入后就是这种），
     * 也可能是裸正则。Flet 版对前者会把斜杠也当正则的一部分，这里按酒馆的语义正确处理。
     */
    private fun compileRule(text: String): Regex {
        val match = SLASH_RULE.matchEntire(text)
        val body = match?.groups?.get("body")?.value ?: text
        val flags = match?.groups?.get("flags")?.value ?: ""
        val options = buildSet {
            if ('i' in flags) add(RegexOption.IGNORE_CASE)
            if ('m' in flags) add(RegexOption.MULTILINE)
            if ('s' in flags) add(RegexOption.DOT_MATCHES_ALL)
            if ('x' in flags) add(RegexOption.COMMENTS)
        }
        return if (options.isEmpty()) Regex(body) else Regex(body, options)
    }

    private fun namePrefixPattern(name: String): Regex =
        Regex("^\\s*(?:\\*\\*)?\\s*" + Regex.escape(name) + "\\s*(?:\\*\\*)?\\s*[:：]\\s*")

    /** 去掉回复开头的「角色名：」（只处理开头这一处）。 */
    fun stripNamePrefix(text: String?, charName: String): String {
        val src = text ?: ""
        if (src.isEmpty()) return src
        var out = src
        for (name in listOf("{{char}}", charName.trim())) {
            if (name.isEmpty()) continue
            out = namePrefixPattern(name).replaceFirst(out, "")
        }
        return out
    }

    /** 按开关与自定义规则清洗模型回复。 */
    fun cleanReply(
        text: String?,
        charName: String,
        stripPrefix: Boolean = true,
        rulesText: String = "",
        userName: String = "User",
    ): String {
        var out = text ?: ""
        if (stripPrefix) out = stripNamePrefix(out, charName)
        val parsed = parseRules(rulesText, charName, userName)
        for ((pattern, replacement) in parsed.rules) {
            out = try {
                // JS 风格替换串（SillyTavern 的脚本用 $1）：自己展开，语义才和酒馆一致
                pattern.replace(out) { match -> expandReplacement(match, replacement) }
            } catch (exc: Exception) {
                out
            }
        }
        return out.trim()
    }

    /** JS 风格替换串：`$1`..`$9` 捕获组、`$&` 整体、`$$` 字面 $，其余原样。 */
    internal fun expandReplacement(match: MatchResult, replacement: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < replacement.length) {
            val ch = replacement[i]
            if (ch == '$' && i + 1 < replacement.length) {
                val next = replacement[i + 1]
                when {
                    next == '$' -> {
                        out.append('$'); i += 2; continue
                    }
                    next == '&' -> {
                        out.append(match.value); i += 2; continue
                    }
                    next.isDigit() -> {
                        out.append(match.groupValues.getOrNull(next - '0') ?: "")
                        i += 2; continue
                    }
                }
            }
            out.append(ch)
            i += 1
        }
        return out.toString()
    }

    // ---------------------------------------------------------------- 导出

    /** 把会话导出成 Markdown 文本（`speakerNames` 供群聊按发言人署名）。 */
    fun exportMarkdown(
        character: Character,
        chat: Chat,
        messages: List<Message>,
        includeReasoning: Boolean = false,
        userName: String = "User",
        speakerNames: Map<String, String> = emptyMap(),
    ): String {
        val title = chat.title.trim().ifEmpty { "与 ${character.name} 的会话" }
        val lines = mutableListOf("# $title", "")
        if (speakerNames.isNotEmpty()) {
            lines += "- 角色：" + speakerNames.values.joinToString("、")
        } else {
            lines += "- 角色：${character.name}"
        }
        lines += "- 用户：${userName.ifEmpty { "User" }}"
        lines += "- 消息数：${messages.size}"
        lines += ""

        for (msg in messages) {
            val speaker = when (msg.role) {
                "user" -> userName.ifEmpty { "User" }
                "assistant" -> speakerNames[msg.speaker] ?: character.name
                else -> continue
            }
            val body = msg.text.trim()
            if (body.isEmpty() && !(includeReasoning && msg.reasoning.trim().isNotEmpty())) continue
            lines += "### $speaker"
            lines += ""
            if (body.isNotEmpty()) {
                lines += body
                lines += ""
            }
            if (includeReasoning && msg.reasoning.trim().isNotEmpty()) {
                lines += "<details><summary>思考过程</summary>"
                lines += ""
                lines += msg.reasoning.trim()
                lines += ""
                lines += "</details>"
                lines += ""
            }
        }
        return lines.joinToString("\n").trimEnd() + "\n"
    }

    /** 把会话名变成安全的文件名。 */
    fun safeFilename(name: String?, fallback: String = "chat"): String {
        val cleaned = Regex("[\\\\/:*?\"<>|\\r\\n\\t]+")
            .replace((name ?: "").trim(), "_")
            .trim(' ', '.', '_')
        return cleaned.take(40).ifEmpty { fallback }
    }
}
