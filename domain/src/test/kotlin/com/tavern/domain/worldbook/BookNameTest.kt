package com.tavern.domain.worldbook

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

/** 导入世界书时的书名识别（不同工具字段名不一样）。 */
class BookNameTest {

    private fun json(text: String) = Json.parseToJsonElement(text)

    @Test
    fun readsCommonFields() {
        assertEquals("我的书", Worldbook.bookName(json("""{"name":"我的书"}""")))
        assertEquals("我的书", Worldbook.bookName(json("""{"book_name":"我的书"}""")))
        assertEquals("我的书", Worldbook.bookName(json("""{"title":"我的书"}""")))
        assertEquals("我的书", Worldbook.bookName(json("""{"world_name":"我的书"}""")))
    }

    @Test
    fun readsNestedForms() {
        assertEquals("卡里的书", Worldbook.bookName(json("""{"character_book":{"name":"卡里的书"}}""")))
        assertEquals("卡里的书", Worldbook.bookName(json("""{"data":{"character_book":{"name":"卡里的书"}}}""")))
        assertEquals("v2 书", Worldbook.bookName(json("""{"data":{"name":"v2 书"}}""")))
    }

    @Test
    fun fallsBackWhenMissing() {
        assertEquals("导入的世界书", Worldbook.bookName(json("""{"entries":{}}""")))
        assertEquals("文件名", Worldbook.bookName(json("""{"entries":{}}"""), fallback = "文件名"))
        assertEquals("导入的世界书", Worldbook.bookName(null))
    }
}
