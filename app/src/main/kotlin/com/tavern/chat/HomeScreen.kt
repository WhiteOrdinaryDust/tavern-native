package com.tavern.chat

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tavern.data.RoomStore
import com.tavern.data.TavernDatabase
import com.tavern.domain.cards.Cards
import com.tavern.domain.models.Character
import com.tavern.domain.postprocess.Postprocess

private data class Quad(
    val kids: List<com.tavern.domain.models.Folder>,
    val chars: List<Character>,
    val crumb: String,
    val parent: String,
    val counts: Map<String, Int>,
)

/** 把角色卡里的图存成这个角色的头像，返回路径（失败返回 null，退回文字头像）。 */
private fun saveAvatar(context: android.content.Context, characterId: String, bytes: ByteArray): String? = try {
    val dir = java.io.File(context.filesDir, "avatars").apply { mkdirs() }
    val file = java.io.File(dir, "$characterId.png")
    file.writeBytes(bytes)
    file.absolutePath
} catch (_: Exception) {
    null
}

/**
 * 主页：角色列表。
 *
 * 支持新建（手填名字/开场白）与**导入 SillyTavern 角色卡 PNG**（解析逻辑在 `:domain`，
 * 已经对着原实现测过：tEXt/iTXt、base64、V1/V2/V3 都认）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    characters: List<Character>,
    tab: Int = 0,
    onTabChanged: (Int) -> Unit = {},
    onOpen: (Character) -> Unit,
    onOpenGroup: (com.tavern.domain.models.Group) -> Unit,
    onOpenSettings: () -> Unit,
    onChanged: () -> Unit,
) {
    val context = LocalContext.current
    var currentFolder by remember { mutableStateOf("") }
    var children by remember { mutableStateOf<List<com.tavern.domain.models.Folder>>(emptyList()) }
    var inFolder by remember { mutableStateOf<List<Character>>(emptyList()) }
    var creatingFolder by remember { mutableStateOf(false) }
    // 文件夹相关的显示信息在这里一次算好（组合函数里不能碰数据库）
    var crumb by remember { mutableStateOf("全部角色") }
    var parentId by remember { mutableStateOf("") }
    var folderCounts by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var allFolders by remember { mutableStateOf<List<com.tavern.domain.models.Folder>>(emptyList()) }
    var folderDepth by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var movingTarget by remember { mutableStateOf<Character?>(null) }
    var renamingFolder by remember { mutableStateOf<com.tavern.domain.models.Folder?>(null) }
    var avatarTarget by remember { mutableStateOf<Character?>(null) }
    var avatarTemp by remember { mutableStateOf("") }
    var pinned by remember { mutableStateOf<Set<String>>(emptySet()) }
    // ---- 换头像：选图 → 裁剪（缩放+框选）→ 存成新的头像文件 ----
    val avatarPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri != null) {
            bg({
                val target = java.io.File(context.filesDir, "avatars").apply { mkdirs() }
                val temp = java.io.File(target, "_pick.png")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    temp.outputStream().use { output -> input.copyTo(output) }
                }
                temp.absolutePath
            }) { path -> avatarTemp = path }
        }
    }


    var pendingDelete by remember { mutableStateOf<(() -> Unit)?>(null) }
    var editingCharacter by remember { mutableStateOf<Character?>(null) }
    var pendingWhat by remember { mutableStateOf("") }

    ConfirmDelete(pendingDelete, pendingWhat) { pendingDelete = null }
    var tick by remember { mutableStateOf(0) }

    // 每次进/出文件夹或建完东西后重载这一层
    androidx.compose.runtime.LaunchedEffect(currentFolder, tick, characters.size) {
        bg({
            val store = store0(context)
            val kids = store.childrenOf(currentFolder, "character")
            val path = if (currentFolder.isEmpty()) emptyList() else store.folderPath(currentFolder)
            Quad(
                kids,
                store.listCharacters(currentFolder),
                if (path.isEmpty()) "全部角色" else path.joinToString(" / ") { it.title },
                path.dropLast(1).lastOrNull()?.id ?: "",
                kids.associate { it.id to store.countInFolder(it.id) },
            )
        }) { q ->
            children = q.kids
            inFolder = q.chars
            crumb = q.crumb
            parentId = q.parent
            folderCounts = q.counts
        }
        bg({
            val store = store0(context)
            val list = store.listFolders("character")
            // 置顶集合（存在 kv 里，避免改表结构）
            bg({ store0(context).readKv("pinned_characters").orEmpty() }) { raw ->
                pinned = raw.split("\n").filter { it.isNotBlank() }.toSet()
            }
            // 层级缩进在这里一次算好（组合函数里不能查库）
            list to list.associate { it.id to (store.folderPath(it.id).size - 1) }
        }) { pair ->
            allFolders = pair.first
            folderDepth = pair.second
        }
    }

    var notice by remember { mutableStateOf("") }
    var creating by remember { mutableStateOf(false) }

    fun importCard(uri: Uri?) {
        if (uri == null) return
        bg({
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: return@bg "读不到这个文件"
            val raw = Cards.loadCardBytes(bytes) ?: return@bg "这个文件不是角色卡（PNG 里没有 chara 数据）"
            val base = Cards.normalizeCard(raw).copy(folderId = currentFolder)
            // 卡里的图（PNG 导入才有）存成头像，列表就能显示真人像了
            val avatar = saveAvatar(context, base.id, bytes)
            val char = if (avatar != null) base.copy(avatarPath = avatar) else base
            store0(context).upsertCharacter(char)
            "已导入：${char.name}"
        }) { message ->
            notice = message
            onChanged()
        }
    }

    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri -> importCard(uri) }

    Scaffold(
        // 容器透明，背景图才透得出来；但必须显式给 contentColor ——
        // 否则 Compose 无法从透明色推导文字颜色，会回退成黑色（深色主题下几乎不可见）
        containerColor = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurface,
        topBar = {
            TopAppBar(
                title = { Text("酒馆") },
                actions = {
                    TextButton(onClick = onOpenSettings) { Text("设置") }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { creating = true }) { Text("＋") }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            // 页签与常用操作放在内容区第一行（顶栏只放标题，避免手机窄屏挤成一列）
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { onTabChanged(0) }) { Text(if (tab == 0) "● 角色" else "○ 角色") }
                TextButton(onClick = { onTabChanged(1) }) { Text(if (tab == 1) "● 群聊" else "○ 群聊") }
                TextButton(onClick = { onTabChanged(2) }) { Text(if (tab == 2) "● 世界书" else "○ 世界书") }
                Spacer(Modifier.weight(1f))
                if (tab == 0) {
                    TextButton(onClick = { picker.launch(arrayOf("image/png", "application/json", "*/*")) }) {
                        Text("导入卡片")
                    }
                    TextButton(onClick = { creatingFolder = true }) { Text("新建文件夹") }
                }
            }
            if (tab == 2) {
                com.tavern.chat.WorldBookSection()
            }
            if (tab == 1) {
                com.tavern.chat.GroupSection(
                    characters = characters,
                    onOpen = onOpenGroup,
                    onChanged = onChanged,
                )
            }
            if (tab == 0) {
            if (inFolder.isEmpty() && children.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "这一层还是空的：点上面「导入卡片」，或先建个文件夹分类",
                            modifier = Modifier.padding(24.dp),
                        )
                        // 空文件夹里也必须能出去（之前这里没有返回入口，进去就出不来了）
                        if (currentFolder.isNotEmpty()) {
                            TextButton(onClick = { currentFolder = parentId }) {
                                Text("‹ 返回上一层")
                            }
                        }
                    }
                }
            } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // 面包屑 + 返回上一级
                if (currentFolder.isNotEmpty() || children.isNotEmpty()) {
                    item(key = "__crumb__") {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (currentFolder.isNotEmpty()) {
                                TextButton(onClick = { currentFolder = parentId }) { Text("‹ 上一级") }
                            }
                            Text(crumb, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
                items(children, key = { it.id }) { folder ->
                    // 整行都能点进文件夹（以前只有文字可点，太窄）
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
                                // 删文件夹：里面的角色会上移到父级，不会跟着删
                                pendingWhat = "删除文件夹「${folder.title}」？里面的角色会回到上一层，不会一起删掉。"
                            pendingDelete = { bg({ store0(context).deleteFolder(folder.id) }) { tick++ } }
                            }) { Text("删除") }
                            TextButton(onClick = { renamingFolder = folder }) { Text("改名") }
                        }
                    }
                }
                items(
                    inFolder.sortedByDescending { it.id in pinned },
                    key = { it.id },
                ) { character ->
                    Card(
                        onClick = { onOpen(character) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            val avatarPath = character.avatarPath
                            val avatar = remember(avatarPath) {
                                if (avatarPath.isNotEmpty() && java.io.File(avatarPath).exists()) {
                                    try {
                                        android.graphics.BitmapFactory.decodeFile(avatarPath)?.asImageBitmap()
                                    } catch (_: Exception) {
                                        null
                                    }
                                } else {
                                    null
                                }
                            }
                            if (avatar != null) {
                                Image(
                                    bitmap = avatar,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .size(44.dp)
                                        .clip(CircleShape)
                                        .padding(end = 2.dp),
                                )
                                Spacer(Modifier.width(10.dp))
                            } else {
                                // 没图就用名字首字（中文 1~2 字、拉丁取首字母，逻辑在 :domain）
                                Box(
                                    modifier = Modifier
                                        .height(44.dp)
                                        .padding(end = 12.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        Postprocess.avatarLines(character.name).joinToString("\n"),
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                    )
                                }
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(character.name, style = MaterialTheme.typography.titleMedium)
                                val preview = character.greeting.ifEmpty { character.description }
                                if (preview.isNotEmpty()) {
                                    Text(
                                        preview.replace("\n", " "),
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            // 行内操作收进右边的 ⋮ 菜单，后续功能都往这里加
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
                                            movingTarget = character
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = {
                                            Text(if (character.id in pinned) "取消置顶" else "置顶")
                                        },
                                        onClick = {
                                            menuOpen = false
                                            val next = if (character.id in pinned) {
                                                pinned - character.id
                                            } else {
                                                pinned + character.id
                                            }
                                            pinned = next
                                            bg({
                                                store0(context).writeKv(
                                                    "pinned_characters",
                                                    next.joinToString("\n"),
                                                )
                                            }) { tick++ }
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("编辑角色卡…") },
                                        onClick = {
                                            menuOpen = false
                                            editingCharacter = character
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("导出角色卡（JSON）") },
                                        onClick = {
                                            menuOpen = false
                                            val json = try {
                                                String(
                                                    com.tavern.domain.cards.Cards
                                                        .cardToJsonBytes(character),
                                                    Charsets.UTF_8,
                                                )
                                            } catch (_: Exception) {
                                                ""
                                            }
                                            if (json.isEmpty()) {
                                                notice = "导出失败：这张卡读不出来"
                                            } else {
                                                val clipboard = context.getSystemService(
                                                    android.content.Context.CLIPBOARD_SERVICE,
                                                ) as android.content.ClipboardManager
                                                clipboard.setPrimaryClip(
                                                    android.content.ClipData.newPlainText(
                                                        "角色卡",
                                                        json,
                                                    ),
                                                )
                                                val share = android.content.Intent(
                                                    android.content.Intent.ACTION_SEND,
                                                ).apply {
                                                    type = "text/plain"
                                                    putExtra(
                                                        android.content.Intent.EXTRA_SUBJECT,
                                                        character.name,
                                                    )
                                                    putExtra(android.content.Intent.EXTRA_TEXT, json)
                                                }
                                                context.startActivity(
                                                    android.content.Intent.createChooser(
                                                        share,
                                                        "导出角色卡",
                                                    ),
                                                )
                                                notice = "已导出「${character.name}」" +
                                                    "（${json.length} 字），并复制到剪贴板"
                                            }
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("恢复默认头像") },
                                        onClick = {
                                            menuOpen = false
                                            val old = character.avatarPath
                                            bg({
                                                if (old.isNotEmpty()) {
                                                    runCatching { java.io.File(old).delete() }
                                                }
                                                store0(context).upsertCharacter(
                                                    character.copy(avatarPath = ""),
                                                )
                                            }) { tick++ }
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("换头像") },
                                        onClick = {
                                            menuOpen = false
                                            avatarTarget = character
                                            avatarPicker.launch(arrayOf("image/*"))
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("复制一份") },
                                        onClick = {
                                            menuOpen = false
                                            bg({
                                                store0(context).upsertCharacter(
                                                    character.copy(
                                                        id = com.tavern.domain.models.newId(),
                                                        name = character.name + " 副本",
                                                    ),
                                                )
                                            }) { tick++ }
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("删除角色", color = MaterialTheme.colorScheme.error) },
                                        onClick = {
                                            menuOpen = false
                                            pendingWhat = "删除角色「${character.name}」？" +
                                                "TA 的所有会话与消息也会一起删除，无法恢复。"
                                            pendingDelete = {
                                                bg({ store0(context).deleteCharacter(character.id) }) { tick++ }
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
            }
            }
            if (notice.isNotEmpty()) {
                Text(
                    notice,
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
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

    editingCharacter?.let { target ->
        CharacterEditDialog(
            character = target,
            onDismiss = { editingCharacter = null },
            onSave = { updated ->
                editingCharacter = null
                bg({ store0(context).upsertCharacter(updated) }) { tick++ }
            },
        )
    }

    movingTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { movingTarget = null },
            title = { Text("把「${target.name}」移到") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    TextButton(onClick = {
                        movingTarget = null
                        bg({ store0(context).moveToFolder(target.id, "", "character") }) { tick++ }
                    }) { Text("（顶层）") }
                    for (folder in allFolders) {
                        TextButton(onClick = {
                            movingTarget = null
                            bg({ store0(context).moveToFolder(target.id, folder.id, "character") }) { tick++ }
                        }) { Text("　".repeat(folderDepth[folder.id] ?: 0) + "📁 " + folder.title) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { movingTarget = null }) { Text("取消") } },
        )
    }

    if (creatingFolder) {
        var folderName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { creatingFolder = false },
            title = { Text("新建文件夹") },
            text = {
                OutlinedTextField(
                    value = folderName,
                    onValueChange = { folderName = it },
                    label = { Text("名字") },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val name = folderName.trim().ifEmpty { "新建文件夹" }
                    creatingFolder = false
                    bg({
                        store0(context).createFolder(name, kind = "character", parentId = currentFolder)
                    }) { tick++ }
                }) { Text("创建") }
            },
            dismissButton = { TextButton(onClick = { creatingFolder = false }) { Text("取消") } },
        )
    }

    avatarTarget?.let { target ->
        if (avatarTemp.isNotEmpty()) {
            com.tavern.chat.AvatarCropDialog(
                sourcePath = avatarTemp,
                onCancel = { avatarTarget = null; avatarTemp = "" },
                onSave = { bitmap ->
                    val old = target.avatarPath
                    avatarTarget = null
                    avatarTemp = ""
                    bg({
                        // 文件名带时间戳：路径一变，气泡里的头像缓存自然刷新
                        val name = target.id + "_" + System.currentTimeMillis()
                        val path = saveAvatarBitmap(
                            java.io.File(context.filesDir, "avatars"),
                            name,
                            bitmap,
                        )
                        if (path != null) {
                            if (old.isNotEmpty()) {
                                runCatching { java.io.File(old).delete() }
                            }
                            store0(context).upsertCharacter(target.copy(avatarPath = path))
                        }
                        path
                    }) { tick++ }
                },
            )
        }
    }

    if (creating) {
        var name by remember { mutableStateOf("") }
        var greeting by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { creating = false },
            title = { Text("新建角色") },
            text = {
                Column {
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("名字") })
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = greeting,
                        onValueChange = { greeting = it },
                        label = { Text("开场白") },
                        modifier = Modifier.height(120.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val trimmed = name.trim().ifEmpty { "未命名角色" }
                    val first = greeting.trim()
                    creating = false
                    bg({
                        store0(context).upsertCharacter(
                            Character(name = trimmed, firstMes = first, folderId = currentFolder),
                        )
                    }) { tick++; onChanged() }
                }) { Text("创建") }
            },
            dismissButton = { TextButton(onClick = { creating = false }) { Text("取消") } },
        )
    }
}

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
