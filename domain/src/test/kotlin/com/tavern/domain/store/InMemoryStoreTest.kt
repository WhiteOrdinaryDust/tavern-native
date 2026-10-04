package com.tavern.domain.store

import com.tavern.domain.models.Character
import com.tavern.domain.models.Group
import com.tavern.domain.models.WorldBook
import com.tavern.domain.models.WorldEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 对应 Flet 版 `tavern/store.py` 的行为（表结构与 SQL 由 Room 实现，语义在这里锁住）。 */
class InMemoryStoreTest {

    private fun store() = InMemoryStore()

    private fun twoCharacters(store: InMemoryStore): Pair<Character, Character> {
        val a = store.upsertCharacter(Character(name = "林月"))
        val b = store.upsertCharacter(Character(name = "阿伟"))
        return a to b
    }

    // ------------------------------------------------------------------ 文件夹

    @Test
    fun folderTreeAndPath() {
        val store = store()
        val root = store.createFolder("人物")
        val child = store.createFolder("配角", parentId = root.id)
        assertEquals(listOf(child.id), store.childrenOf(root.id).map { it.id })
        assertEquals(listOf(root.name, child.name), store.folderPath(child.id).map { it.name })
        assertEquals(emptyList(), store.folderPath("不存在"))
    }

    @Test
    fun deleteFolderReparentsInsteadOfDeleting() {
        val store = store()
        val parent = store.createFolder("人物")
        val mid = store.createFolder("旧分类", parentId = parent.id)
        val child = store.createFolder("子分类", parentId = mid.id)
        val char = store.upsertCharacter(Character(name = "林月", folderId = mid.id))
        // 群聊有自己的文件夹树（kind="group"）
        val groupRoot = store.createFolder("群聊", kind = "group")
        val groupMid = store.createFolder("旧群分类", kind = "group", parentId = groupRoot.id)
        val group = store.createGroup("群", listOf(char.id), folderId = groupMid.id)

        store.deleteFolder(mid.id)
        store.deleteFolder(groupMid.id)

        assertEquals(parent.id, store.getFolder(child.id)!!.parentId, "子文件夹上移")
        assertEquals(parent.id, store.getCharacter(char.id)!!.folderId, "角色上移到父级而不是被删")
        assertEquals(groupRoot.id, store.getGroup(group.id)!!.folderId, "群聊同理")
        assertNull(store.getFolder(mid.id))
        assertEquals(1, store.countInFolder(parent.id), "角色算在父级里")
        assertEquals(1, store.countInFolder(groupRoot.id, kind = "group"))
    }

    @Test
    fun moveAndCount() {
        val store = store()
        val a = store.createFolder("A")
        val b = store.createFolder("B")
        val char = store.upsertCharacter(Character(name = "林月"))
        assertEquals(0, store.countInFolder(a.id))
        store.moveToFolder(char.id, a.id)
        assertEquals(1, store.countInFolder(a.id))
        store.moveToFolder(char.id, b.id)
        assertEquals(0, store.countInFolder(a.id))
        assertEquals(1, store.countInFolder(b.id))
        store.renameFolder(b.id, "B2")
        assertEquals("B2", store.getFolder(b.id)!!.name)
    }

    // ------------------------------------------------------------------ 角色 / 会话 / 消息

    @Test
    fun charactersListAndDeleteCascade() {
        val store = store()
        val (a, _) = twoCharacters(store)
        assertEquals(2, store.listCharacters().size)
        val chat = store.createChat(a.id)
        store.addMessage(chat.id, "user", "你好")
        val book = store.createWorldBook(a.id, "林月的书")
        store.upsertWorldEntry(WorldEntry(bookId = book.id, characterId = a.id, keys = listOf("k"), content = "c"))

        store.deleteCharacter(a.id)

        assertNull(store.getCharacter(a.id))
        assertNull(store.getChat(chat.id))
        assertEquals(0, store.countMessages(chat.id))
        assertNull(store.getWorldBook(book.id))
        assertEquals(0, store.countWorldEntries(a.id))
    }

    @Test
    fun primaryChatIsStableAndGroupChatSeparate() {
        val store = store()
        val (a, _) = twoCharacters(store)
        val first = store.primaryChat(a.id)
        assertEquals(first.id, store.primaryChat(a.id).id, "已有会话就复用")
        val group = store.createGroup("群", listOf(a.id))
        val groupChat = store.primaryGroupChat(group.id, title = "群聊")
        assertEquals(groupChat.id, store.primaryGroupChat(group.id).id)
        assertTrue(store.listChats(characterId = a.id).none { it.groupId == group.id }, "群会话不出现在角色的会话列表")
    }

