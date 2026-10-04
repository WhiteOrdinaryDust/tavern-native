package com.tavern.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tavern.domain.models.WorldBook
import com.tavern.domain.models.WorldEntry

/**
 * 世界书条目编辑（独立弹窗）。
 *
 * 字段对齐旧版（Flet 版）/SillyTavern 的设定模型：关键词与二级关键词、
 * 选择性、大小写、全词匹配、优先级、深度、扫描深度、概率、分组、
 * 常驻、启用、递归控制。**留空/默认 = 跟随全局设置**。
 *
 * 暂未暴露 `position` / `role` / `selectiveLogic` 三个枚举（取值语义要先跟
 * 核心层的实现逐一核对，宁可先不给，避免给你一个填了却是错的开关）。
 */
@Composable
fun WorldBookEntryDialog(
    entry: WorldEntry,
    isNew: Boolean,
    onDismiss: () -> Unit,
    onSave: (WorldEntry) -> Unit,
    onDelete: (() -> Unit)? = null,
    books: List<WorldBook> = emptyList(),
) {
    var choosingBook by remember(entry.id) { mutableStateOf(false) }
    var confirmingDelete by remember(entry.id) { mutableStateOf(false) }


    var comment by remember(entry.id) { mutableStateOf(entry.comment) }
    var keysText by remember(entry.id) { mutableStateOf(entry.keys.joinToString("\n")) }
    var secondaryText by remember(entry.id) { mutableStateOf(entry.keySecondary.joinToString("\n")) }
    var content by remember(entry.id) { mutableStateOf(entry.content) }
    var order by remember(entry.id) { mutableStateOf(entry.order.toString()) }
    var depth by remember(entry.id) { mutableStateOf(entry.depth.toString()) }
    var scanDepth by remember(entry.id) { mutableStateOf(entry.scanDepth?.toString() ?: "") }
    var group by remember(entry.id) { mutableStateOf(entry.group) }
    var groupWeight by remember(entry.id) { mutableStateOf(entry.groupWeight.toString()) }
    var probability by remember(entry.id) { mutableStateOf(entry.probability.toString()) }
    var enabled by remember(entry.id) { mutableStateOf(entry.enabled) }
    var constant by remember(entry.id) { mutableStateOf(entry.constant) }
    var selective by remember(entry.id) { mutableStateOf(entry.selective) }
    var useProbability by remember(entry.id) { mutableStateOf(entry.useProbability) }
    var excludeRecursion by remember(entry.id) { mutableStateOf(entry.excludeRecursion) }
    var preventRecursion by remember(entry.id) { mutableStateOf(entry.preventRecursion) }
    // 三态：默认（跟随全局）/ 是 / 否
    var caseSensitive by remember(entry.id) { mutableStateOf(entry.caseSensitive) }
    var wholeWords by remember(entry.id) { mutableStateOf(entry.matchWholeWords) }

    fun splitKeys(text: String): List<String> = text
        .split(",", "，", "\n", "、")
        .map { it.trim() }
        .filter { it.isNotEmpty() }

    if (choosingBook) {
        val others = books.filter { it.id != entry.bookId }
        AlertDialog(
            onDismissRequest = { choosingBook = false },
            title = { Text("复制到哪本世界书") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    if (others.isEmpty()) {
                        Text(
                            "还没有别的书可以复制过去，先去主页「世界书」新建一本。",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    for (book in others) {
                        TextButton(onClick = {
                            choosingBook = false
                            onSave(
                                entry.copy(
                                    id = com.tavern.domain.models.newId(),
                                    bookId = book.id,
                                    characterId = book.characterId,
                                    keys = splitKeys(keysText),
                                    keySecondary = splitKeys(secondaryText),
                                    content = content,
                                    order = order.trim().toIntOrNull() ?: entry.order,
                                    depth = depth.trim().toIntOrNull() ?: entry.depth,
                                    group = group.trim(),
                                    probability = probability.trim()
                                        .toIntOrNull() ?: entry.probability,
                                    enabled = enabled,
                                    constant = constant,
                                    selective = selective,
                                    useProbability = useProbability,
                                    caseSensitive = caseSensitive,
                                    matchWholeWords = wholeWords,
                                    excludeRecursion = excludeRecursion,
                                    preventRecursion = preventRecursion,
                                    comment = comment.trim().ifEmpty { entry.comment },
                                ),
                            )
                        }) { Text("📁 " + book.name) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { choosingBook = false }) { Text("取消") } },
        )
    }
    if (confirmingDelete && onDelete != null) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text("确认删除条目") },
            text = {
                Text(
                    "「" + (comment.trim().ifEmpty { entry.title }) + "」删除后无法恢复。",
                    style = MaterialTheme.typography.bodySmall,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmingDelete = false
                    onDelete()
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDelete = false }) { Text("取消") }
            },
        )
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "新增条目" else "编辑条目") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "关键词命中即注入「内容」。多个关键词用逗号或换行分隔——**任何一个命中就算命中**" +
                        "（旧版也是这个规则，之前只认一个是我界面上没写清楚）。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = comment,
                    onValueChange = { comment = it },
                    label = { Text("备注（只给你自己看）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = keysText,
                    onValueChange = { keysText = it },
                    label = { Text("关键词（多个用逗号/换行）") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = secondaryText,
                    onValueChange = { secondaryText = it },
                    label = { Text("二级关键词（可选）") },
                    modifier = Modifier.fillMaxWidth(),
                )
                SwitchRowSwitch("需要同时命中二级关键词", selective) { selective = it }
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = { Text("内容（注入的设定）") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                Row {
                    NumberBox("优先级", order) { order = it }
                    Spacer(Modifier.width(8.dp))
                    NumberBox("深度", depth) { depth = it }
                }
                Spacer(Modifier.height(6.dp))
                Row {
                    NumberBox("扫描深度（空=全局）", scanDepth) { scanDepth = it }
                    Spacer(Modifier.width(8.dp))
                    NumberBox("分组权重", groupWeight) { groupWeight = it }
                }
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = group,
                    onValueChange = { group = it },
                    label = { Text("分组（同名分组里只取权重最高的一条）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                Row {
                    NumberBox("概率 %", probability) { probability = it }
                    Spacer(Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        SwitchRowSwitch("启用概率", useProbability) { useProbability = it }
                    }
                }
                Spacer(Modifier.height(4.dp))
                SwitchRowSwitch("常驻（不看关键词始终注入）", constant) { constant = it }
                SwitchRowSwitch("启用", enabled) { enabled = it }
                SwitchRowSwitch("大小写敏感（需与关键词完全一致）", caseSensitive == true) {
                    caseSensitive = it
                }
                SwitchRowSwitch("全词匹配（避免部分命中）", wholeWords == true) { wholeWords = it }
                SwitchRowSwitch("排除自身参与递归", excludeRecursion) { excludeRecursion = it }
                SwitchRowSwitch("阻止进一步递归", preventRecursion) { preventRecursion = it }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(
                    entry.copy(
                        comment = comment.trim(),
                        keys = splitKeys(keysText),
                        keySecondary = splitKeys(secondaryText),
                        content = content,
                        order = order.trim().toIntOrNull() ?: entry.order,
                        depth = depth.trim().toIntOrNull() ?: entry.depth,
                        scanDepth = scanDepth.trim().toIntOrNull(),
                        group = group.trim(),
                        groupWeight = groupWeight.trim().toIntOrNull() ?: entry.groupWeight,
                        probability = probability.trim().toIntOrNull() ?: entry.probability,
                        enabled = enabled,
                        constant = constant,
                        selective = selective,
                        useProbability = useProbability,
                        caseSensitive = caseSensitive,
                        matchWholeWords = wholeWords,
                        excludeRecursion = excludeRecursion,
                        preventRecursion = preventRecursion,
                    ),
                )
            }) { Text("保存") }
        },
        dismissButton = {
            Row {
                if (!isNew) {
                    // 复制 = 复制到**别的书**（同书内不需要副本，重复内容各自独立）
                    TextButton(onClick = { choosingBook = true }) { Text("复制到…") }
                }
                if (!isNew && onDelete != null) {
                    // 删除要二次确认（误删无法撤销）
                    TextButton(onClick = { confirmingDelete = true }) {
                        Text("删除", color = MaterialTheme.colorScheme.error)
                    }
                }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
    )
}

@Composable
private fun SwitchRowSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.NumberBox(
    label: String,
    value: String,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.weight(1f),
    )
}
