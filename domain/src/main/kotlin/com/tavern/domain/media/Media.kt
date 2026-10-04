package com.tavern.domain.media

import java.io.File

/** 图片尺寸读不出来时的兜底（按竖屏算）。 */
val DEFAULT_SIZE = 1080 to 1920

/** 屏幕尺寸拿不到时的兜底（现代手机竖屏比例）。 */
val DEFAULT_SCREEN = 1080 to 2340

/** 取景区（单位：图片像素）。 */
data class CropBox(val left: Double, val top: Double, val width: Double, val height: Double)

/**
 * 图片相关的小工具：读像素尺寸、算「手机屏幕形状」的取景区。
 *
 * 只做纯解析（PNG/JPEG/GIF/WebP），不依赖图片库。
 * 与 Flet 版 `tavern/media.py` 行为一致（Pillow 那层兜底由界面用平台能力补）。
 */
object Media {

    private fun pngSize(data: ByteArray): Pair<Int, Int>? {
        if (data.size < 24) return null
        if (!data.copyOfRange(0, 8).contentEquals(byteArrayOf(
                0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            ))
        ) {
            return null
        }
        val width = beInt(data, 16)
        val height = beInt(data, 20)
        return width to height
    }

    private fun gifSize(data: ByteArray): Pair<Int, Int>? {
        if (data.size < 10) return null
        val head = String(data, 0, 6, Charsets.ISO_8859_1)
        if (head != "GIF87a" && head != "GIF89a") return null
        val width = leShort(data, 6)
        val height = leShort(data, 8)
        return width to height
    }

    /** 扫 SOFn 段拿宽高（不解码像素，够快）。 */
    private fun jpegSize(data: ByteArray): Pair<Int, Int>? {
        if (data.size < 2) return null
        if (data[0] != 0xFF.toByte() || data[1] != 0xD8.toByte()) return null
        var pos = 2
        val n = data.size
        while (pos + 9 < n) {
            if (data[pos] != 0xFF.toByte()) {
                pos += 1
                continue
            }
            val marker = data[pos + 1].toInt() and 0xFF
            if (marker == 0xD8 || marker == 0xD9 || marker in 0xD0..0xD7) {
                pos += 2
                continue
            }
            val length = beShort(data, pos + 2)
            if (marker in listOf(0xC0, 0xC1, 0xC2, 0xC3, 0xC5, 0xC6, 0xC7, 0xC9, 0xCA, 0xCB)) {
                val height = beShort(data, pos + 5)
                val width = beShort(data, pos + 7)
                return width to height
            }
            pos += 2 + length
        }
        return null
    }

    private fun webpSize(data: ByteArray): Pair<Int, Int>? {
        if (data.size < 30) return null
        if (String(data, 0, 4, Charsets.ISO_8859_1) != "RIFF") return null
        if (String(data, 8, 4, Charsets.ISO_8859_1) != "WEBP") return null
        val chunk = String(data, 12, 4, Charsets.ISO_8859_1)
        return when (chunk) {
            "VP8X" -> (leInt24(data, 24) + 1) to (leInt24(data, 27) + 1)
            "VP8 " -> (leShort(data, 26) and 0x3FFF) to (leShort(data, 28) and 0x3FFF)
            "VP8L" -> {
                val bits = leInt(data, 21)
                ((bits and 0x3FFF) + 1) to (((bits shr 14) and 0x3FFF) + 1)
            }
            else -> null
        }
    }

    private fun beInt(data: ByteArray, pos: Int): Int {
        var value = 0
        for (i in 0 until 4) value = (value shl 8) or (data[pos + i].toInt() and 0xFF)
        return value
    }

    private fun beShort(data: ByteArray, pos: Int): Int =
        ((data[pos].toInt() and 0xFF) shl 8) or (data[pos + 1].toInt() and 0xFF)

    private fun leShort(data: ByteArray, pos: Int): Int =
        (data[pos].toInt() and 0xFF) or ((data[pos + 1].toInt() and 0xFF) shl 8)

    private fun leInt(data: ByteArray, pos: Int): Int {
        var value = 0
        for (i in 3 downTo 0) value = (value shl 8) or (data[pos + i].toInt() and 0xFF)
        return value
    }

    private fun leInt24(data: ByteArray, pos: Int): Int =
        (data[pos].toInt() and 0xFF) or
            ((data[pos + 1].toInt() and 0xFF) shl 8) or
            ((data[pos + 2].toInt() and 0xFF) shl 16)