    @Test
    fun messagesKeepOrderAndLastAndFirstUser() {
        val store = store()
        val (a, _) = twoCharacters(store)
        val chat = store.createChat(a.id)
        val m1 = store.addMessage(chat.id, "user", "第一句")
        val m2 = store.addMessage(chat.id, "assistant", "第二句")
        val m3 = store.addMessage(chat.id, "user", "第三句")
        assertEquals(listOf(m1.id, m2.id, m3.id), store.listMessages(chat.id).map { it.id })
        assertEquals("第三句", store.lastMessage(chat.id)!!.text)
        assertEquals("第一句", store.firstUserMessage(chat.id))
        assertEquals(3, store.countMessages(chat.id))

        store.updateMessage(m2.id, "改过的")
        assertEquals("改过的", store.listMessages(chat.id)[1].text)
        store.deleteMessage(m3.id)
        assertEquals(2, store.countMessages(chat.id))
        assertEquals("改过的", store.lastMessage(chat.id)!!.text)
    }

    @Test
    fun addMessageKeepsVariantsAndUsage() {
        val store = store()
        val (a, _) = twoCharacters(store)
        val chat = store.createChat(a.id)
        val msg = store.addMessage(
            chat.id, "assistant", "候选二",
            reasoning = "想过",
            variants = listOf("候选一", "候选二"),
            speaker = "s1",
            usage = mapOf("completion_tokens" to 7),
        )
        assertEquals(2, msg.variantCount)
        assertEquals(0, msg.variantIndex)
        assertEquals("候选一", msg.text)
        assertEquals("s1", msg.speaker)
        assertEquals(7, msg.usage["completion_tokens"])
        val reloaded = store.lastMessage(chat.id)!!
        assertEquals(2, reloaded.variantCount)
        assertEquals("想过", reloaded.reasoning)
    }

    @Test
    fun chatsSortedByRecentActivity() {
        val store = store()
        val (a, _) = twoCharacters(store)
        val old = store.createChat(a.id, "旧会话")
        val fresh = store.createChat(a.id, "新会话")
        assertEquals(fresh.id, store.listChats(characterId = a.id).first().id)
        store.addMessage(old.id, "user", "刚说了一句")
        assertEquals(old.id, store.listChats(characterId = a.id).first().id, "发消息后要排到最前")
    }

    @Test
    fun autotitleOnlyWhenEmpty() {
        val store = store()
        val (a, _) = twoCharacters(store)
        val chat = store.createChat(a.id)
        store.autotitleChat(chat.id, "  今天   天气\n不错  ")
        assertEquals("今天 天气 不错", store.getChat(chat.id)!!.title, "空白折叠成单空格")

        val long = store.createChat(a.id)
        store.autotitleChat(long.id, "一二三四五六七八九十一二三四五六七八")
        assertEquals(14, store.getChat(long.id)!!.title.length, "最多 14 个字")

        val named = store.createChat(a.id, "已有标题")
        store.autotitleChat(named.id, "不该覆盖")
        assertEquals("已有标题", store.getChat(named.id)!!.title)
        val empty = store.createChat(a.id)
        store.autotitleChat(empty.id, "   ")
        assertEquals("", store.getChat(empty.id)!!.title)
    }

    @Test
    fun branchChatCopiesUpToMessage() {
        val store = store()
        val (a, _) = twoCharacters(store)
        val chat = store.createChat(a.id, "原会话")
        val m1 = store.addMessage(chat.id, "user", "第一句")
        val m2 = store.addMessage(chat.id, "assistant", "第二句")
        store.addMessage(chat.id, "user", "第三句")

        val branch = assertNotNull(store.branchChat(chat.id, m2.id))
        assertEquals("分支 · 原会话", branch.title)
        assertEquals(a.id, branch.characterId)
        assertEquals(listOf("第一句", "第二句"), store.listMessages(branch.id).map { it.text })
        assertEquals(listOf("第一句", "第二句", "第三句"), store.listMessages(chat.id).map { it.text }, "原会话不受影响")

        assertNull(store.branchChat(chat.id, "没有这条消息"))
        assertNull(store.branchChat("没有这个会话", m1.id))
    }

