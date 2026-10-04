package com.tavern.data

import com.tavern.domain.models.ApiSettings
import com.tavern.domain.models.Character
import com.tavern.domain.models.Chat
import com.tavern.domain.models.Folder
import com.tavern.domain.models.Group
import com.tavern.domain.models.Message
import com.tavern.domain.models.WorldBook
import com.tavern.domain.models.WorldEntry
import com.tavern.domain.theme.Appearance
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Room 版数据层：与 `InMemoryStore` 同一套方法，界面只依赖这些方法。
 *
 * 所以「内存实现跑快速单测、Room 实现跑真机」两条腿都成立，行为一致。
 * 复杂字段用 JSON 列存取，映射都在这里。
 */
class RoomStore(private val db: TavernDatabase) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    // ------------------------------------------------------------------ JSON 小工具

    private fun listJson(values: List<String>): String =
        json.encodeToString(JsonArray.serializer(), JsonArray(values.map { JsonPrimitive(it) }))

    private fun jsonList(text: String?): List<String> {
        if (text.isNullOrBlank()) return emptyList()
        return try {
            (json.parseToJsonElement(text) as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
                ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun mapJson(map: Map<String, Any?>): String {
        fun toJson(value: Any?): JsonElement = when (value) {
            null -> JsonPrimitive("")
            is Boolean -> JsonPrimitive(value)
            is Number -> JsonPrimitive(value)
            is String -> JsonPrimitive(value)
            is Map<*, *> -> buildJsonObject { value.forEach { (k, v) -> if (k is String) put(k, toJson(v)) } }
            is List<*> -> JsonArray(value.map { toJson(it) })
            else -> JsonPrimitive(value.toString())
        }
        return json.encodeToString(JsonObject.serializer(), buildJsonObject { map.forEach { (k, v) -> put(k, toJson(v)) } })
    }

    private fun jsonMap(text: String?): Map<String, Any?> {
        if (text.isNullOrBlank()) return emptyMap()
        return try {
            val obj = json.parseToJsonElement(text) as? JsonObject ?: return emptyMap()
            obj.mapValues { (_, value) -> plain(value) }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun plain(value: JsonElement?): Any? = when (value) {
        null -> null
        is JsonPrimitive -> when {
            value.isString -> value.content
            value.content == "true" -> true
            value.content == "false" -> false
            else -> value.content.toDoubleOrNull() ?: value.content
        }
        is JsonObject -> value.mapValues { (_, v) -> plain(v) }
        is JsonArray -> value.map { plain(it) }
    }

    private fun intMap(text: String?): Map<String, Int> =
        jsonMap(text).mapNotNull { (k, v) -> (v as? Number)?.toInt()?.let { k to it } }.toMap()

    // ------------------------------------------------------------------ 设置 / 外观

    fun loadSettings(): ApiSettings {
        val raw = db.kvDao().get(KEY_SETTINGS) ?: return ApiSettings()
        return try {
            json.decodeFromString(ApiSettings.serializer(), raw)
        } catch (_: Exception) {
            ApiSettings()
        }
    }

    fun saveSettings(settings: ApiSettings) {
        db.kvDao().put(KvEntity(KEY_SETTINGS, json.encodeToString(ApiSettings.serializer(), settings)))
    }

    /** 通用小配置读写：置顶这类轻量状态用它，避免加列 + 数据库迁移。 */
    fun readKv(key: String): String? = db.kvDao().get(key)

    fun writeKv(key: String, value: String) {
        db.kvDao().put(KvEntity(key, value))
    }

    fun loadAppearance(): Appearance {
        val raw = db.kvDao().get(KEY_APPEARANCE) ?: return Appearance()
        return try {
            json.decodeFromString(Appearance.serializer(), raw)
        } catch (_: Exception) {
            Appearance()
        }
    }

    fun saveAppearance(appearance: Appearance) {
        db.kvDao().put(KvEntity(KEY_APPEARANCE, json.encodeToString(Appearance.serializer(), appearance)))
    }

    // ------------------------------------------------------------------ 文件夹

    private fun folderOf(e: FolderEntity) = Folder(
        id = e.id, name = e.name, kind = e.kind, parentId = e.parentId,
        sortOrder = e.sortOrder.toInt(), createdAt = e.createdAt, updatedAt = e.updatedAt,
    )

    private fun folderEntity(f: Folder) = FolderEntity(
        id = f.id, name = f.name, kind = f.kind, parentId = f.parentId,
        sortOrder = f.sortOrder.toLong(), createdAt = f.createdAt, updatedAt = f.updatedAt,
    )

    fun listFolders(kind: String = "character"): List<Folder> = db.folderDao().list(kind).map { folderOf(it) }
    fun getFolder(id: String): Folder? = db.folderDao().get(id)?.let { folderOf(it) }
    fun upsertFolder(folder: Folder): Folder { db.folderDao().upsert(folderEntity(folder)); return folder }
    /** 建一个文件夹（子文件夹传 parentId）。 */
    fun createFolder(name: String = "", kind: String = "character", parentId: String = ""): Folder =
        upsertFolder(
            Folder(
                name = name.ifEmpty { "新建文件夹" },
                kind = kind,
                parentId = parentId,
            ),
        )

    fun childrenOf(parentId: String, kind: String = "character"): List<Folder> =
        db.folderDao().children(parentId, kind).map { folderOf(it) }

    fun folderPath(folderId: String): List<Folder> {
        val path = mutableListOf<Folder>()
        var current = getFolder(folderId)
        val guard = mutableSetOf<String>()
        while (current != null && guard.add(current.id)) {
            path.add(0, current)
            current = getFolder(current.parentId)
        }
        return path
    }

    /** 删文件夹：子文件夹与角色/群聊上移到父级，不连带删除。 */
    fun deleteFolder(folderId: String) {
        val folder = getFolder(folderId) ?: return
        val parent = folder.parentId
        db.folderDao().reparentChildren(parent, folderId)
        if (folder.kind == "character") db.characterDao().reparent(parent, folderId)
        else db.groupDao().reparent(parent, folderId)
        db.folderDao().delete(folderId)
    }

    fun renameFolder(folderId: String, name: String) {
        getFolder(folderId)?.let { db.folderDao().upsert(folderEntity(it.copy(name = name))) }
    }

    fun countInFolder(folderId: String, kind: String = "character"): Int =
        if (kind == "character") db.characterDao().countInFolder(folderId)
        else db.groupDao().countInFolder(folderId)

    fun moveToFolder(itemId: String, folderId: String, kind: String = "character") {
        if (kind == "character") {
            db.characterDao().get(itemId)?.let { db.characterDao().upsert(it.copy(folderId = folderId)) }
        } else {
            db.groupDao().get(itemId)?.let { db.groupDao().upsert(it.copy(folderId = folderId)) }
        }
    }

    // ------------------------------------------------------------------ 角色

    private fun characterOf(e: CharacterEntity) = Character(
        id = e.id, name = e.name, folderId = e.folderId, description = e.description,
        personality = e.personality, scenario = e.scenario, firstMes = e.firstMes,
        mesExample = e.mesExample, systemPrompt = e.systemPrompt,
        postHistoryInstructions = e.postHistoryInstructions, creator = e.creator,
        creatorNotes = e.creatorNotes, tags = jsonList(e.tagsJson),
        alternateGreetings = jsonList(e.alternateGreetingsJson), avatarPath = e.avatarPath,
        raw = jsonMap(e.rawJson), createdAt = e.createdAt, updatedAt = e.updatedAt,
    )

    private fun characterEntity(c: Character) = CharacterEntity(
        id = c.id, name = c.name, folderId = c.folderId, description = c.description,
        personality = c.personality, scenario = c.scenario, firstMes = c.firstMes,
        mesExample = c.mesExample, systemPrompt = c.systemPrompt,
        postHistoryInstructions = c.postHistoryInstructions, creator = c.creator,
        creatorNotes = c.creatorNotes, tagsJson = listJson(c.tags),
        alternateGreetingsJson = listJson(c.alternateGreetings), avatarPath = c.avatarPath,
        rawJson = mapJson(c.raw), createdAt = c.createdAt, updatedAt = c.updatedAt,
        seq = c.createdAt.toLong(),
    )

    fun listCharacters(folderId: String? = null): List<Character> =
        (if (folderId == null) db.characterDao().listAll() else db.characterDao().listInFolder(folderId))
            .map { characterOf(it) }

    fun getCharacter(id: String): Character? = db.characterDao().get(id)?.let { characterOf(it) }
    fun upsertCharacter(char: Character): Character { db.characterDao().upsert(characterEntity(char)); return char }

    fun deleteCharacter(characterId: String) {
        for (chatId in db.chatDao().idsByCharacter(characterId)) db.messageDao().deleteByChat(chatId)
        db.chatDao().deleteByCharacter(characterId)
        db.worldEntryDao().deleteByCharacter(characterId)
        db.worldBookDao().deleteByCharacter(characterId)
        db.characterDao().delete(characterId)
        removeCharacterFromGroups(characterId)
    }

    // ------------------------------------------------------------------ 会话

    private fun chatOf(e: ChatEntity) = Chat(
        id = e.id, characterId = e.characterId, groupId = e.groupId, title = e.title,
        summary = e.summary, summaryUpto = e.summaryUpto, rhythmTime = e.rhythmTime,
        rhythmAuthority = e.rhythmAuthority, webSearch = e.webSearch, bookIds = jsonList(e.bookIdsJson),
        createdAt = e.createdAt, updatedAt = e.updatedAt,
    )

    private fun chatEntity(c: Chat) = ChatEntity(
        id = c.id, characterId = c.characterId, groupId = c.groupId, title = c.title,
        summary = c.summary, summaryUpto = c.summaryUpto, rhythmTime = c.rhythmTime,
        rhythmAuthority = c.rhythmAuthority, webSearch = c.webSearch,
        bookIdsJson = listJson(c.bookIds), createdAt = c.createdAt, updatedAt = c.updatedAt,
        seq = c.createdAt.toLong(),
    )

    fun listChats(characterId: String? = null, groupId: String? = null): List<Chat> = when {
        characterId != null -> db.chatDao().listByCharacter(characterId).map { chatOf(it) }
        groupId != null -> db.chatDao().listByGroup(groupId).map { chatOf(it) }
        else -> db.chatDao().listAll().map { chatOf(it) }
    }

    fun getChat(id: String): Chat? = db.chatDao().get(id)?.let { chatOf(it) }
    fun createChat(characterId: String, title: String = "", groupId: String = ""): Chat {
        val chat = Chat(characterId = characterId, title = title, groupId = groupId)
        db.chatDao().upsert(chatEntity(chat))
        return chat
    }

    fun primaryChat(characterId: String): Chat =
        listChats(characterId = characterId).firstOrNull() ?: createChat(characterId)

    fun primaryGroupChat(groupId: String, title: String = ""): Chat =
        listChats(groupId = groupId).firstOrNull() ?: createChat("", title, groupId = groupId)

    fun renameChat(chatId: String, title: String) {
        getChat(chatId)?.let { db.chatDao().upsert(chatEntity(it.copy(title = title, updatedAt = now()))) }
    }

    fun deleteChat(chatId: String) {
        db.messageDao().deleteByChat(chatId)
        db.chatDao().delete(chatId)
    }

    fun countMessages(chatId: String): Int = db.messageDao().count(chatId)
    fun firstUserMessage(chatId: String): String = db.messageDao().firstUser(chatId)?.content ?: ""

    fun autotitleChat(chatId: String, text: String) {
        val title = text.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ").take(14)
        if (title.isEmpty()) return
        db.chatDao().autotitle(chatId, title, now())
    }

    fun touch(chatId: String) = db.chatDao().touch(chatId, now())

    fun resetChat(chatId: String) = db.messageDao().deleteByChat(chatId)

    fun setChatBooks(chatId: String, bookIds: List<String>) {
        getChat(chatId)?.let { db.chatDao().upsert(chatEntity(it.copy(bookIds = bookIds, updatedAt = now()))) }
    }

    fun setChatSummary(chatId: String, summary: String, uptoId: String) {
        getChat(chatId)?.let {
            db.chatDao().upsert(chatEntity(it.copy(summary = summary, summaryUpto = uptoId, updatedAt = now())))
        }
    }

    fun setChatWebSearch(chatId: String, enabled: Boolean) {
        getChat(chatId)?.let { db.chatDao().upsert(chatEntity(it.copy(webSearch = enabled, updatedAt = now()))) }
    }

    fun setChatRhythm(chatId: String, timeGear: String, authority: String) {
        getChat(chatId)?.let {
            db.chatDao().upsert(chatEntity(it.copy(rhythmTime = timeGear, rhythmAuthority = authority, updatedAt = now())))
        }
    }

    fun characterOfChat(chatId: String): String? = getChat(chatId)?.characterId?.ifEmpty { null }

    // ------------------------------------------------------------------ 消息

    private fun messageOf(e: MessageEntity) = Message(
        id = e.id, chatId = e.chatId, role = e.role, speaker = e.speaker, content = e.content,
        reasoning = e.reasoning, variants = jsonList(e.variantsJson).toMutableList(),
        variantIndex = e.variantIndex, usage = intMap(e.usageJson), createdAt = e.createdAt,
    )

    private fun messageEntity(m: Message, seq: Long) = MessageEntity(
        id = m.id, chatId = m.chatId, role = m.role, speaker = m.speaker, content = m.content,
        reasoning = m.reasoning, variantsJson = listJson(m.variants), variantIndex = m.variantIndex,
        usageJson = mapJson(m.usage), createdAt = m.createdAt, seq = seq,
    )

    fun listMessages(chatId: String): List<Message> = db.messageDao().list(chatId).map { messageOf(it) }

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
            msg.variants = variants.toMutableList()
            msg.variantIndex = 0
        }
        msg.ensureVariants()
        db.messageDao().upsert(messageEntity(msg, db.messageDao().maxSeq() + 1))
        touch(chatId)
        return msg
    }

    fun saveMessage(msg: Message) {
        val existing = db.messageDao().get(msg.id)
        val seq = existing?.seq ?: (db.messageDao().maxSeq() + 1)
        db.messageDao().upsert(messageEntity(msg, seq))
    }

    fun updateMessage(messageId: String, content: String, reasoning: String? = null) {
        val entity = db.messageDao().get(messageId) ?: return
        val msg = messageOf(entity)
        msg.replaceCurrent(content)
        if (reasoning != null) msg.reasoning = reasoning
        db.messageDao().upsert(messageEntity(msg, entity.seq))
    }

    fun deleteMessage(messageId: String) = db.messageDao().delete(messageId)
    fun lastMessage(chatId: String): Message? = db.messageDao().last(chatId)?.let { messageOf(it) }

    fun branchChat(chatId: String, messageId: String, title: String = ""): Chat? {
        val source = getChat(chatId) ?: return null
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
                newChat.id, msg.role, msg.content, reasoning = msg.reasoning,
                variants = msg.variants.ifEmpty { null }, speaker = msg.speaker,
            )
            if (msg.variantIndex != 0) {
                copied.variantIndex = msg.variantIndex
                copied.ensureVariants()
                saveMessage(copied)
            }
        }
        return newChat
    }

    // ------------------------------------------------------------------ 群聊

    private fun groupOf(e: GroupEntity) = Group(
        id = e.id, name = e.name, folderId = e.folderId, members = jsonList(e.membersJson),
        muted = jsonList(e.mutedJson), createdAt = e.createdAt, updatedAt = e.updatedAt,
    )

    private fun groupEntity(g: Group) = GroupEntity(
        id = g.id, name = g.name, folderId = g.folderId, membersJson = listJson(g.members),
        mutedJson = listJson(g.muted), createdAt = g.createdAt, updatedAt = g.updatedAt,
        seq = g.createdAt.toLong(),
    )

    fun listGroups(folderId: String? = null): List<Group> =
        (if (folderId == null) db.groupDao().listAll() else db.groupDao().listInFolder(folderId)).map { groupOf(it) }

    fun getGroup(id: String): Group? = db.groupDao().get(id)?.let { groupOf(it) }
    fun upsertGroup(group: Group): Group { db.groupDao().upsert(groupEntity(group)); return group }

    fun createGroup(name: String, memberIds: List<String>, folderId: String = ""): Group =
        upsertGroup(
            Group(
                name = name.ifEmpty { "新群聊" }, members = memberIds, muted = memberIds,
                folderId = folderId,
            ),
        )

    fun deleteGroup(groupId: String, deleteChats: Boolean = true) {
        if (deleteChats) {
            for (chatId in db.chatDao().idsByGroup(groupId)) db.messageDao().deleteByChat(chatId)
            db.chatDao().deleteByGroup(groupId)
        }
        db.groupDao().delete(groupId)
    }

    fun groupsContaining(characterId: String): List<Group> =
        db.groupDao().containing(characterId).map { groupOf(it) }.filter { it.isMember(characterId) }

    fun removeCharacterFromGroups(characterId: String) {
        for (group in groupsContaining(characterId)) {
            val members = group.members.filterNot { it == characterId }
            if (members.isEmpty()) {
                deleteGroup(group.id)
            } else {
                upsertGroup(group.copy(members = members, muted = group.muted.filterNot { it == characterId }))
            }
        }
    }

    fun countGroupChats(groupId: String): Int = db.chatDao().countByGroup(groupId)

    // ------------------------------------------------------------------ 世界书

    private fun bookOf(e: WorldBookEntity) = WorldBook(
        id = e.id, name = e.name, characterId = e.characterId, enabled = e.enabled,
        sortOrder = e.sortOrder.toInt(), raw = jsonMap(e.rawJson),
        createdAt = e.createdAt, updatedAt = e.updatedAt,
    )

    private fun bookEntity(b: WorldBook) = WorldBookEntity(
        id = b.id, name = b.name, characterId = b.characterId, enabled = b.enabled,
        sortOrder = b.sortOrder.toLong(), rawJson = mapJson(b.raw),
        createdAt = b.createdAt, updatedAt = b.updatedAt,
    )

    fun listWorldBooks(
        characterId: String = "",
        includeGlobal: Boolean = true,
        enabledOnly: Boolean = false,
    ): List<WorldBook> = db.worldBookDao().listVisible(characterId, includeGlobal, enabledOnly).map { bookOf(it) }

    fun allWorldBooks(): List<WorldBook> = db.worldBookDao().listAll().map { bookOf(it) }
    fun getWorldBook(id: String): WorldBook? = db.worldBookDao().get(id)?.let { bookOf(it) }
    fun upsertWorldBook(book: WorldBook): WorldBook { db.worldBookDao().upsert(bookEntity(book)); return book }

    fun createWorldBook(characterId: String = "", name: String = ""): WorldBook =
        upsertWorldBook(WorldBook(name = name.ifEmpty { "未命名世界书" }, characterId = characterId))

    fun setWorldBookEnabled(bookId: String, enabled: Boolean) = db.worldBookDao().setEnabled(bookId, enabled)
    fun renameWorldBook(bookId: String, name: String) {
        getWorldBook(bookId)?.let { db.worldBookDao().upsert(bookEntity(it.copy(name = name))) }
    }

    fun deleteWorldBook(bookId: String, deleteEntries: Boolean = true) {
        if (deleteEntries) db.worldEntryDao().deleteByBook(bookId)
        db.worldBookDao().delete(bookId)
    }

    fun countWorldEntriesInBook(bookId: String): Int = db.worldEntryDao().countInBook(bookId)

    private fun entryOf(e: WorldEntryEntity) = WorldEntry(
        id = e.id, characterId = e.characterId, bookId = e.bookId, comment = e.comment,
        keys = jsonList(e.keysJson), keySecondary = jsonList(e.keySecondaryJson), content = e.content,
        constant = e.constant, selective = e.selective, selectiveLogic = e.selectiveLogic,
        order = e.entryOrder, position = e.position, depth = e.depth, group = e.groupName,
        groupWeight = e.groupWeight, role = e.role, enabled = e.enabled, probability = e.probability,
        useProbability = e.useProbability, caseSensitive = e.caseSensitive,
        matchWholeWords = e.matchWholeWords, scanDepth = e.scanDepth,
        excludeRecursion = e.excludeRecursion, preventRecursion = e.preventRecursion,
        raw = jsonMap(e.rawJson), createdAt = e.createdAt, updatedAt = e.updatedAt,
    )

    private fun entryEntity(w: WorldEntry) = WorldEntryEntity(
        id = w.id, characterId = w.characterId, bookId = w.bookId, comment = w.comment,
        keysJson = listJson(w.keys), keySecondaryJson = listJson(w.keySecondary), content = w.content,
        constant = w.constant, selective = w.selective, selectiveLogic = w.selectiveLogic,
        entryOrder = w.order, position = w.position, depth = w.depth, groupName = w.group,
        groupWeight = w.groupWeight, role = w.role, enabled = w.enabled, probability = w.probability,
        useProbability = w.useProbability, caseSensitive = w.caseSensitive,
        matchWholeWords = w.matchWholeWords, scanDepth = w.scanDepth,
        excludeRecursion = w.excludeRecursion, preventRecursion = w.preventRecursion,
        rawJson = mapJson(w.raw), createdAt = w.createdAt, updatedAt = w.updatedAt,
    )

    fun orphanEntries(): List<WorldEntry> = db.worldEntryDao().listOrphans().map { entryOf(it) }
    fun allWorldEntries(enabledOnly: Boolean = false): List<WorldEntry> =
        db.worldEntryDao().listAll().map { entryOf(it) }.filter { !enabledOnly || it.enabled }

    /** 把散装条目收进「未分类」书（按需生成，没有散装条目就不建书）。 */
    fun adoptOrphanEntries(name: String = "未分类"): List<WorldBook> {
        val orphans = orphanEntries()
        if (orphans.isEmpty()) return emptyList()
        val created = mutableListOf<WorldBook>()
        for ((owner, owned) in orphans.groupBy { it.characterId }) {
            var book = db.worldBookDao().findByName(owner, name)?.let { bookOf(it) }
            if (book == null) {
                book = createWorldBook(owner, name)
                created += book
            }
            for (entry in owned) db.worldEntryDao().moveToBook(entry.id, book.id, owner)
        }
        return created
    }

    fun listWorldEntries(
        characterId: String = "",
        includeGlobal: Boolean = true,
        bookId: String? = null,
        enabledOnly: Boolean = false,
    ): List<WorldEntry> {
        val visible = listWorldBooks(characterId, includeGlobal, enabledOnly).associateBy { it.id }
        val entries = if (bookId != null) db.worldEntryDao().listByBook(bookId) else db.worldEntryDao().listAll()
        return entries.map { entryOf(it) }
            .filter { visible.containsKey(it.bookId) }
            .sortedWith(
                compareByDescending<WorldEntry> { visible.getValue(it.bookId).sortOrder }
                    .thenByDescending { it.order },
            )
    }

    fun getWorldEntry(id: String): WorldEntry? = db.worldEntryDao().get(id)?.let { entryOf(it) }
    fun upsertWorldEntry(entry: WorldEntry): WorldEntry { db.worldEntryDao().upsert(entryEntity(entry)); return entry }
    fun deleteWorldEntry(entryId: String) = db.worldEntryDao().delete(entryId)
    fun clearWorldEntries(characterId: String) = db.worldEntryDao().deleteByCharacter(characterId)

    fun countWorldEntries(characterId: String = ""): Int = db.worldEntryDao().listByCharacter(characterId).size

    private fun now(): Double = System.currentTimeMillis() / 1000.0

    companion object {
        const val KEY_SETTINGS = "settings"
        const val KEY_APPEARANCE = "appearance"
    }
}
