package com.tavern.chat

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import java.io.File

/**
 * 头像裁剪：固定**正方形**取景框，图在框里拖动 + 缩放，所见即所得。
 *
 * 与背景取景框同一套思路（固定框 + 移动图片），只是这里框是正方形、
 * 取景数学内联（头像永远是 1:1，不需要按屏幕比例算）。
 */
@Composable
fun AvatarCropDialog(
    sourcePath: String,
    onCancel: () -> Unit,
    onSave: (Bitmap) -> Unit,
) {
    val src = remember(sourcePath) {
        try {
            BitmapFactory.decodeFile(sourcePath)
        } catch (_: Exception) {
            null
        }
    }
    if (src == null) {
        AlertDialog(
            onDismissRequest = onCancel,
            title = { Text("换头像") },
            text = { Text("这张图片读不出来，换一张试试。") },
            confirmButton = { TextButton(onClick = onCancel) { Text("好") } },
        )
        return
    }

    val frame = 200.dp
    val framePx = with(LocalDensity.current) { frame.toPx() }
    val imgW = src.width.toFloat()
    val imgH = src.height.toFloat()
    // 取景边长（图片像素）：先取"能盖住正方形的最小边长"，再按缩放收窄
    var zoom by remember { mutableStateOf(1f) }
    val side = minOf(imgW, imgH) / zoom
    // 取景中心（图片像素），初值在图片正中
    var cx by remember { mutableStateOf(imgW / 2f) }
    var cy by remember { mutableStateOf(imgH / 2f) }

    fun clamp() {
        val half = side / 2f
        cx = cx.coerceIn(half, imgW - half)
        cy = cy.coerceIn(half, imgH - half)
    }
    clamp()

    val bmp = remember(src) { src.asImageBitmap() }

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("换头像") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "拖动图片选位置，滑杆缩放；方框里就是头像效果。",
                    style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                    color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                Box(
                    modifier = Modifier
                        .size(frame)
                        .background(Color.Black)
                        .border(2.dp, Color.White)
                        .pointerInput(sourcePath, zoom) {
                            detectDragGestures { change, drag ->
                                change.consume()
                                val scale = imgW / framePx
                                cx -= drag.x * scale
                                cy -= drag.y * scale
                                clamp()
                            }
                        },
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val half = side / 2f
                        drawImage(
                            image = bmp,
                            srcOffset = IntOffset(
                                (cx - half).toInt().coerceIn(0, (imgW - side).toInt().coerceAtLeast(0)),
                                (cy - half).toInt().coerceIn(0, (imgH - side).toInt().coerceAtLeast(0)),
                            ),
                            srcSize = IntSize(
                                side.toInt().coerceAtLeast(1),
                                side.toInt().coerceAtLeast(1),
                            ),
                            dstOffset = IntOffset.Zero,
                            dstSize = IntSize(
                                size.width.toInt().coerceAtLeast(1),
                                size.height.toInt().coerceAtLeast(1),
                            ),
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text("缩放", style = androidx.compose.material3.MaterialTheme.typography.labelSmall)
                Slider(
                    value = zoom,
                    onValueChange = { zoom = it.coerceIn(1f, 4f); clamp() },
                    valueRange = 1f..4f,
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                // 按当前取景框裁出正方形，再缩到 512 存档
                val out = 512
                val half = side / 2f
                val left = (cx - half).toInt().coerceIn(0, (imgW - side).toInt().coerceAtLeast(0))
                val top = (cy - half).toInt().coerceIn(0, (imgH - side).toInt().coerceAtLeast(0))
                val edge = side.toInt().coerceAtLeast(1).coerceAtMost(minOf(src.width, src.height))
                val square = try {
                    Bitmap.createBitmap(src, left, top, edge, edge)
                } catch (_: Exception) {
                    src
                }
                onSave(Bitmap.createScaledBitmap(square, out, out, true))
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("取消") } },
    )
}

/** 把裁剪结果写进角色头像文件（覆盖），返回路径。 */
fun saveAvatarBitmap(dir: File, characterId: String, bitmap: Bitmap): String? = try {
    dir.mkdirs()
    val file = File(dir, "$characterId.png")
    file.outputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
    file.absolutePath
} catch (_: Exception) {
    null
}
