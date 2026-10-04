package com.tavern.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
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
import com.tavern.domain.models.Character
import com.tavern.domain.models.Chat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 会话列表：一个角色可以有多个会话（含「从某条消息开分支」出来的那些）。
 *
 * 对应 Flet 版的「会话」页：切换、新建、重命名、删除。
 */
@Composable
fun SessionDialog(
    character: Character,
    currentChatId: String?,
    onDismiss: () -> Unit,
    onOpen: (Chat) -> Unit,
    onChanged: () -> Unit,
) {
    val context = LocalContext.current
    var pendingDelete by remember { mutableStateOf<(() -> Unit)?>(null) }
    var pendingWhat by remember { mutableStateOf("") }
    ConfirmDeleteDialog(pendingDelete, pendingWhat) { pendingDelete = null }
    var chats by remember { mutableStateOf<List<Chat>>(emptyList()) }
    var counts by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var renaming by remember { mutableStateOf<Chat?>(null) }
    var tick by remember { mutableStateOf(0) }
    var query by remember { mutableStateOf("") }

    LaunchedEffect(character.id, tick) {
        bg({
            val store = store0(context)
            val list = store.listChats(characterId = character.id)
            list to list.associate { it.id to store.countMessages(it.id) }
        }) { pair ->
            chats = pair.first
            counts = pair.second
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${character.name} 的会话") },
        text = {
            Column {
                if (chats.isEmpty()) {
                    Text("还没有会话")
                } else {
                    OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("搜索会话（按标题）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            val keyword = query.trim()
            val shown = if (keyword.isEmpty()) {
                chats
            } else {
                chats.filter { it.title.contains(keyword, ignoreCase = true) }
            }
            if (keyword.isNotEmpty() && shown.isEmpty()) {
                Text(
                    "没有匹配的会话",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            LazyColumn(
                        modifier = Modifier.heightIn(max = 320.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(shown, key = { it.id }) { item ->
                            Card(
                                onClick = { onOpen(item) },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            (if (item.id == currentChatId) "● " else "") +
                                                item.title.ifBlank { "未命名会话" },
                                            style = MaterialTheme.typography.bodyMedium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            "${counts[item.id] ?: 0} 条 · " + formatTime(item.updatedAt),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    TextButton(onClick = { renaming = item }) { Text("改名") }
                                    TextButton(onClick = {
                                        pendingWhat = "删除会话「${item.title}」？它的全部消息会一起删除。"
                                        pendingDelete = {
                                            bg({ store0(context).deleteChat(item.id) }) {
                                                tick++
                                                onChanged()
                                            }
                                        }
                                    }) { Text("删除") }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                bg({ store0(context).createChat(character.id) }) { created ->
                    tick++
                    onOpen(created)
                }
            }) { Text("新建会话") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("返回") } },
    )

    renaming?.let { target ->
        var draft by remember(target.id) { mutableStateOf(target.title) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("改会话名") },
            text = {
                OutlinedTextField(value = draft, onValueChange = { draft = it })
            },
            confirmButton = {
                TextButton(onClick = {
                    val name = draft.trim()
                    renaming = null
                    bg({ store0(context).renameChat(target.id, name) }) {
                        tick++
                        onChanged()
                    }
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("取消") } },
        )
    }
}

private fun formatTime(seconds: Double): String {
    if (seconds <= 0) return ""
    val formatter = SimpleDateFormat("MM-dd HH:mm", Locale.ROOT)
    return formatter.format(Date((seconds * 1000).toLong()))
}

/** 统一的删除确认弹窗（防误触）。 */
@Composable
private fun ConfirmDeleteDialog(pending: (() -> Unit)?, what: String, onClear: () -> Unit) {
    if (pending == null) return
    AlertDialog(
        onDismissRequest = onClear,
        title = { Text("确认删除") },
        text = { Text(what) },
        confirmButton = {
            TextButton(onClick = { pending(); onClear() }) {
                Text("删除", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onClear) { Text("取消") } },
    )
}
