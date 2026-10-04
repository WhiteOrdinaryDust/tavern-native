package com.tavern.domain.store

import com.tavern.domain.models.ApiSettings
import com.tavern.domain.models.Character
import com.tavern.domain.models.Chat
import com.tavern.domain.models.Folder
import com.tavern.domain.models.Group
import com.tavern.domain.models.Message
import com.tavern.domain.models.WorldBook
import com.tavern.domain.models.WorldEntry
import com.tavern.domain.models.newId

/**
 * 数据层（内存实现）。
 *
 * 对应 Flet 版 `tavern/store.py` 的行为：文件夹上移、分支会话、自动标题、
 * 散装条目归入「未分类」、按书优先级排序等。
 *
 * Room/SQLite 的落地实现（`:data`）与它保持同一套方法，界面只依赖这些方法。
 * 排序用「时间戳 + 自增序号」，同一毫秒内也不会乱序（Python 那边靠浮点时间戳）。
 */
class InMemoryStore {

    private val folders = linkedMapOf<String, Folder>()
    private val characters = linkedMapOf<String, Character>()
    private val groups = linkedMapOf<String, Group>()
    private val chats = linkedMapOf<String, Chat>()
    private val messages = linkedMapOf<String, Message>()
    private val books = linkedMapOf<String, WorldBook>()
    private val entries = linkedMapOf<String, WorldEntry>()
    private val kv = linkedMapOf<String, Any?>()

    private var seq = 0L
    private fun nextSeq(): Long = ++seq
    private val chatSeq = linkedMapOf<String, Long>()
    private val messageSeq = linkedMapOf<String, Long>()
    private val groupSeq = linkedMapOf<String, Long>()

    var settings: ApiSettings = ApiSettings()
    var appearance: Any? = null

    // ------------------------------------------------------------------ 设置

    fun getSetting(key: String, default: Any? = null): Any? = kv[key] ?: default
    fun setSetting(key: String, value: Any?) { kv[key] = value }

    // ------------------------------------------------------------------ 文件夹

    fun listFolders(kind: String = "character"): List<Folder> =
        folders.values.filter { it.kind == kind }.sortedWith(compareBy({ it.sortOrder }, { it.name }))

    fun getFolder(folderId: String): Folder? = folders[folderId]

    fun upsertFolder(folder: Folder): Folder { folders[folder.id] = folder; return folder }

    fun createFolder(name: String = "", kind: String = "character", parentId: String = ""): Folder =
        upsertFolder(Folder(name = name.ifEmpty { "新建文件夹" }, kind = kind, parentId = parentId))

    fun childrenOf(parentId: String, kind: String = "character"): List<Folder> =
        listFolders(kind).filter { it.parentId == parentId }

    /** 从根到该文件夹的路径（面包屑用）。 */
    fun folderPath(folderId: String): List<Folder> {
        val path = mutableListOf<Folder>()
        var current = folders[folderId]
        val guard = mutableSetOf<String>()
        while (current != null && guard.add(current.id)) {
            path.add(0, current)
            current = folders[current.parentId]
        }
        return path
    }

    /** 删文件夹：里面的子文件夹与角色/群聊**上移到它的父级**，不会连带删除。 */
    fun deleteFolder(folderId: String) {
        val folder = folders[folderId] ?: return
        val parent = folder.parentId
        for (child in listFolders(folder.kind)) {
            if (child.parentId == folderId) upsertFolder(child.copy(parentId = parent))
        }
        if (folder.kind == "character") {
            characters.replaceAll { _, c -> if (c.folderId == folderId) c.copy(folderId = parent) else c }
        } else {
            groups.replaceAll { _, g -> if (g.folderId == folderId) g.copy(folderId = parent) else g }
        }
        folders.remove(folderId)
    }

    fun renameFolder(folderId: String, name: String) {
        folders[folderId]?.let { folders[folderId] = it.copy(name = name) }
    }

    fun countInFolder(folderId: String, kind: String = "character"): Int =
        if (kind == "character") characters.values.count { it.folderId == folderId }
        else groups.values.count { it.folderId == folderId }