    @Test
    fun branchChatKeepsSelectedVariant() {
        val store = store()
        val (a, _) = twoCharacters(store)
        val chat = store.createChat(a.id)
        val msg = store.addMessage(chat.id, "assistant", "一", variants = listOf("一", "二", "三"))
        msg.variantIndex = 2
        msg.ensureVariants()
        store.saveMessage(msg)
        val branch = assertNotNull(store.branchChat(chat.id, msg.id))
        val copied = store.listMessages(branch.id).single()
        assertEquals(3, copied.variantCount)
        assertEquals(2, copied.variantIndex)
        assertEquals("三", copied.text)
    }

    @Test
    fun chatFlagsAndReset() {
        val store = store()
        val (a, _) = twoCharacters(store)
        val chat = store.createChat(a.id)
        store.setChatBooks(chat.id, listOf("b1", "b2"))
        store.setChatSummary(chat.id, "前情", "m5")
        store.setChatWebSearch(chat.id, true)
        store.setChatRhythm(chat.id, "act", "director")
        val loaded = store.getChat(chat.id)!!
        assertEquals(listOf("b1", "b2"), loaded.bookIds)
        assertEquals("前情", loaded.summary)
        assertEquals("m5", loaded.summaryUpto)
        assertTrue(loaded.webSearch)
        assertEquals("act", loaded.rhythmTime)
        assertEquals("director", loaded.rhythmAuthority)
        assertEquals(a.id, store.characterOfChat(chat.id))

        store.addMessage(chat.id, "user", "一句话")
        store.resetChat(chat.id)
        assertEquals(0, store.countMessages(chat.id))
        store.deleteChat(chat.id)
        assertNull(store.getChat(chat.id))
    }

    // ------------------------------------------------------------------ 群聊

    @Test
    fun newGroupStartsFullyMuted() {
        val store = store()
        val (a, b) = twoCharacters(store)
        val group = store.createGroup("三人行", listOf(a.id, b.id))
        assertEquals(listOf(a.id, b.id), group.members)
        assertEquals(listOf(a.id, b.id), group.muted, "新群默认全员禁言，解禁谁谁出场")
        assertEquals(emptyList(), group.activeMembers())
    }

    @Test
    fun removeCharacterFromGroupsDeletesEmptyGroup() {
        val store = store()
        val (a, b) = twoCharacters(store)
        val group = store.upsertGroup(Group(name = "群", members = listOf(a.id, b.id), muted = listOf(a.id)))
        assertEquals(1, store.groupsContaining(a.id).size)
        store.removeCharacterFromGroups(a.id)
        val after = store.getGroup(group.id)!!
        assertEquals(listOf(b.id), after.members)
        assertEquals(emptyList(), after.muted, "被删成员的禁言记录也要清掉")

        store.removeCharacterFromGroups(b.id)
        assertNull(store.getGroup(group.id), "空群直接删掉")
    }

    @Test
    fun deleteGroupCascadesChats() {
        val store = store()
        val (a, _) = twoCharacters(store)
        val group = store.createGroup("群", listOf(a.id))
        val chat = store.createChat("", "群聊", groupId = group.id)
        store.addMessage(chat.id, "user", "在吗")
        assertEquals(1, store.countGroupChats(group.id))
        store.deleteGroup(group.id)
        assertNull(store.getGroup(group.id))
        assertNull(store.getChat(chat.id))
        assertEquals(0, store.countGroupChats(group.id))
    }

    // ------------------------------------------------------------------ 世界书

    @Test
    fun worldBookVisibility() {
        val store = store()
        val (a, b) = twoCharacters(store)
        val global = store.createWorldBook("", "全局书")
        val own = store.createWorldBook(a.id, "林月的书")
        val other = store.createWorldBook(b.id, "阿伟的书")
        val disabled = store.upsertWorldBook(WorldBook(name = "关掉的书", characterId = a.id, enabled = false))

        assertEquals(listOf(global.id, own.id, disabled.id), store.listWorldBooks(a.id).map { it.id })
        assertEquals(listOf(global.id, own.id), store.listWorldBooks(a.id, enabledOnly = true).map { it.id })
        assertFalse(store.listWorldBooks(a.id).any { it.id == other.id })
        assertEquals(listOf(global.id, own.id, other.id, disabled.id), store.allWorldBooks().map { it.id })
    }

