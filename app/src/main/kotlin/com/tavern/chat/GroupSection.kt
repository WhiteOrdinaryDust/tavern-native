package com.tavern.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tavern.domain.models.Character
import com.tavern.domain.models.Group
import com.tavern.domain.util.nowSeconds

/**
 * 群聊列表 + 建群。
 *
 * 建群规则与 Flet 版一致：**新群默认全员禁言**，之后你解禁谁，谁才出场
 * （`InMemoryStore.createGroup` / `RoomStore.createGroup` 都是这么做的，也有测试）。
 */
@Composable
fun GroupSection(
    characters: List<Character>,
    onOpen: (Group) -> Unit,
    onChanged: () -> Unit,
) {
    val context = LocalContext.current
    var groups by remember { mutableStateOf<List<Group>>(emptyList()) }
    var creating by remember { mutableStateOf(false) }
    var tick by remember { mutableStateOf(0) }
    // 群聊也有文件夹（kind = "group"），与角色那棵树互不影响
    var currentFolder by remember { mutableStateOf("") }
    var groupFolders by remember { mutableStateOf<List<com.tavern.domain.models.Folder>>(emptyList()) }
    var crumb by remember { mutableStateOf("全部群聊") }
    var parentId by remember { mutableStateOf("") }
    var folderCounts by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var allGroupFolders by remember { mutableStateOf<List<com.tavern.domain.models.Folder>>(emptyList()) }
    var movingGroup by remember { mutableStateOf<com.tavern.domain.models.Group?>(null) }
    var pinned by remember { mutableStateOf<Set<String>>(emptySet()) }
    var renamingFolder by remember { mutableStateOf<com.tavern.domain.models.Folder?>(null) }
    var pendingDelete by remember { mutableStateOf<(() -> Unit)?>(null) }
    var pendingWhat by remember { mutableStateOf("") }

    ConfirmDelete(pendingDelete, pendingWhat) { pendingDelete = null }

    // 每次进入或建群后刷新
    androidx.compose.runtime.LaunchedEffect(tick, currentFolder) {
        bg({
            val store = store0(context)
            val kids = store.childrenOf(currentFolder, "group")
            val path = if (currentFolder.isEmpty()) emptyList() else store.folderPath(currentFolder)
            GroupQuad(
                kids,
                store.listGroups(currentFolder),
                if (path.isEmpty()) "全部群聊" else path.joinToString(" / ") { it.title },
                path.dropLast(1).lastOrNull()?.id ?: "",
                kids.associate { it.id to store.countInFolder(it.id, "group") },
            )
        }) { q ->
            groupFolders = q.kids
            groups = q.chars
            crumb = q.crumb
            parentId = q.parent
            folderCounts = q.counts
        }
        bg({
            val store = store0(context)
            store.listFolders("group") to store.readKv("pinned_groups").orEmpty()
        }) { pair ->
            allGroupFolders = pair.first
            pinned = pair.second.split("\n").filter { it.isNotBlank() }.toSet()
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("群聊（${groups.size}）", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = {
                    // 群聊也有文件夹（建在当前这一层），名字之后可改
                    bg({
                        store0(context).createFolder(
                            "群文件夹 " + (groupFolders.size + 1),
                            kind = "group",
                            parentId = currentFolder,
                        )
                    }) { tick++ }
                }) { Text("新建文件夹") }
                TextButton(onClick = { creating = true }) { Text("新建群聊") }
        }
        Spacer(Modifier.height(6.dp))
        if (groups.isEmpty()) {
            Text("还没有群聊：点右上角「新建群聊」，选两个以上角色", style = MaterialTheme.typography.bodySmall)
        } else {
            if (currentFolder.isNotEmpty() || groupFolders.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (currentFolder.isNotEmpty()) {
                    TextButton(onClick = { currentFolder = parentId }) { Text("‹ 上一级") }
                }
                Text(crumb, style = MaterialTheme.typography.labelMedium)
            }
        }
        for (folder in groupFolders) {
            Card(
                onClick = { currentFolder = folder.id },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("📁 ${folder.title}", modifier = Modifier.padding(12.dp))
                    Spacer(Modifier.weight(1f))
                    Text(
                        "${folderCounts[folder.id] ?: 0} 个",
                        style = MaterialTheme.typography.labelSmall,
                    )
                    TextButton(onClick = {
                        // 删文件夹：里面的群会上移，不会跟着删
                        pendingWhat = "删除文件夹「${folder.title}」？里面的群会回到上一层，不会一起删掉。"
                            pendingDelete = { bg({ store0(context).deleteFolder(folder.id) }) { tick++ } }
                    }) { Text("删除") }
                    TextButton(onClick = { renamingFolder = folder }) { Text("改名") }
                }
            }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(groups.sortedByDescending { it.id in pinned }, key = { it.id }) { group ->
                    val names = group.members.mapNotNull { id ->
                        characters.firstOrNull { it.id == id }?.name
                    }
                    Card(onClick = { onOpen(group) }, modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    group.title,
                                    style = MaterialTheme.typography.titleSmall,
                                    modifier = Modifier.weight(1f),
                                )
                                var menuOpen by remember { mutableStateOf(false) }
                                Box {
                                    TextButton(onClick = { menuOpen = true }) { Text("⋮") }
                                    DropdownMenu(
                                        expanded = menuOpen,
                                        onDismissRequest = { menuOpen = false },
                                    ) {
                                        DropdownMenuItem(
                                            text = { Text("移动到文件夹…") },
                                            onClick = {
                                                menuOpen = false
                                                movingGroup = group
                                            },
                                        )
                                        DropdownMenuItem(
                                            text = {
                                                Text(if (group.id in pinned) "取消置顶" else "置顶")
                                            },
                                            onClick = {
                                                menuOpen = false
                                                val next = if (group.id in pinned) {
                                                    pinned - group.id
                                                } else {
                                                    pinned + group.id
                                                }
                                                pinned = next
                                                bg({
                                                    store0(context).writeKv(
                                                        "pinned_groups",
                                                        next.joinToString("\n"),
                                                    )
                                                }) { tick++ }
                                            },
                                        )
                                        DropdownMenuItem(
                                            text = { Text("复制一群") },
                                            onClick = {
                                                menuOpen = false
                                                bg({
                                                    val s = store0(context)
                                                    val copy = s.createGroup(
                                                        group.title + " 副本",
                                                        group.members,
                                                    )
                                                    // 禁言名单与所在文件夹一并带过去
                                                    s.upsertGroup(
                                                        copy.copy(
                                                            muted = group.muted,
                                                            folderId = group.folderId,
                                                        ),
                                                    )
                                                }) { tick++ }
                                            },
                                        )
                                        DropdownMenuItem(
                                            text = { Text("置顶") },
                                            onClick = {
                                                menuOpen = false
                                                // 用更新时间当排序权重：置顶 = 把它顶到最前
                                                bg({
                                                    store0(context).upsertGroup(
                                                        group.copy(updatedAt = nowSeconds()),
                                                    )
                                                }) { tick++ }
                                            },
                                        )
                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    "删除群聊",
                                                    color = MaterialTheme.colorScheme.error,
                                                )
                                            },
                                            onClick = {
                                                menuOpen = false
                                                pendingWhat = "删除群聊「${group.title}」？它的聊天记录会一起删除。"
                                                pendingDelete = {
                                                    bg({ store0(context).deleteGroup(group.id) }) { tick++ }
                                                }
                                            },
                                        )
                                    }
                                }
                            }
                            Text(
                                names.joinToString("、").ifEmpty { "（成员已删除）" },
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                "会说话：" + names.filterIndexed { index, _ ->
                                    group.members.getOrNull(index)?.let { it !in group.muted } == true
                                }.joinToString("、").ifEmpty { "暂无（全部禁言）" },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }

    movingGroup?.let { target ->
        AlertDialog(
            onDismissRequest = { movingGroup = null },
            title = { Text("把「${target.title}」移到") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    TextButton(onClick = {
                        movingGroup = null
                        bg({ store0(context).upsertGroup(target.copy(folderId = "")) }) { tick++ }
                    }) { Text("（顶层）") }
                    for (folder in allGroupFolders) {
                        TextButton(onClick = {
                            movingGroup = null
                            bg({ store0(context).upsertGroup(target.copy(folderId = folder.id)) }) { tick++ }
                        }) { Text("📁 " + folder.title) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { movingGroup = null }) { Text("取消") } },
        )
    }

    renamingFolder?.let { folder ->
        var newName by remember { mutableStateOf(folder.title) }
        AlertDialog(
            onDismissRequest = { renamingFolder = null },
            title = { Text("文件夹改名") },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("名字") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val name = newName.trim()
                    renamingFolder = null
                    if (name.isNotEmpty()) {
                        bg({ store0(context).upsertFolder(folder.copy(name = name)) }) { tick++ }
                    }
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { renamingFolder = null }) { Text("取消") } },
        )
    }

    if (creating) {
        var picked by remember { mutableStateOf(setOf<String>()) }
        AlertDialog(
            onDismissRequest = { creating = false },
            title = { Text("新建群聊") },
            text = {
                Column {
                    Text("选两个以上角色（新群默认全员禁言，进去后再解禁）")
                    Spacer(Modifier.height(6.dp))
                    LazyColumn(modifier = Modifier.heightIn(max = 300.dp)) {
                        items(characters, key = { it.id }) { character ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TextButton(onClick = {
                                    picked = if (character.id in picked) picked - character.id
                                    else picked + character.id
                                }) { Text(if (character.id in picked) "☑" else "☐") }
                                Text(character.name)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = picked.size >= 2,
                    onClick = {
                        val ids = characters.map { it.id }.filter { it in picked }
                        creating = false
                        bg({
                val created = store0(context).createGroup("", ids)
                if (currentFolder.isNotEmpty()) {
                    store0(context).upsertGroup(created.copy(folderId = currentFolder))
                }
                created
            }) {
                            tick++
                            onChanged()
                        }
                    },
                ) { Text("创建") }
            },
            dismissButton = { TextButton(onClick = { creating = false }) { Text("取消") } },
        )
    }
}

private data class GroupQuad(
    val kids: List<com.tavern.domain.models.Folder>,
    val chars: List<com.tavern.domain.models.Group>,
    val crumb: String,
    val parent: String,
    val counts: Map<String, Int>,
)

/** 统一的删除确认弹窗（防误触）。 */
@Composable
private fun ConfirmDelete(pending: (() -> Unit)?, what: String, onClear: () -> Unit) {
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
