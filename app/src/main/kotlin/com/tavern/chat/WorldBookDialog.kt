package com.tavern.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tavern.domain.models.WorldBook
import com.tavern.domain.models.WorldEntry

/**
 * 聊天内的世界书（按角色 / 按群聊导入）。
 *
 * - **已导入**（默认展开）：☑ 生效 / ☐ 停用；**点书名可展开条目，列出每条的关键词**
 *   （世界书多了记不住名词，这里直接枚举，不用再点进去）
 * - **未导入**（默认收起，点开才加载）：搜索 + 一键导入
 */
@Composable
fun WorldBookDialog(
    ownerId: String,
    ownerLabel: String,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var tick by remember { mutableStateOf(0) }
    var imported by remember { mutableStateOf<List<String>>(emptyList()) }
    var enabled by remember { mutableStateOf<List<String>>(emptyList()) }
    var library by remember { mutableStateOf<List<WorldBook>>(emptyList()) }
    var counts by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var showAvailable by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf("") }
    // 展开某本书的条目（只列关键词，不再往下钻）
    var openBook by remember { mutableStateOf("") }
    var openEntries by remember { mutableStateOf<List<WorldEntry>>(emptyList()) }

    val key = "books_for_$ownerId"
    val onKey = "books_on_$ownerId"

    LaunchedEffect(tick) {
        bg({
            val store = store0(context)
            val ids = store.readKv(key).orEmpty().split("\n").filter { it.isNotBlank() }
            val on = store.readKv(onKey).orEmpty().split("\n").filter { it.isNotBlank() }
            val all = store.allWorldBooks()
            val countMap = all.associate { it.id to store.countWorldEntriesInBook(it.id) }
            Triple(ids, on, all) to countMap
        }) { data ->
            imported = data.first.first
            enabled = data.first.second
            library = data.first.third
            counts = data.second
        }
    }

    val importedBooks = library.filter { it.id in imported }
    val available = library.filter { it.id !in imported }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("世界书 · $ownerLabel") },
        text = {
            Column {
                if (notice.isNotEmpty()) {
                    Text(
                        notice,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(4.dp))
                }
                Text(
                    "按「角色 / 群聊」导入；导入后还要 ☑ 才生效。点书名可看这本书里有哪些条目（关键词）。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))

                Text("▾ 已导入（${importedBooks.size}）", style = MaterialTheme.typography.labelLarge)
                if (importedBooks.isEmpty()) {
                    Text(
                        "还没有导入任何世界书，展开下面的「未导入」挑一本。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                LazyColumn(modifier = Modifier.heightIn(max = 260.dp)) {
                    items(importedBooks, key = { it.id }) { book ->
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TextButton(onClick = {
                                    val next = if (book.id in enabled) {
                                        enabled - book.id
                                    } else {
                                        enabled + book.id
                                    }
                                    enabled = next
                                    bg({ store0(context).writeKv(onKey, next.joinToString("\n")) }) {
                                        notice = if (book.id in next) {
                                            "已启用「${book.name}」"
                                        } else {
                                            "已停用「${book.name}」（书还留着）"
                                        }
                                    }
                                }) { Text(if (book.id in enabled) "☑" else "☐") }
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clickable {
                                            if (openBook == book.id) {
                                                openBook = ""
                                                openEntries = emptyList()
                                            } else {
                                                openBook = book.id
                                                bg({
                                                    store0(context).listWorldEntries(
                                                        book.characterId,
                                                        bookId = book.id,
                                                    )
                                                }) { list -> openEntries = list }
                                            }
                                        }
                                        .padding(vertical = 6.dp),
                                ) {
                                    Text(
                                        book.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        "${counts[book.id] ?: 0} 条 · " +
                                            if (book.id in enabled) "生效中" else "已停用",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                TextButton(onClick = {
                                    val next = imported - book.id
                                    val nextOn = enabled - book.id
                                    imported = next
                                    enabled = nextOn
                                    if (openBook == book.id) {
                                        openBook = ""
                                        openEntries = emptyList()
                                    }
                                    bg({
                                        val store = store0(context)
                                        store.writeKv(key, next.joinToString("\n"))
                                        store.writeKv(onKey, nextOn.joinToString("\n"))
                                    }) { notice = "已移除「${book.name}」" }
                                }) { Text("移除") }
                            }
                            if (openBook == book.id) {
                                Column(modifier = Modifier.fillMaxWidth().padding(start = 44.dp)) {
                                    if (openEntries.isEmpty()) {
                                        Text(
                                            "（这本书还没有条目）",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    for (entry in openEntries) {
                                        Text(
                                            "· ${entry.title}：" +
                                                entry.keys.joinToString("、").ifEmpty { "（常驻/无关键词）" },
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(6.dp))
                TextButton(onClick = { showAvailable = !showAvailable }) {
                    Text(
                        if (showAvailable) {
                            "▾ 未导入（${available.size}）"
                        } else {
                            "▸ 未导入（${available.size}）"
                        },
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
                if (showAvailable) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text("搜索书名") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(4.dp))
                    val keyword = query.trim()
                    val hit = if (keyword.isEmpty()) {
                        available
                    } else {
                        available.filter { it.name.contains(keyword, ignoreCase = true) }
                    }
                    if (hit.isEmpty()) {
                        Text(
                            if (available.isEmpty()) "书库里的书都已在上面" else "没有匹配的书",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    LazyColumn(modifier = Modifier.heightIn(max = 220.dp)) {
                        items(hit, key = { it.id }) { book ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "· ${book.name}",
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                TextButton(onClick = {
                                    val next = imported + book.id
                                    val nextOn = enabled + book.id
                                    imported = next
                                    enabled = nextOn
                                    bg({
                                        val store = store0(context)
                                        store.writeKv(key, next.joinToString("\n"))
                                        store.writeKv(onKey, nextOn.joinToString("\n"))
                                    }) { notice = "已导入并启用「${book.name}」" }
                                }) { Text("导入") }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("好") } },
    )
}