    @Test
    fun adoptOrphansCreatesUncategorizedPerOwner() {
        val store = store()
        val (a, b) = twoCharacters(store)
        val globalBook = store.createWorldBook("", "全局书")
        // 一开始没有散装条目：不该建书
        assertEquals(emptyList(), store.adoptOrphanEntries())
        assertTrue(store.allWorldBooks().none { it.name == "未分类" })

        store.upsertWorldEntry(WorldEntry(characterId = "", keys = listOf("k1"), content = "全局散装"))
        store.upsertWorldEntry(WorldEntry(characterId = a.id, keys = listOf("k2"), content = "林月散装"))
        store.upsertWorldEntry(WorldEntry(characterId = a.id, keys = listOf("k3"), content = "林月散装二"))
        // 已经归书的条目不算散装
        store.upsertWorldEntry(WorldEntry(bookId = globalBook.id, characterId = "", keys = listOf("k4"), content = "已归书"))

        val created = store.adoptOrphanEntries()
        assertEquals(2, created.size, "每个归属各一本")
        assertEquals(setOf("", a.id), created.map { it.characterId }.toSet())
        assertEquals(emptyList(), store.orphanEntries())
        assertEquals(2, store.countWorldEntriesInBook(created.first { it.characterId == a.id }.id))
        // 再调一次不会重复建书
        store.upsertWorldEntry(WorldEntry(characterId = a.id, keys = listOf("k5"), content = "新的散装"))
        assertEquals(0, store.adoptOrphanEntries().size, "已经有一本「未分类」就不再建")
        assertEquals(1, store.allWorldBooks().count { it.name == "未分类" && it.characterId == a.id })
    }

    @Test
    fun worldEntriesSortedByBookThenOrder() {
        val store = store()
        val (a, _) = twoCharacters(store)
        val low = store.upsertWorldBook(WorldBook(name = "低优先", characterId = a.id, sortOrder = 100))
        val high = store.upsertWorldBook(WorldBook(name = "高优先", characterId = a.id, sortOrder = 900))
        store.upsertWorldEntry(WorldEntry(bookId = low.id, keys = listOf("a"), content = "低·小", order = 10))
        store.upsertWorldEntry(WorldEntry(bookId = low.id, keys = listOf("b"), content = "低·大", order = 500))
        store.upsertWorldEntry(WorldEntry(bookId = high.id, keys = listOf("c"), content = "高·小", order = 10))
        store.upsertWorldEntry(WorldEntry(bookId = high.id, keys = listOf("d"), content = "高·中", order = 300))

        assertEquals(
            listOf("高·中", "高·小", "低·大", "低·小"),
            store.listWorldEntries(a.id).map { it.content },
            "先按书的优先级倒序，再按条目 order 倒序",
        )
        assertEquals(listOf("高·中", "高·小"), store.listWorldEntries(a.id, bookId = high.id).map { it.content })
        // 全局书里的条目对每个角色都可见
        val global = store.createWorldBook("", "全局")
        store.upsertWorldEntry(WorldEntry(bookId = global.id, keys = listOf("e"), content = "全局条目"))
        assertTrue(store.listWorldEntries(a.id).any { it.content == "全局条目" })
        assertFalse(store.listWorldEntries(a.id, includeGlobal = false).any { it.content == "全局条目" })
    }

    @Test
    fun entryCrudAndBookDeletion() {
        val store = store()
        val (a, _) = twoCharacters(store)
        val book = store.createWorldBook(a.id, "书")
        val entry = store.upsertWorldEntry(WorldEntry(bookId = book.id, characterId = a.id, keys = listOf("k"), content = "c"))
        assertEquals(1, store.countWorldEntriesInBook(book.id))
        assertEquals(entry.id, store.getWorldEntry(entry.id)!!.id)
        store.renameWorldBook(book.id, "改名了")
        assertEquals("改名了", store.getWorldBook(book.id)!!.name)
        store.setWorldBookEnabled(book.id, false)
        assertFalse(store.getWorldBook(book.id)!!.enabled)

        store.deleteWorldBook(book.id)
        assertNull(store.getWorldBook(book.id))
        assertNull(store.getWorldEntry(entry.id), "默认连带删条目")

        val book2 = store.createWorldBook(a.id, "书2")
        store.upsertWorldEntry(WorldEntry(bookId = book2.id, characterId = a.id, keys = listOf("k"), content = "c"))
        store.deleteWorldBook(book2.id, deleteEntries = false)
        assertEquals(1, store.allWorldEntries().size, "不连带删时条目变成散装")

        store.clearWorldEntries(a.id)
        assertEquals(0, store.countWorldEntries(a.id))
    }
}
