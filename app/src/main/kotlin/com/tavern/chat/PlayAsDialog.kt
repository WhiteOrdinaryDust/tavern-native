package com.tavern.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.tavern.domain.group.GroupPrefs
import com.tavern.domain.models.Character
import com.tavern.domain.postprocess.Postprocess

/**
 * 「你现在扮演谁」选择器。
 *
 * 两个可折叠分组：
 *  - 用户：这些角色**不会**由 AI 扮演（默认展开，方便在自己的角色间切换）
 *  - NPC ：AI 可以扮演他们（默认折叠；需要推进剧情时再展开）
 *
 * 想让 AI 演某个用户角色 → 把他移进 NPC（同理，移回用户就"只归你"）。
 * 进来时若还没选过扮演者，这个弹窗**不可关闭** —— 必须先选定才能开始。
 */
@Composable
fun PlayAsDialog(
    candidates: List<Character>,
    kinds: Map<String, String>,
    current: Character?,
    onMove: (String, String) -> Unit,
    onPick: (Character) -> Unit,
) {
    var usersOpen by remember { mutableStateOf(true) }
    var npcsOpen by remember { mutableStateOf(false) }
    val users = candidates.filter { GroupPrefs.isUser(kinds, it.id) }
    val npcs = candidates.filterNot { GroupPrefs.isUser(kinds, it.id) }

    AlertDialog(
        // 只有在"已经选过"的情况下才允许关闭（首次必须选定）
        onDismissRequest = { if (current != null) onPick(current) },
        title = { Text(if (current == null) "先选一个你扮演的角色" else "切换我扮演的角色") },
        text = {
            Column {
                Text(
                    "「用户」栏的角色不会由 AI 扮演；想让 AI 演他，把他移进 NPC。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                    item(key = "h_user") {
                        SectionHeader(
                            title = "用户（${users.size}）",
                            open = usersOpen,
                            onClick = { usersOpen = !usersOpen },
                        )
                    }
                    if (usersOpen) {
                        if (users.isEmpty()) {
                            item(key = "u_empty") { Hint("这一栏还是空的 —— 在下面把角色移进来") }
                        }
                        items(users, key = { "u_" + it.id }) { c ->
                            PersonRow(
                                name = c.name,
                                selected = current?.id == c.id,
                                onPick = { onPick(c) },
                                actionLabel = "移入 NPC",
                                onAction = { onMove(c.id, GroupPrefs.NPC) },
                            )
                        }
                    }
                    item(key = "h_npc") {
                        SectionHeader(
                            title = "NPC（${npcs.size}）",
                            open = npcsOpen,
                            onClick = { npcsOpen = !npcsOpen },
                        )
                    }
                    if (npcsOpen) {
                        if (npcs.isEmpty()) {
                            item(key = "n_empty") { Hint("没有 NPC —— AI 就没法替你演别人了") }
                        }
                        items(npcs, key = { "n_" + it.id }) { c ->
                            PersonRow(
                                name = c.name,
                                selected = current?.id == c.id,
                                onPick = { onPick(c) },
                                actionLabel = "移入 用户",
                                onAction = { onMove(c.id, GroupPrefs.USER) },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (current != null) {
                TextButton(onClick = { onPick(current) }) { Text("关闭") }
            }
        },
    )
}

@Composable
private fun SectionHeader(title: String, open: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(if (open) "▾ " else "▸ ", style = MaterialTheme.typography.bodyMedium)
        Text(title, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun PersonRow(
    name: String,
    selected: Boolean,
    onPick: () -> Unit,
    actionLabel: String,
    onAction: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .clickable { onPick() }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(
                        if (selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    Postprocess.avatarLines(name).joinToString(""),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (selected) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                (if (selected) "● " else "") + name,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        TextButton(onClick = { onAction() }) { Text(actionLabel, style = MaterialTheme.typography.labelSmall) }
    }
}