    fun moveToFolder(itemId: String, folderId: String, kind: String = "character") {
        if (kind == "character") {
            characters[itemId]?.let { characters[itemId] = it.copy(folderId = folderId) }
        } else {
            groups[itemId]?.let { groups[itemId] = it.copy(folderId = folderId) }
        }
    }

    // ------------------------------------------------------------------ 角色

    fun listCharacters(folderId: String? = null): List<Character> =
        characters.values
            .filter { folderId == null || it.folderId == folderId }
            .sortedWith(compareBy({ it.name }, { it.createdAt }))

    fun getCharacter(id: String): Character? = characters[id]

    fun upsertCharacter(char: Character): Character {
        characters[char.id] = char
        return char
    }

    fun deleteCharacter(characterId: String) {
        val chatIds = chats.values.filter { it.characterId == characterId }.map { it.id }
        for (cid in chatIds) {
            messages.entries.removeIf { it.value.chatId == cid }
            chats.remove(cid)
        }
        entries.entries.removeIf { it.value.characterId == characterId }
        books.entries.removeIf { it.value.characterId == characterId }
        characters.remove(characterId)
        // 群聊里也要摘掉它（空群直接删掉）
        removeCharacterFromGroups(characterId)
    }

    // ------------------------------------------------------------------ 会话

    private fun touchChat(chatId: String) {
        chats[chatId]?.let { chats[chatId] = it.copy(updatedAt = nowSeconds()) }
    }

    fun listChats(
        characterId: String? = null,
        groupId: String? = null,
    ): List<Chat> = chats.values
        .filter { (characterId == null || it.characterId == characterId) }
        .filter { (groupId == null || it.groupId == groupId) }
        .sortedWith(compareByDescending<Chat> { it.updatedAt }.thenByDescending { chatSeq[it.id] ?: 0 })

    fun getChat(chatId: String): Chat? = chats[chatId]

    fun createChat(characterId: String, title: String = "", groupId: String = ""): Chat {
        val chat = Chat(characterId = characterId, title = title, groupId = groupId)
        chats[chat.id] = chat
        chatSeq[chat.id] = nextSeq()
        return chat
    }

    fun primaryChat(characterId: String): Chat =
        listChats(characterId = characterId).firstOrNull() ?: createChat(characterId)

    fun primaryGroupChat(groupId: String, title: String = ""): Chat =
        listChats(groupId = groupId).firstOrNull() ?: createChat("", title, groupId = groupId)

    fun renameChat(chatId: String, title: String) {
        chats[chatId]?.let { chats[chatId] = it.copy(title = title, updatedAt = nowSeconds()) }
    }

    fun deleteChat(chatId: String) {
        messages.entries.removeIf { it.value.chatId == chatId }
        chats.remove(chatId)
    }

    fun countMessages(chatId: String): Int = messages.values.count { it.chatId == chatId }

    fun firstUserMessage(chatId: String): String =
        listMessages(chatId).firstOrNull { it.role == "user" }?.text ?: ""

    /** 会话还没有标题时，用第一条用户消息给它起个名。 */
    fun autotitleChat(chatId: String, text: String) {
        val title = (text).split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ").take(14)
        if (title.isEmpty()) return
        val chat = chats[chatId] ?: return
        if (chat.title.trim().isNotEmpty()) return
        chats[chatId] = chat.copy(title = title, updatedAt = nowSeconds())
    }

    /** 从某条消息处开分支：把这条及其之前的消息复制进一个新会话，后面留空。 */
    fun branchChat(chatId: String, messageId: String, title: String = ""): Chat? {
        val source = chats[chatId] ?: return null
        val keep = mutableListOf<Message>()
        var found = false
        for (msg in listMessages(chatId)) {
            keep += msg
            if (msg.id == messageId) { found = true; break }
        }
        if (!found) return null

        val base = title.ifBlank { source.title.ifBlank { "会话" } }.trim()
        val newChat = createChat(source.characterId, title = "分支 · $base", groupId = source.groupId)
        for (msg in keep) {
            val copied = addMessage(
                newChat.id, msg.role, msg.content,
                reasoning = msg.reasoning,
                variants = msg.variants.ifEmpty { null },
                speaker = msg.speaker,
            )
            if (msg.variantIndex != 0) {
                copied.variantIndex = msg.variantIndex
                copied.ensureVariants()
                saveMessage(copied)
            }
        }
        return newChat
    }

