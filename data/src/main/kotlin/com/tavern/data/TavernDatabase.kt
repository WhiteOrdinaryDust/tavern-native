package com.tavern.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/** 数据库：表结构与 Flet 版一致（复杂字段走 JSON 列）。 */
@Database(
    entities = [
        KvEntity::class,
        FolderEntity::class,
        CharacterEntity::class,
        GroupEntity::class,
        ChatEntity::class,
        MessageEntity::class,
        WorldBookEntity::class,
        WorldEntryEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class TavernDatabase : RoomDatabase() {

    abstract fun kvDao(): KvDao
    abstract fun folderDao(): FolderDao
    abstract fun characterDao(): CharacterDao
    abstract fun groupDao(): GroupDao
    abstract fun chatDao(): ChatDao
    abstract fun messageDao(): MessageDao
    abstract fun worldBookDao(): WorldBookDao
    abstract fun worldEntryDao(): WorldEntryDao

    companion object {
        private const val NAME = "tavern.db"

        fun open(context: Context): TavernDatabase =
            Room.databaseBuilder(context.applicationContext, TavernDatabase::class.java, NAME)
                // 表结构第 1 版：以后的改动都写正式迁移（不做破坏性重建，免得丢数据）
                .build()

        fun openInMemory(context: Context): TavernDatabase =
            Room.inMemoryDatabaseBuilder(context.applicationContext, TavernDatabase::class.java)
                .allowMainThreadQueries()
                .build()
    }
}
