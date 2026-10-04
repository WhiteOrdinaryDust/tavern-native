package com.tavern.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tavern.domain.markdown.Markdown
import com.tavern.domain.markdown.MdBlock
import com.tavern.domain.markdown.MdSpan
import com.tavern.domain.theme.Theme

/**
 * 把 Markdown 块渲染成 Compose 控件。
 *
 * 解析在 `:domain`（有 13 项测试），这里只负责"长什么样"：
 * 标题加粗放大、列表加项目符号、代码块等宽+底色、引用加竖线、表格按列排。
 */
@Composable
fun MarkdownBody(text: String, modifier: Modifier = Modifier) {
    val size = Theme.scaled(14, LocalAppearance.current.fontScale)
    val mono = FontFamily.Monospace
    Column(modifier = modifier) {
        for (block in Markdown.parse(text)) {
            when (block) {
                is MdBlock.Heading -> Text(
                    inlineSpans(block.text),
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontSize = (size + (4 - block.level).coerceAtLeast(0)).sp,
                        fontWeight = FontWeight.Bold,
                    ),
                    modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
                )

                is MdBlock.Paragraph -> Text(
                    inlineSpans(block.text),
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = size.sp),
                )

                is MdBlock.Bullets -> for (item in block.items) {
                    Row(modifier = Modifier.padding(vertical = 1.dp)) {
                        Text("• ", style = MaterialTheme.typography.bodyMedium.copy(fontSize = size.sp))
                        Text(
                            inlineSpans(item),
                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = size.sp),
                        )
                    }
                }

                is MdBlock.Numbered -> for ((index, item) in block.items.withIndex()) {
                    Row(modifier = Modifier.padding(vertical = 1.dp)) {
                        Text(
                            "${index + 1}. ",
                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = size.sp),
                        )
                        Text(
                            inlineSpans(item),
                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = size.sp),
                        )
                    }
                }

                is MdBlock.Quote -> Row(modifier = Modifier.padding(vertical = 2.dp)) {
                    Text("▎", color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(4.dp))
                    Text(
                        inlineSpans(block.text),
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontSize = size.sp,
                            fontStyle = FontStyle.Italic,
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                is MdBlock.Code -> Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                        .padding(8.dp),
                ) {
                    if (block.language.isNotEmpty()) {
                        Text(
                            block.language,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(2.dp))
                    }
                    Text(
                        block.code,
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = (size - 2).sp, fontFamily = mono),
                    )
                }

                MdBlock.Rule -> androidx.compose.material3.HorizontalDivider(
                    modifier = Modifier.padding(vertical = 6.dp),
                )

                is MdBlock.Table -> Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                        .padding(8.dp),
                ) {
                    Row(modifier = Modifier.fillMaxWidth()) {
                        for (cell in block.header) {
                            Text(
                                cell,
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontSize = (size - 1).sp,
                                    fontWeight = FontWeight.Bold,
                                ),
                            )
                        }
                    }
                    Spacer(Modifier.height(2.dp))
                    for (row in block.rows) {
                        Row(modifier = Modifier.fillMaxWidth()) {
                            for (cell in row) {
                                Text(
                                    cell,
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodySmall.copy(fontSize = (size - 1).sp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 把行内片段拼成带样式的文本。 */
private fun inlineSpans(text: String): AnnotatedString = buildAnnotatedString {
    for (span in Markdown.inline(text)) appendSpan(span)
}

private fun AnnotatedString.Builder.appendSpan(span: MdSpan) {
    val style = when {
        span.code -> SpanStyle(fontFamily = FontFamily.Monospace)
        span.bold -> SpanStyle(fontWeight = FontWeight.Bold)
        span.strike -> SpanStyle(textDecoration = TextDecoration.LineThrough)
        span.italic -> SpanStyle(fontStyle = FontStyle.Italic)
        else -> null
    }
    if (style == null) append(span.text) else withStyle(style) { append(span.text) }
}
