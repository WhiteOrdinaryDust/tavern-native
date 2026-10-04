package com.tavern.chat

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import com.tavern.domain.models.Folder
import com.tavern.domain.models.WorldBook
import com.tavern.domain.models.WorldEntry
import com.tavern.domain.worldbook.Worldbook
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

private data class BookQuad(
    val kids: List<Folder>,
    val books: List<WorldBook>,
    val allBooks: List<WorldBook>,
    val counts: Map<String, Int>,
)

/**
 * 世界书库（主页页签）：书 + 文件夹。
 *
 * 交互按使用习惯收敛：**点整本书 = 打开它（展开条目）**，其余操作收进行尾的 ⋮。
 * 归属（书在哪一层）存在 kv `wb_folder_<书id>`，不必给表加列；文件夹被删时书回到上层。
 */
@Composable
fun WorldBookSection() {
    val context = LocalContext.current
    var tick by remember { mutableStateOf(0) }
    var notice by remember { mutableStateOf("") }
    var currentFolder by remember { mutableStateOf("") }
    var folders by remember { mutableStateOf<List<Folder>>(emptyList()) }
    var allFolders by remember { mutableStateOf<List<Folder>>(emptyList()) }
    var parentId by remember { mutableStateOf("") }
    var crumb by remember { mutableStateOf("全部世界书") }
    var books by remember { mutableStateOf<List<WorldBook>>(emptyList()) }
    var allBooksState by remember { mutableStateOf<List<WorldBook>>(emptyList()) }
    var counts by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var expanded by remember { mutableStateOf("") }
    var entries by remember { mutableStateOf<List<WorldEntry>>(emptyList()) }
    var orphans by remember { mutableStateOf<List<WorldEntry>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var entryQuery by remember { mutableStateOf("") }
    var allEntries by remember { mutableStateOf<List<WorldEntry>>(emptyList()) }
    var editing by remember { mutableStateOf<WorldEntry?>(null) }
    var editingIsNew by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<WorldBook?>(null) }
    var renamingFolder by remember { mutableStateOf<Folder?>(null) }
    var movingBook by remember { mutableStateOf<WorldBook?>(null) }
    var creating by remember { mutableStateOf(false) }
    var deletingBook by remember { mutableStateOf<WorldBook?>(null) }
    var mergingBook by remember { mutableStateOf<WorldBook?>(null) }

    fun reload() {
        bg({
            val store = store0(context)
            val kids = store.childrenOf(currentFolder, "worldbook")
            val path = if (currentFolder.isEmpty()) emptyList() else store.folderPath(currentFolder)
            val allBooks = store.allWorldBooks()
            val folderIds = store.listFolders("worldbook").map { it.id }.toSet()
            // 自愈：文件夹被删后它的 id 就成了悬空值，书会"消失"，这里把它放回顶层
            for (book in allBooks) {
                val raw = store.readKv("wb_folder_" + book.id).orEmpty()
                if (raw.isNotEmpty() && raw !in folderIds) {
                    store.writeKv("wb_folder_" + book.id, "")
                }
            }
            val inFolder = allBooks.filter { book ->
                store.readKv("wb_folder_" + book.id).orEmpty() == currentFolder
            }
            val countMap = allBooks.associate { it.id to store.countWorldEntriesInBook(it.id) }
            BookQuad(kids, inFolder, allBooks, countMap) to
                (store.listFolders("worldbook") to path)
        }) { data ->
            folders = data.first.kids
            books = data.first.books
            allBooksState = data.first.allBooks
            counts = data.first.counts
            allFolders = data.second.first
            val path = data.second.second
            parentId = path.dropLast(1).lastOrNull()?.id ?: ""
            crumb = if (path.isEmpty()) "全部世界书" else path.joinToString(" / ") { it.title }
        }
    }

    LaunchedEffect(tick, currentFolder) {
        reload()
        // 不属于任何书的散装条目（老库遗留）：不列出来就永远看不到、也注入不了
        bg({ store0(context).orphanEntries() }) { list -> orphans = list }
        bg({ store0(context).allWorldEntries() }) { list -> allEntries = list }
    }

    fun reloadEntries(book: WorldBook) {
        bg({ store0(context).listWorldEntries(book.characterId, bookId = book.id) }) { list ->
            entries = list
        }
    }

    fun exportBook(book: WorldBook) {
        bg({
            val store = store0(context)
            val bookEntries = store.listWorldEntries(book.characterId, bookId = book.id)
            val obj = buildJsonObject {
                put("name", JsonPrimitive(book.name))
                put(
                    "entries",
                    buildJsonObject {
                        bookEntries.forEachIndexed { index, entry ->
                            put(index.toString(), Worldbook.toStEntry(entry))
                        }
                    },
                )
            }
            Json.encodeToString(JsonObject.serializer(), obj)
        }) { json ->
            val clipboard = context.getSystemService(
                android.content.Context.CLIPBOARD_SERVICE,
            ) as android.content.ClipboardManager
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("世界书", json))
            val share = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(android.content.Intent.EXTRA_SUBJECT, book.name)
                putExtra(android.content.Intent.EXTRA_TEXT, json)
            }
            context.startActivity(android.content.Intent.createChooser(share, "导出世界书"))
            notice = "已导出「${book.name}」（${json.length} 字），并复制到剪贴板"
        }
    }

    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        bg({
            val fallbackName = uri.lastPathSegment
                ?.substringAfterLast('/')
                ?.substringAfterLast(':')
                ?.substringBeforeLast('.')
                ?.takeIf { it.isNotBlank() }
                ?: "导入的世界书"
            val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?.toString(Charsets.UTF_8) ?: return@bg "读不到文件内容"
            val raw = try {
                Json.parseToJsonElement(text)
            } catch (_: Exception) {
                return@bg "不是合法的 JSON"
            }
            val (parsed, _) = Worldbook.extractBook(raw, "")
            if (parsed.isEmpty()) return@bg "这个文件里没有世界书条目"
            val name = Worldbook.bookName(raw, fallback = fallbackName)
            val store = store0(context)
            store.createWorldBook("", name)
            val book = store.allWorldBooks().lastOrNull { it.title == name } ?: return@bg "建书失败"
            for (entry in parsed) {
                store.upsertWorldEntry(entry.copy(bookId = book.id))
            }
            store.writeKv("wb_folder_" + book.id, currentFolder)
            "已导入「$name」，${parsed.size} 条条目"
        }) { message ->
            notice = message
            tick++
        }
    }

    Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { creating = true }) { Text("新建世界书") }
            TextButton(onClick = {
                bg({
                    store0(context).createFolder(
                        "世界书夹 " + (folders.size + 1),
                        kind = "worldbook",
                        parentId = currentFolder,
                    )
                }) { tick++ }
            }) { Text("新建文件夹") }
            TextButton(onClick = { picker.launch(arrayOf("application/json", "*/*")) }) {
                Text("导入 JSON")
            }
            Spacer(Modifier.weight(1f))
        }
        if (notice.isNotEmpty()) {
            Text(notice, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(4.dp))
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("搜索：书名，或条目里的关键词/内容") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        val keyword = query.trim()
        if (keyword.isNotEmpty()) {
            val bookHits = allBooksState.filter { it.name.contains(keyword, ignoreCase = true) }
            val entryHits = allEntries.filter { entry ->
                entry.keys.any { it.contains(keyword, ignoreCase = true) } ||
                    entry.keySecondary.any { it.contains(keyword, ignoreCase = true) } ||
                    entry.content.contains(keyword, ignoreCase = true) ||
                    entry.comment.contains(keyword, ignoreCase = true)
            }.take(30)
            val titleOf = allBooksState.associate { it.id to it.name }
            Text(
                "书名命中 ${bookHits.size} 本 · 条目命中 ${entryHits.size} 条",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            for (book in bookHits.take(10)) {
                TextButton(onClick = {
                    query = ""
                    currentFolder = ""
                    expanded = book.id
                    reloadEntries(book)
                }) { Text("📕 ${book.name}", style = MaterialTheme.typography.bodySmall) }
            }
            for (entry in entryHits) {
                TextButton(onClick = {
                    editingIsNew = false
                    editing = entry
                }) {
                    Text(
                        "· " + (titleOf[entry.bookId] ?: "未分类") + " · " +
                            entry.title + "：" +
                            entry.keys.joinToString("、").ifEmpty { "（无关键词）" },
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (currentFolder.isNotEmpty()) {
                TextButton(onClick = { currentFolder = parentId }) { Text("‹ 上一级") }
            }
            Text(crumb, style = MaterialTheme.typography.labelMedium)
        }

        for (folder in folders) {
            Card(onClick = { currentFolder = folder.id }, modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "📁 ${folder.title}",
                        modifier = Modifier.weight(1f).padding(12.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    var folderMenu by remember { mutableStateOf(false) }
                    Box {
                        TextButton(onClick = { folderMenu = true }) { Text("⋮") }
                        DropdownMenu(expanded = folderMenu, onDismissRequest = { folderMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("改名") },
                                onClick = { folderMenu = false; renamingFolder = folder },
                            )
                            DropdownMenuItem(
                                text = { Text("删除", color = MaterialTheme.colorScheme.error) },
                                onClick = {
                                    folderMenu = false
                                    // 顺序很重要：先把书搬到上一层，再删文件夹
                                    bg({
                                        val store = store0(context)
                                        for (book in store.allWorldBooks()) {
                                            if (store.readKv("wb_folder_" + book.id).orEmpty() == folder.id) {
                                                store.writeKv("wb_folder_" + book.id, parentId)
                                            }
                                        }
                                        store.deleteFolder(folder.id)
                                    }) { tick++ }
                                },
                            )
                        }
                    }
                }
            }
        }

        // 「未分类」：散装条目的收容处（只在顶层显示）
        if (currentFolder.isEmpty() && orphans.isNotEmpty()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (expanded == "__orphan__") {
                                    expanded = ""
                                    entries = emptyList()
                                } else {
                                    expanded = "__orphan__"
                                    entries = orphans
                                }
                            }
                            .padding(12.dp),
                    ) {
                        Text("未分类", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "${orphans.size} 条 · 不属于任何书（点标题看条目）",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (expanded == "__orphan__") {
                        Column(modifier = Modifier.fillMaxWidth().padding(start = 8.dp)) {
                            for (entry in entries) {
                                TextButton(onClick = {
                                    editingIsNew = false
                                    editing = entry
                                }) {
                                    Text(
                                        "· ${entry.title}：${entry.triggerSummary}",
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        LazyColumn(modifier = Modifier.heightIn(max = 520.dp)) {
            items(books, key = { it.id }) { book ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable {
                                        if (expanded == book.id) {
                                            expanded = ""
                                        } else {
                                            expanded = book.id
                                            entryQuery = ""
                                            reloadEntries(book)
                                        }
                                    }
                                    .padding(12.dp),
                            ) {
                                Text(
                                    book.name,
                                    style = MaterialTheme.typography.titleSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    "${counts[book.id] ?: 0} 条 · " +
                                        if (expanded == book.id) "点标题收起" else "点标题看条目",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            var bookMenu by remember { mutableStateOf(false) }
                            Box {
                                TextButton(onClick = { bookMenu = true }) { Text("⋮") }
                                DropdownMenu(expanded = bookMenu, onDismissRequest = { bookMenu = false }) {
                                    DropdownMenuItem(
                                        text = { Text("导出（分享 JSON）") },
                                        onClick = { bookMenu = false; exportBook(book) },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("合并到…") },
                                        onClick = {
                                            bookMenu = false
                                            mergingBook = book
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("移到文件夹…") },
                                        onClick = { bookMenu = false; movingBook = book },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("改名") },
                                        onClick = { bookMenu = false; renaming = book },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("删除", color = MaterialTheme.colorScheme.error) },
                                        onClick = {
                                            bookMenu = false
                                            deletingBook = book
                                        },
                                    )
                                }
                            }
                        }
                        if (expanded == book.id) {
                            Column(modifier = Modifier.fillMaxWidth().padding(start = 8.dp)) {
                                OutlinedTextField(
                                    value = entryQuery,
                                    onValueChange = { entryQuery = it },
                                    label = { Text("在这本书里搜索条目") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                val shownEntries = if (entryQuery.trim().isEmpty()) {
                                    entries
                                } else {
                                    val k = entryQuery.trim()
                                    entries.filter { entry ->
                                        entry.title.contains(k, ignoreCase = true) ||
                                            entry.keys.any { it.contains(k, ignoreCase = true) } ||
                                            entry.content.contains(k, ignoreCase = true) ||
                                            entry.comment.contains(k, ignoreCase = true)
                                    }
                                }
                                if (shownEntries.isEmpty() && entries.isNotEmpty()) {
                                    Text(
                                        "没有匹配的条目",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                for (entry in shownEntries) {
                                    TextButton(onClick = {
                                        editingIsNew = false
                                        editing = entry
                                    }) {
                                        Text(
                                            "· ${entry.title}：${entry.triggerSummary}",
                                            style = MaterialTheme.typography.labelSmall,
                                        )
                                    }
                                }
                                if (entries.isEmpty()) {
                                    Text(
                                        "（这本书还没有条目）",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                TextButton(onClick = {
                                    editingIsNew = true
                                    editing = WorldEntry(bookId = book.id)
                                }) { Text("＋ 新增条目", style = MaterialTheme.typography.labelSmall) }
                            }
                        }
                    }
                }
            }
        }
    }

    mergingBook?.let { source ->
        AlertDialog(
            onDismissRequest = { mergingBook = null },
            title = { Text("把「${source.name}」合并到哪本") },
            text = {
                Column(modifier = Modifier.heightIn(max = 320.dp)) {
                    Text(
                        "会把这本书的 ${counts[source.id] ?: 0} 条条目全部搬到目标书，" +
                            "然后删除「${source.name}」。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    for (target in allBooksState.filter { it.id != source.id }) {
                        TextButton(onClick = {
                            mergingBook = null
                            bg({
                                val store = store0(context)
                                val entries = store.listWorldEntries(
                                    source.characterId,
                                    bookId = source.id,
                                )
                                for (entry in entries) {
                                    store.upsertWorldEntry(
                                        entry.copy(
                                            bookId = target.id,
                                            characterId = target.characterId,
                                        ),
                                    )
                                }
                                store.deleteWorldBook(source.id)
                                entries.size
                            }) { moved ->
                                notice = "已把 $moved 条条目合并进「${target.name}」，并删除原书"
                                tick++
                            }
                        }) { Text("📕 " + target.name) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { mergingBook = null }) { Text("取消") } },
        )
    }

    deletingBook?.let { book ->
        AlertDialog(
            onDismissRequest = { deletingBook = null },
            title = { Text("确认删除世界书") },
            text = {
                Text(
                    "「${book.name}」及其 ${counts[book.id] ?: 0} 条条目会一起删除，无法恢复。" +
                        "（已把它导入的角色/群聊会少一本书）",
                    style = MaterialTheme.typography.bodySmall,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    deletingBook = null
                    bg({ store0(context).deleteWorldBook(book.id) }) { tick++ }
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deletingBook = null }) { Text("取消") }
            },
        )
    }

    if (creating) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { creating = false },
            title = { Text("新建世界书") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("书名") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val title = name.trim().ifEmpty { "新建世界书" }
                    creating = false
                    bg({
                        val store = store0(context)
                        val book = store.createWorldBook("", title)
                        store.writeKv("wb_folder_" + book.id, currentFolder)
                    }) { tick++ }
                }) { Text("创建") }
            },
            dismissButton = { TextButton(onClick = { creating = false }) { Text("取消") } },
        )
    }

    renaming?.let { book ->
        var name by remember(book.id) { mutableStateOf(book.name) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("重命名世界书") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("书名") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val title = name.trim()
                    renaming = null
                    if (title.isNotEmpty()) {
                        bg({ store0(context).upsertWorldBook(book.copy(name = title)) }) { tick++ }
                    }
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("取消") } },
        )
    }

    renamingFolder?.let { folder ->
        var name by remember(folder.id) { mutableStateOf(folder.title) }
        AlertDialog(
            onDismissRequest = { renamingFolder = null },
            title = { Text("文件夹改名") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("名字") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val title = name.trim()
                    renamingFolder = null
                    if (title.isNotEmpty()) {
                        bg({ store0(context).upsertFolder(folder.copy(name = title)) }) { tick++ }
                    }
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { renamingFolder = null }) { Text("取消") } },
        )
    }

    movingBook?.let { book ->
        AlertDialog(
            onDismissRequest = { movingBook = null },
            title = { Text("把「${book.name}」移到") },
            text = {
                Column(modifier = Modifier.heightIn(max = 320.dp)) {
                    TextButton(onClick = {
                        movingBook = null
                        bg({ store0(context).writeKv("wb_folder_" + book.id, "") }) { tick++ }
                    }) { Text("（顶层）") }
                    for (folder in allFolders) {
                        TextButton(onClick = {
                            movingBook = null
                            bg({ store0(context).writeKv("wb_folder_" + book.id, folder.id) }) { tick++ }
                        }) { Text("📁 " + folder.title) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { movingBook = null }) { Text("取消") } },
        )
    }

    editing?.let { entry ->
        WorldBookEntryDialog(
            entry = entry,
            isNew = editingIsNew,
            onDismiss = { editing = null },
            onSave = { saved ->
                editing = null
                bg({
                    val store = store0(context)
                    store.upsertWorldEntry(saved)
                    // 复制/编辑之后要重新取一遍列表，否则看起来"没生效"（复制按钮就踩了这个坑）
                    if (saved.bookId.isEmpty()) {
                        store.orphanEntries()
                    } else {
                        val book = store.getWorldBook(saved.bookId)
                        if (book != null) {
                            store.listWorldEntries(book.characterId, bookId = book.id)
                        } else {
                            emptyList()
                        }
                    }
                }) { list ->
                    entries = list
                    if (saved.bookId.isEmpty()) orphans = list
                    tick++
                }
            },
            books = allBooksState,
            onDelete = {
                val id = entry.id
                val wasOrphan = entry.bookId.isEmpty()
                editing = null
                bg({
                    val store = store0(context)
                    store.deleteWorldEntry(id)
                    if (wasOrphan) {
                        store.orphanEntries()
                    } else {
                        val book = store.getWorldBook(entry.bookId)
                        if (book != null) {
                            store.listWorldEntries(book.characterId, bookId = book.id)
                        } else {
                            emptyList()
                        }
                    }
                }) { list ->
                    entries = list
                    if (wasOrphan) orphans = list
                    tick++
                }
            },
        )
    }
}