    /** 图片的 (宽, 高) 像素；读不出来就给竖屏兜底值（不抛异常）。 */
    fun imageSize(path: String?): Pair<Int, Int> {
        if (path.isNullOrEmpty()) return DEFAULT_SIZE
        val data = try {
            File(path).readBytes()
        } catch (_: Exception) {
            return DEFAULT_SIZE
        }
        val readers = listOf(::pngSize, ::jpegSize, ::gifSize, ::webpSize)
        for (reader in readers) {
            val size = try {
                reader(data)
            } catch (_: Exception) {
                null
            }
            if (size != null && size.first > 0 && size.second > 0) return size
        }
        return DEFAULT_SIZE
    }

    /**
     * 算「手机屏幕形状」的取景区（cover 语义），单位是图片像素。
     *
     * - `zoom=1` 时取景区是能盖住屏幕的最大区域（也就是 cover）
     * - `zoom` 越大取景区越小（等于把图放大）
     * - `center` 是取景区中心在图片里的归一化位置（0~1），会被夹在图片内
     */
    fun cropBox(
        image: Pair<Int, Int>,
        screen: Pair<Int, Int>,
        zoom: Double,
        center: Pair<Double, Double>,
    ): CropBox {
        val imgW = maxOf(1, image.first)
        val imgH = maxOf(1, image.second)
        val scrW = maxOf(1, screen.first)
        val scrH = maxOf(1, screen.second)
        val aspect = scrH.toDouble() / scrW  // 屏幕高宽比（竖屏 > 1）
        val z = maxOf(1.0, zoom)

        // 基准取景区（z=1，即 cover）：能盖住屏幕的最大「屏幕形状」矩形。
        // cover 时并不显示整张图，所以不能按「整图面积 / z²」算。
        val baseW = minOf(imgW.toDouble(), imgH / aspect)
        val baseH = baseW * aspect
        val width = baseW / z
        val height = baseH / z

        var cx = clamp01(center.first) * imgW
        var cy = clamp01(center.second) * imgH
        val halfW = width / 2
        val halfH = height / 2
        cx = minOf(maxOf(cx, halfW), imgW - halfW)
        cy = minOf(maxOf(cy, halfH), imgH - halfH)
        return CropBox(cx - halfW, cy - halfH, width, height)
    }

    fun clamp01(value: Double?): Double {
        if (value == null || value.isNaN()) return 0.5
        return minOf(1.0, maxOf(0.0, value))
    }

    /**
     * 取景区中心（图片归一化 0~1）→ 对齐值（-1~1）。
     *
     * 对齐是相对**溢出量**的（`(img - box) / 2`），不是相对整图，
     * 所以必须带上取景区尺寸一起算，否则拖到边上会偏。
     */
    fun alignmentForCenter(
        image: Pair<Int, Int>,
        box: Pair<Double, Double>,
        center: Pair<Double, Double>,
    ): Pair<Double, Double> {
        val imgW = maxOf(1, image.first)
        val imgH = maxOf(1, image.second)
        val boxW = if (box.first == 0.0) 1.0 else box.first
        val boxH = if (box.second == 0.0) 1.0 else box.second
        var cx = clamp01(center.first) * imgW
        var cy = clamp01(center.second) * imgH
        cx = minOf(maxOf(cx, boxW / 2), imgW - boxW / 2)
        cy = minOf(maxOf(cy, boxH / 2), imgH - boxH / 2)

        fun toAlign(value: Double, size: Int, span: Double): Double {
            val slack = (size - span) / 2
            if (slack <= 0) return 0.0
            return (value - size / 2.0) / slack
        }

        return toAlign(cx, imgW, boxW) to toAlign(cy, imgH, boxH)
    }

    /** 对齐值（-1~1）→ 取景区中心（图片归一化 0~1）。 */
    fun centerForAlignment(
        image: Pair<Int, Int>,
        box: Pair<Double, Double>,
        alignment: Pair<Double, Double>,
    ): Pair<Double, Double> {
        val imgW = maxOf(1, image.first)
        val imgH = maxOf(1, image.second)
        val boxW = if (box.first == 0.0) 1.0 else box.first
        val boxH = if (box.second == 0.0) 1.0 else box.second
        val ax = minOf(1.0, maxOf(-1.0, alignment.first))
        val ay = minOf(1.0, maxOf(-1.0, alignment.second))
        val cx = boxW / 2 + (ax + 1) / 2 * maxOf(0.0, imgW - boxW)
        val cy = boxH / 2 + (ay + 1) / 2 * maxOf(0.0, imgH - boxH)
        return clamp01(cx / imgW) to clamp01(cy / imgH)
    }
}
