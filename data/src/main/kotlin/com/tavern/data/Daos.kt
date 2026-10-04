package com.tavern.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

/** kv：设置与外观（值为 JSON 字符串）。 */
@Dao
interface KvDao {
    @Query("SELECT value FROM kv WHERE key = :key")
    fun get(key: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun put(entry: KvEntity)
}

@Dao
interface FolderDao {
    @Query("SELECT * FROM folders WHERE kind = :kind ORDER BY sort_order, name")
    fun list(kind: String): List<FolderEntity>

    @Query("SELECT * FROM folders WHERE id = :id")
    fun get(id: String): FolderEntity?

    @Query("SELECT * FROM folders WHERE parent_id = :parentId AND kind = :kind ORDER BY sort_order, name")
    fun children(parentId: String, kind: String): List<FolderEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(folder: FolderEntity)

    @Query("UPDATE folders SET parent_id = :parentId WHERE parent_id = :folderId")
    fun reparentChildren(parentId: String, folderId: String)

    @Query("DELETE FROM folders WHERE id = :id")
    fun delete(id: String)

    @Query("SELECT COUNT(*) FROM folders WHERE parent_id = :parentId AND kind = :kind")
    fun countChildren(parentId: String, kind: String): Int
}

@Dao
interface CharacterDao {
    @Query("SELECT * FROM characters ORDER BY name, created_at")
    fun listAll(): List<CharacterEntity>

    @Query("SELECT * FROM characters WHERE folder_id = :folderId ORDER BY name, created_at")
    fun listInFolder(folderId: String): List<CharacterEntity>

    @Query("SELECT * FROM characters WHERE id = :id")
    fun get(id: String): CharacterEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(character: CharacterEntity)

    @Query("UPDATE characters SET folder_id = :folderId WHERE folder_id = :oldFolderId")
    fun reparent(folderId: String, oldFolderId: String)

    @Query("SELECT COUNT(*) FROM characters WHERE folder_id = :folderId")
    fun countInFolder(folderId: String): Int

    @Query("DELETE FROM characters WHERE id = :id")
    fun delete(id: String)
}

@Dao
interface GroupDao {
    @Query("SELECT * FROM groups ORDER BY name")
    fun listAll(): List<GroupEntity>

    @Query("SELECT * FROM groups WHERE folder_id = :folderId ORDER BY name")
    fun listInFolder(folderId: String): List<GroupEntity>

    @Query("SELECT * FROM groups WHERE id = :id")
    fun get(id: String): GroupEntity?

    @Query("SELECT * FROM groups WHERE members_json LIKE '%' || :characterId || '%'")
    fun containing(characterId: String): List<GroupEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(group: GroupEntity)

    @Query("UPDATE groups SET folder_id = :folderId WHERE folder_id = :oldFolderId")
    fun reparent(folderId: String, oldFolderId: String)

    @Query("SELECT COUNT(*) FROM groups WHERE folder_id = :folderId")
    fun countInFolder(folderId: String): Int

    @Query("DELETE FROM groups WHERE id = :id")
    fun delete(id: String)
}

@Dao
interface ChatDao {
    @Query("SELECT * FROM chats ORDER BY updated_at DESC, seq DESC")
    fun listAll(): List<ChatEntity>

    @Query("SELECT * FROM chats WHERE character_id = :characterId ORDER BY updated_at DESC, seq DESC")
    fun listByCharacter(characterId: String): List<ChatEntity>

    @Query("SELECT * FROM chats WHERE group_id = :groupId ORDER BY updated_at DESC, seq DESC")
    fun listByGroup(groupId: String): List<ChatEntity>

    @Query("SELECT * FROM chats WHERE id = :id")
    fun get(id: String): ChatEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(chat: ChatEntity)

    @Query("UPDATE chats SET title = :title, updated_at = :updatedAt WHERE id = :id AND TRIM(title) = ''")
    fun autotitle(id: String, title: String, updatedAt: Double)

    @Query("UPDATE chats SET updated_at = :updatedAt WHERE id = :id")
    fun touch(id: String, updatedAt: Double)

    @Query("DELETE FROM chats WHERE id = :id")
    fun delete(id: String)

    @Query("DELETE FROM chats WHERE character_id = :characterId")
    fun deleteByCharacter(characterId: String)

    @Query("DELETE FROM chats WHERE group_id = :groupId")
    fun deleteByGroup(groupId: String)

    @Query("SELECT id FROM chats WHERE character_id = :characterId")
    fun idsByCharacter(characterId: String): List<String>

