package com.tavern.domain.media

import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 对应 Flet 版 `tavern/media.py` 与背景取景框的行为。 */
class MediaTest {

    private fun write(name: String, bytes: ByteArray): String {
        val file = java.io.File.createTempFile("media-", "-$name")
        file.deleteOnExit()
        file.writeBytes(bytes)
        return file.absolutePath
    }

    private fun pngBytes(width: Int, height: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        out.write(ByteArray(8))  // 长度 + 类型
        for (value in listOf(width, height)) {
            out.write(byteArrayOf(
                ((value ushr 24) and 0xFF).toByte(), ((value ushr 16) and 0xFF).toByte(),
                ((value ushr 8) and 0xFF).toByte(), (value and 0xFF).toByte(),
            ))
        }
        out.write(ByteArray(8))
        return out.toByteArray()
    }

    @Test
    fun readsPngSize() {
        assertEquals(1080 to 2340, Media.imageSize(write("a.png", pngBytes(1080, 2340))))
    }

    @Test
    fun readsGifSize() {
        val out = ByteArrayOutputStream()
        out.write("GIF89a".toByteArray(Charsets.ISO_8859_1))
        out.write(byteArrayOf(0x40, 0x01, 0x2C, 0x01))  // 320 x 300（小端）
        out.write(ByteArray(8))
        assertEquals(320 to 300, Media.imageSize(write("a.gif", out.toByteArray())))
    }

    @Test
    fun readsJpegSize() {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0xFF.toByte(), 0xD8.toByte()))          // SOI
        out.write(byteArrayOf(0xFF.toByte(), 0xE0.toByte(), 0x00, 0x10))  // APP0，长度 16
        out.write(ByteArray(14))
        out.write(byteArrayOf(0xFF.toByte(), 0xC0.toByte(), 0x00, 0x11, 0x08))  // SOF0
        out.write(byteArrayOf(0x02, 0x58, 0x03, 0x20))                // 高 600，宽 800
        out.write(ByteArray(3 * 3 + 8))
        assertEquals(800 to 600, Media.imageSize(write("a.jpg", out.toByteArray())))
    }

    @Test
    fun fallsBackWhenUnknown() {
        assertEquals(DEFAULT_SIZE, Media.imageSize(write("x.txt", "不是图片".toByteArray())))
        assertEquals(DEFAULT_SIZE, Media.imageSize(null))
        assertEquals(DEFAULT_SIZE, Media.imageSize(""))
        assertEquals(DEFAULT_SIZE, Media.imageSize("D:\\不存在的文件.png"))
    }

    @Test
    fun cropBoxIsCoverAtZoomOne() {
        // 屏幕 1080x2340（高宽比 2.1667）；图 1080x1920 → 宽度被裁到 886.15
        val box = Media.cropBox(1080 to 1920, 1080 to 2340, 1.0, 0.5 to 0.5)
        assertEquals(886.1538, box.width, 0.01)
        assertEquals(1920.0, box.height, 0.01)
        assertEquals(96.92, box.left, 0.02)
        assertEquals(0.0, box.top, 0.02)
    }

    @Test
    fun cropBoxZoomShrinksBox() {
        val one = Media.cropBox(1080 to 1920, 1080 to 2340, 1.0, 0.5 to 0.5)
        val two = Media.cropBox(1080 to 1920, 1080 to 2340, 2.0, 0.5 to 0.5)
        assertEquals(one.width / 2, two.width, 0.01)
        assertEquals(one.height / 2, two.height, 0.01)
        // 越界缩放按 1 处理
        assertEquals(one.width, Media.cropBox(1080 to 1920, 1080 to 2340, 0.1, 0.5 to 0.5).width, 0.01)
    }

    @Test
    fun cropBoxCenterIsClampedInsideImage() {
        val box = Media.cropBox(1080 to 1920, 1080 to 2340, 2.0, 0.0 to 0.0)
        assertEquals(0.0, box.left, 0.01, "拖到最左上时不能越出图片")
        assertEquals(0.0, box.top, 0.01)
        val right = Media.cropBox(1080 to 1920, 1080 to 2340, 2.0, 1.0 to 1.0)
        assertEquals(1080.0, right.left + right.width, 0.01)
        assertEquals(1920.0, right.top + right.height, 0.01)
    }

    @Test
    fun alignmentRoundTripsWithCenter() {
        val image = 1080 to 1920
        val box = Media.cropBox(image, 1080 to 2340, 2.0, 0.5 to 0.5)
        val size = box.width to box.height

        val center = Media.alignmentForCenter(image, size, 0.5 to 0.5)
        assertEquals(0.0, center.first, 0.001)
        assertEquals(0.0, center.second, 0.001)

        val leftTop = Media.alignmentForCenter(image, size, 0.0 to 0.0)
        assertEquals(-1.0, leftTop.first, 0.001, "拖到边上就是 -1")
        assertEquals(-1.0, leftTop.second, 0.001)

        // 往返：中心 → 对齐 → 中心
        val source = 0.3 to 0.7
        val back = Media.centerForAlignment(image, size, Media.alignmentForCenter(image, size, source))
        assertEquals(source.first, back.first, 0.001)
        assertEquals(source.second, back.second, 0.001)
    }

    @Test
    fun centerForAlignmentHandlesExtremes() {
        val image = 1080 to 1920
        val size = 400.0 to 800.0
        val low = Media.centerForAlignment(image, size, -1.0 to -1.0)
        assertEquals(400.0 / 2 / 1080, low.first, 0.001)
        val high = Media.centerForAlignment(image, size, 1.0 to 1.0)
        assertEquals((1080 - 400.0 / 2) / 1080, high.first, 0.001)
        // 越界的对齐值被夹回 -1~1
        assertEquals(high.first, Media.centerForAlignment(image, size, 9.0 to 9.0).first, 0.001)
    }

    @Test
    fun clamp01Defaults() {
        assertEquals(0.5, Media.clamp01(null))
        assertEquals(0.0, Media.clamp01(-3.0))
        assertEquals(1.0, Media.clamp01(3.0))
        assertEquals(0.25, Media.clamp01(0.25))
        assertTrue(DEFAULT_SIZE.first > 0)
        assertTrue(DEFAULT_SCREEN.second > DEFAULT_SCREEN.first, "兜底按竖屏算")
    }
}