    fun touch(chatId: String) = touchChat(chatId)

    fun resetChat(chatId: String) {
        messages.entries.removeIf { it.value.chatId == chatId }
    }

    fun setChatBooks(chatId: String, bookIds: List<String>) {
        chats[chatId]?.let { chats[chatId] = it.copy(bookIds = bookIds, updatedAt = nowSeconds()) }
    }

    fun setChatSummary(chatId: String, summary: String, uptoId: String) {
        chats[chatId]?.let {
            chats[chatId] = it.copy(summary = summary, summaryUpto = uptoId, updatedAt = nowSeconds())
        }
    }

    fun setChatWebSearch(chatId: String, enabled: Boolean) {
        chats[chatId]?.let { chats[chatId] = it.copy(webSearch = enabled, updatedAt = nowSeconds()) }
    }

    fun setChatRhythm(chatId: String, timeGear: String, authority: String) {
        chats[chatId]?.let {
            chats[chatId] = it.copy(rhythmTime = timeGear, rhythmAuthority = authority, updatedAt = nowSeconds())
        }
    }

    fun characterOfChat(chatId: String): String? = chats[chatId]?.characterId?.ifEmpty { null }

    // ------------------------------------------------------------------ 消息

    fun listMessages(chatId: String): List<Message> = messages.values
        .filter { it.chatId == chatId }
        .sortedBy { messageSeq[it.id] ?: 0 }

    fun addMessage(
        chatId: String,
        role: String,
        content: String,
        reasoning: String = "",
        variants: List<String>? = null,
        speaker: String = "",
        usage: Map<String, Int> = emptyMap(),
    ): Message {
        val msg = Message(
            chatId = chatId, role = role, content = content, reasoning = reasoning,
            speaker = speaker, usage = usage,
        )
        if (!variants.isNullOrEmpty()) {
            msg.variants = variants.map { it }.toMutableList()
            msg.variantIndex = 0
        }
        msg.ensureVariants()
        messages[msg.id] = msg
        messageSeq[msg.id] = nextSeq()
        touchChat(chatId)
        return msg
    }

    fun saveMessage(msg: Message) { messages[msg.id] = msg }

    fun updateMessage(messageId: String, content: String, reasoning: String? = null) {
        val msg = messages[messageId] ?: return
        msg.replaceCurrent(content)
        if (reasoning != null) msg.reasoning = reasoning
    }

    fun deleteMessage(messageId: String) { messages.remove(messageId) }

    fun lastMessage(chatId: String): Message? = listMessages(chatId).lastOrNull()

    // ------------------------------------------------------------------ 群聊

    fun listGroups(folderId: String? = null): List<Group> = groups.values
        .filter { folderId == null || it.folderId == folderId }
        .sortedBy { it.name }

    fun getGroup(groupId: String): Group? = groups[groupId]

    fun upsertGroup(group: Group): Group {
        groups[group.id] = group
        if (!groupSeq.containsKey(group.id)) groupSeq[group.id] = nextSeq()
        return group
    }

    fun createGroup(name: String, memberIds: List<String>, folderId: String = ""): Group =
        upsertGroup(
            Group(
                name = name.ifEmpty { "新群聊" },
                members = memberIds,
                muted = memberIds,  // 新群默认全员禁言，解禁谁谁出场
                folderId = folderId,
            ),
        )

    fun deleteGroup(groupId: String, deleteChats: Boolean = true) {
        if (deleteChats) {
            val chatIds = chats.values.filter { it.groupId == groupId }.map { it.id }
            for (cid in chatIds) {
                messages.entries.removeIf { it.value.chatId == cid }
                chats.remove(cid)
            }
        }
        groups.remove(groupId)
    }

    fun groupsContaining(characterId: String): List<Group> =
        groups.values.filter { it.isMember(characterId) }

    fun removeCharacterFromGroups(characterId: String) {
        for (group in groups.values.toList()) {
            if (!group.isMember(characterId)) continue
            val members = group.members.filterNot { it == characterId }
            if (members.isEmpty()) {
                deleteGroup(group.id)
            } else {
                upsertGroup(
                    group.copy(members = members, muted = group.muted.filterNot { it == characterId }),
                )
            }
        }
    }

