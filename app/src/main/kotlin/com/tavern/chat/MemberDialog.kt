package com.tavern.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.tavern.domain.models.Character
import com.tavern.domain.models.Group

/**
 * 群聊成员与禁言。
 *
 * 规则与 Flet 版一致：**解禁谁，谁就出场**；如果 TA 在这个会话里还没说过话，
 * 会把 TA 的开场白放进来（`_say_greeting` 的语义），避免反复解禁刷重复开场白。
 */
@Composable
fun MemberDialog(
    group: Group,
    members: List<Character>,
    candidates: List<Character> = emptyList(),
    chatId: String?,
    onDismiss: () -> Unit,
    onGroupChanged: (Group) -> Unit,
) {
    val context = LocalContext.current
    var notice by remember { mutableStateOf("") }

    fun toggle(member: Character, muted: Boolean) {
        val updated = group.copy(
            muted = if (muted) group.muted + member.id else group.muted - member.id,
        )
        onGroupChanged(updated)
        bg({
            val store = store0(context)
            var saved = store.upsertGroup(updated)
            var message = if (muted) "${member.name} 已禁言" else "${member.name} 已解除禁言"
            if (!muted && chatId != null) {
                // 还没说过话才补开场白
                val spoke = store.listMessages(chatId).any { it.speaker == member.id }
                if (!spoke && member.greeting.isNotEmpty()) {
                    store.addMessage(chatId, "assistant", member.greeting, speaker = member.id)
                    message = "${member.name} 出场了（带出开场白）"
                }
            }
            saved to message
        }) { pair ->
            notice = pair.second
        }
    }

    var adding by remember { mutableStateOf(false) }
    val addable = candidates.filter { it.id !in group.members }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("成员与禁言") },
        text = {
            Column {
                Text(
                    "☑ = 会说话，☐ = 禁言。解禁谁谁出场（没说过话会带出开场白）。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (notice.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text(notice, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
                Spacer(Modifier.height(6.dp))
                // 按 group.members 的顺序显示：这个顺序就是「依次都回」的发言顺序
                val ordered = group.members.mapNotNull { id -> members.firstOrNull { it.id == id } }
                LazyColumn(modifier = Modifier.heightIn(max = 300.dp)) {
                    items(ordered, key = { it.id }) { member ->
                        val muted = group.isMuted(member.id)
                        Row(
                            modifier = Modifier.padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TextButton(onClick = { toggle(member, !muted) }) {
                                Text(if (muted) "☐ ${member.name}" else "☑ ${member.name}")
                            }
                            if (muted) {
                                Text(
                                    "（已禁言）",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Spacer(Modifier.weight(1f))
                            // 调换发言顺序：直接影响群聊「依次都回」的先后
                            val index = group.members.indexOf(member.id)
                            fun move(delta: Int) {
                                val ids = group.members.toMutableList()
                                val to = index + delta
                                if (index < 0 || to < 0 || to >= ids.size) return
                                val tmp = ids[index]
                                ids[index] = ids[to]
                                ids[to] = tmp
                                val updated = group.copy(members = ids)
                                onGroupChanged(updated)
                                bg({ store0(context).upsertGroup(updated) }) {
                                    notice = "发言顺序已调整为：第 ${to + 1} 位"
                                }
                            }
                            TextButton(onClick = { move(-1) }, enabled = index > 0) { Text("↑") }
                            TextButton(
                                onClick = { move(1) },
                                enabled = index in 0 until (group.members.size - 1),
                            ) { Text("↓") }
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Spacer(Modifier.height(6.dp))
                TextButton(onClick = { adding = !adding }) {
                    Text(if (adding) "收起「添加成员」" else "＋ 添加成员（${addable.size} 位可选）")
                }
                if (adding) {
                    if (addable.isEmpty()) {
                        Text(
                            "所有角色都已在群里了",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    LazyColumn(modifier = Modifier.heightIn(max = 180.dp)) {
                        items(addable, key = { it.id }) { character ->
                            TextButton(onClick = {
                                val updated = group.copy(members = group.members + character.id)
                                onGroupChanged(updated)
                                bg({ store0(context).upsertGroup(updated) }) {
                                    notice = "${character.name} 已加入群聊"
                                }
                            }) { Text("＋ ${character.name}") }
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Row {
                    TextButton(onClick = {
                        onGroupChanged(group.copy(muted = emptyList()))
                        bg({ store0(context).upsertGroup(group.copy(muted = emptyList())) }) {
                            notice = "已全部解除禁言"
                        }
                    }) { Text("全部出场") }
                    TextButton(onClick = {
                        onGroupChanged(group.copy(muted = group.members))
                        bg({ store0(context).upsertGroup(group.copy(muted = group.members)) }) {
                            notice = "已全部禁言"
                        }
                    }) { Text("全部禁言") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("好") } },
    )
}