    @Query("SELECT id FROM chats WHERE group_id = :groupId")
    fun idsByGroup(groupId: String): List<String>

    @Query("SELECT COUNT(*) FROM chats WHERE group_id = :groupId")
    fun countByGroup(groupId: String): Int
}

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE chat_id = :chatId ORDER BY seq")
    fun list(chatId: String): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE id = :id")
    fun get(id: String): MessageEntity?

    @Query("SELECT * FROM messages WHERE chat_id = :chatId ORDER BY seq DESC LIMIT 1")
    fun last(chatId: String): MessageEntity?

    @Query("SELECT * FROM messages WHERE chat_id = :chatId AND role = 'user' ORDER BY seq LIMIT 1")
    fun firstUser(chatId: String): MessageEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(message: MessageEntity)

    @Query("SELECT COALESCE(MAX(seq), 0) FROM messages")
    fun maxSeq(): Long

    @Query("SELECT COUNT(*) FROM messages WHERE chat_id = :chatId")
    fun count(chatId: String): Int

    @Query("DELETE FROM messages WHERE id = :id")
    fun delete(id: String)

    @Query("DELETE FROM messages WHERE chat_id = :chatId")
    fun deleteByChat(chatId: String)
}

@Dao
interface WorldBookDao {
    @Query("SELECT * FROM world_books ORDER BY sort_order, name")
    fun listAll(): List<WorldBookEntity>

    @Query(
        "SELECT * FROM world_books WHERE (character_id = :characterId OR (:includeGlobal AND character_id = '')) " +
            "AND (:enabledOnly = 0 OR enabled = 1) ORDER BY sort_order, name",
    )
    fun listVisible(characterId: String, includeGlobal: Boolean, enabledOnly: Boolean): List<WorldBookEntity>

    @Query("SELECT * FROM world_books WHERE character_id = :characterId ORDER BY sort_order, name")
    fun listOwned(characterId: String): List<WorldBookEntity>

    @Query("SELECT * FROM world_books WHERE id = :id")
    fun get(id: String): WorldBookEntity?

    @Query("SELECT * FROM world_books WHERE character_id = :characterId AND name = :name LIMIT 1")
    fun findByName(characterId: String, name: String): WorldBookEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(book: WorldBookEntity)

    @Query("UPDATE world_books SET enabled = :enabled WHERE id = :id")
    fun setEnabled(id: String, enabled: Boolean)

    @Query("DELETE FROM world_books WHERE id = :id")
    fun delete(id: String)

    @Query("DELETE FROM world_books WHERE character_id = :characterId")
    fun deleteByCharacter(characterId: String)

    @Query("SELECT COUNT(*) FROM world_books WHERE character_id = :characterId")
    fun countByCharacter(characterId: String): Int
}

@Dao
interface WorldEntryDao {
    @Query("SELECT * FROM world_entries ORDER BY entry_order DESC")
    fun listAll(): List<WorldEntryEntity>

    @Query("SELECT * FROM world_entries WHERE book_id = :bookId ORDER BY entry_order DESC")
    fun listByBook(bookId: String): List<WorldEntryEntity>

    @Query("SELECT * FROM world_entries WHERE character_id = :characterId ORDER BY entry_order DESC")
    fun listByCharacter(characterId: String): List<WorldEntryEntity>

    /** 散装条目：没归书，或归的书已经不在了。 */
    @Query(
        "SELECT * FROM world_entries WHERE book_id = '' OR book_id NOT IN (SELECT id FROM world_books) " +
            "ORDER BY created_at",
    )
    fun listOrphans(): List<WorldEntryEntity>

    @Query("SELECT * FROM world_entries WHERE book_id IN (:bookIds) ORDER BY entry_order DESC")
    fun listInBooks(bookIds: List<String>): List<WorldEntryEntity>

    @Query("SELECT * FROM world_entries WHERE id = :id")
    fun get(id: String): WorldEntryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(entry: WorldEntryEntity)

    @Query("UPDATE world_entries SET book_id = :bookId, character_id = :characterId WHERE id = :id")
    fun moveToBook(id: String, bookId: String, characterId: String)

    @Query("DELETE FROM world_entries WHERE id = :id")
    fun delete(id: String)

    @Query("DELETE FROM world_entries WHERE book_id = :bookId")
    fun deleteByBook(bookId: String)

    @Query("DELETE FROM world_entries WHERE character_id = :characterId")
    fun deleteByCharacter(characterId: String)

    @Query("SELECT COUNT(*) FROM world_entries WHERE book_id = :bookId")
    fun countInBook(bookId: String): Int
}