    fun countGroupChats(groupId: String): Int = chats.values.count { it.groupId == groupId }

    // ------------------------------------------------------------------ 世界书

    fun listWorldBooks(
        characterId: String = "",
        includeGlobal: Boolean = true,
        enabledOnly: Boolean = false,
    ): List<WorldBook> = books.values
        .filter { it.characterId == characterId || (includeGlobal && it.characterId.isEmpty()) }
        .filter { !enabledOnly || it.enabled }
        .sortedBy { it.sortOrder }

    fun allWorldBooks(): List<WorldBook> = books.values.sortedBy { it.sortOrder }

    fun getWorldBook(bookId: String): WorldBook? = books[bookId]

    fun upsertWorldBook(book: WorldBook): WorldBook { books[book.id] = book; return book }

    fun createWorldBook(characterId: String = "", name: String = ""): WorldBook =
        upsertWorldBook(WorldBook(name = name.ifEmpty { "未命名世界书" }, characterId = characterId))

    fun setWorldBookEnabled(bookId: String, enabled: Boolean) {
        books[bookId]?.let { books[bookId] = it.copy(enabled = enabled) }
    }

    fun renameWorldBook(bookId: String, name: String) {
        books[bookId]?.let { books[bookId] = it.copy(name = name) }
    }

    fun deleteWorldBook(bookId: String, deleteEntries: Boolean = true) {
        if (deleteEntries) entries.entries.removeIf { it.value.bookId == bookId }
        books.remove(bookId)
    }

    fun countWorldEntriesInBook(bookId: String): Int = entries.values.count { it.bookId == bookId }

    fun orphanEntries(): List<WorldEntry> =
        entries.values.filter { it.bookId.isEmpty() || !books.containsKey(it.bookId) }

    fun allWorldEntries(enabledOnly: Boolean = false): List<WorldEntry> =
        entries.values.filter { !enabledOnly || it.enabled }

    /**
     * 把散装条目收进一本「未分类」书（**按需生成，没有散装条目就不建书**）。
     * 每个归属（角色 / 全局）各一本，避免把不同角色的条目混在一起。
     */
    fun adoptOrphanEntries(name: String = "未分类"): List<WorldBook> {
        val orphans = orphanEntries()
        if (orphans.isEmpty()) return emptyList()
        val created = mutableListOf<WorldBook>()
        for ((owner, owned) in orphans.groupBy { it.characterId }) {
            var book = listWorldBooks(owner, includeGlobal = false).firstOrNull { it.name == name }
            if (book == null) {
                book = createWorldBook(owner, name)
                created += book
            }
            for (entry in owned) {
                entries[entry.id] = entry.copy(bookId = book.id, characterId = owner)
            }
        }
        return created
    }

    /** 某个角色可见的书里的条目（可只看某本、只看启用的）。 */
    fun listWorldEntries(
        characterId: String = "",
        includeGlobal: Boolean = true,
        bookId: String? = null,
        enabledOnly: Boolean = false,
    ): List<WorldEntry> {
        val visible = listWorldBooks(characterId, includeGlobal, enabledOnly).associateBy { it.id }
        return entries.values
            .filter { visible.containsKey(it.bookId) && (bookId == null || it.bookId == bookId) }
            .sortedWith(compareByDescending<WorldEntry> { visible.getValue(it.bookId).sortOrder }.thenByDescending { it.order })
    }

    fun getWorldEntry(entryId: String): WorldEntry? = entries[entryId]

    fun upsertWorldEntry(entry: WorldEntry): WorldEntry { entries[entry.id] = entry; return entry }

    fun deleteWorldEntry(entryId: String) { entries.remove(entryId) }

    fun clearWorldEntries(characterId: String) {
        entries.entries.removeIf { it.value.characterId == characterId }
    }

    fun countWorldEntries(characterId: String = ""): Int =
        entries.values.count { it.characterId == characterId }

    private fun nowSeconds(): Double = System.currentTimeMillis() / 1000.0
}
