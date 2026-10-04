package com.tavern.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 表结构（对应 Flet 版 `store.py` 的 SQLite 表）。
 *
 * 复杂字段（标签、候选、用量、原始 JSON…）统一存 JSON 字符串，
 * 映射到领域模型的动作放在 `RoomStore` 里，避免 Room 类型转换器满天飞。
 * 所有带「顺序」意义的列都额外存 `seq`（自增），同一毫秒也不会乱序。
 */

@Entity(tableName = "kv")
data class KvEntity(
    @PrimaryKey val key: String,
    val value: String,
)

@Entity(
    tableName = "folders",
    indices = [Index(value = ["kind", "parent_id"])],
)
data class FolderEntity(
    @PrimaryKey val id: String,
    val name: String,
    val kind: String,
    @ColumnInfo(name = "parent_id") val parentId: String,
    @ColumnInfo(name = "sort_order") val sortOrder: Long,
    @ColumnInfo(name = "created_at") val createdAt: Double,
    @ColumnInfo(name = "updated_at") val updatedAt: Double,
)

@Entity(tableName = "characters")
data class CharacterEntity(
    @PrimaryKey val id: String,
    val name: String,
    @ColumnInfo(name = "folder_id") val folderId: String,
    val description: String,
    val personality: String,
    val scenario: String,
    @ColumnInfo(name = "first_mes") val firstMes: String,
    @ColumnInfo(name = "mes_example") val mesExample: String,
    @ColumnInfo(name = "system_prompt") val systemPrompt: String,
    @ColumnInfo(name = "post_history_instructions") val postHistoryInstructions: String,
    val creator: String,
    @ColumnInfo(name = "creator_notes") val creatorNotes: String,
    @ColumnInfo(name = "tags_json") val tagsJson: String,
    @ColumnInfo(name = "alternate_greetings_json") val alternateGreetingsJson: String,
    @ColumnInfo(name = "avatar_path") val avatarPath: String,
    @ColumnInfo(name = "raw_json") val rawJson: String,
    @ColumnInfo(name = "created_at") val createdAt: Double,
    @ColumnInfo(name = "updated_at") val updatedAt: Double,
    @ColumnInfo(name = "seq") val seq: Long = 0,
)

@Entity(tableName = "groups")
data class GroupEntity(
    @PrimaryKey val id: String,
    val name: String,
    @ColumnInfo(name = "folder_id") val folderId: String,
    @ColumnInfo(name = "members_json") val membersJson: String,
    @ColumnInfo(name = "muted_json") val mutedJson: String,
    @ColumnInfo(name = "created_at") val createdAt: Double,
    @ColumnInfo(name = "updated_at") val updatedAt: Double,
    @ColumnInfo(name = "seq") val seq: Long = 0,
)

@Entity(
    tableName = "chats",
    indices = [Index(value = ["character_id"]), Index(value = ["group_id"])],
)
data class ChatEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "character_id") val characterId: String,
    @ColumnInfo(name = "group_id") val groupId: String,
    val title: String,
    val summary: String,
    @ColumnInfo(name = "summary_upto") val summaryUpto: String,
    @ColumnInfo(name = "rhythm_time") val rhythmTime: String,
    @ColumnInfo(name = "rhythm_authority") val rhythmAuthority: String,
    @ColumnInfo(name = "web_search") val webSearch: Boolean,
    @ColumnInfo(name = "book_ids_json") val bookIdsJson: String,
    @ColumnInfo(name = "created_at") val createdAt: Double,
    @ColumnInfo(name = "updated_at") val updatedAt: Double,
    @ColumnInfo(name = "seq") val seq: Long = 0,
)

@Entity(
    tableName = "messages",
    indices = [Index(value = ["chat_id", "seq"])],
)
data class MessageEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "chat_id") val chatId: String,
    val role: String,
    val speaker: String,
    val content: String,
    val reasoning: String,
    @ColumnInfo(name = "variants_json") val variantsJson: String,
    @ColumnInfo(name = "variant_index") val variantIndex: Int,
    @ColumnInfo(name = "usage_json") val usageJson: String,
    @ColumnInfo(name = "created_at") val createdAt: Double,
    @ColumnInfo(name = "seq") val seq: Long,
)

@Entity(
    tableName = "world_books",
    indices = [Index(value = ["character_id"])],
)
data class WorldBookEntity(
    @PrimaryKey val id: String,
    val name: String,
    @ColumnInfo(name = "character_id") val characterId: String,
    val enabled: Boolean,
    @ColumnInfo(name = "sort_order") val sortOrder: Long,
    @ColumnInfo(name = "raw_json") val rawJson: String,
    @ColumnInfo(name = "created_at") val createdAt: Double,
    @ColumnInfo(name = "updated_at") val updatedAt: Double,
)

@Entity(
    tableName = "world_entries",
    indices = [Index(value = ["character_id"]), Index(value = ["book_id"])],
)
data class WorldEntryEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "character_id") val characterId: String,
    @ColumnInfo(name = "book_id") val bookId: String,
    val comment: String,
    @ColumnInfo(name = "keys_json") val keysJson: String,
    @ColumnInfo(name = "key_secondary_json") val keySecondaryJson: String,
    val content: String,
    val constant: Boolean,
    val selective: Boolean,
    @ColumnInfo(name = "selective_logic") val selectiveLogic: Int,
    @ColumnInfo(name = "entry_order") val entryOrder: Int,
    val position: Int,
    val depth: Int,
    @ColumnInfo(name = "group_name") val groupName: String,
    @ColumnInfo(name = "group_weight") val groupWeight: Int,
    val role: Int,
    val enabled: Boolean,
    val probability: Int,
    @ColumnInfo(name = "use_probability") val useProbability: Boolean,
    @ColumnInfo(name = "case_sensitive") val caseSensitive: Boolean?,
    @ColumnInfo(name = "match_whole_words") val matchWholeWords: Boolean?,
    @ColumnInfo(name = "scan_depth") val scanDepth: Int?,
    @ColumnInfo(name = "exclude_recursion") val excludeRecursion: Boolean,
    @ColumnInfo(name = "prevent_recursion") val preventRecursion: Boolean,
    @ColumnInfo(name = "raw_json") val rawJson: String,
    @ColumnInfo(name = "created_at") val createdAt: Double,
    @ColumnInfo(name = "updated_at") val updatedAt: Double,
)
