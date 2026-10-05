package com.tavern.chat

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.tavern.domain.models.Character
import com.tavern.domain.postprocess.Postprocess

/**
 * 角色卡编辑（弹出式）。
 *
 * 顶部展示头像（圆形，没图就用名字首字 ✓），点头像或「换头像」都能改；
 * 下面是角色卡的基本字段（名字/描述/性格/场景/开场白/示例/系统提示/作者/备注/标签）。
 *
 * 聊天里点消息头像也会打开这个界面（改完立即生效）。
 */
@Composable
fun CharacterEditDialog(
    character: Character,
    onDismiss: () -> Unit,
    onSave: (Character) -> Unit,
) {
    val context = LocalContext.current
    var name by remember(character.id) { mutableStateOf(character.name) }
    var description by remember(character.id) { mutableStateOf(character.description) }
    var personality by remember(character.id) { mutableStateOf(character.personality) }
    var scenario by remember(character.id) { mutableStateOf(character.scenario) }
    var firstMes by remember(character.id) { mutableStateOf(character.firstMes) }
    var mesExample by remember(character.id) { mutableStateOf(character.mesExample) }
    var systemPrompt by remember(character.id) { mutableStateOf(character.systemPrompt) }
    var creator by remember(character.id) { mutableStateOf(character.creator) }
    var creatorNotes by remember(character.id) { mutableStateOf(character.creatorNotes) }
    var tags by remember(character.id) { mutableStateOf(character.tags.joinToString("，")) }
    var avatarPath by remember(character.id) { mutableStateOf(character.avatarPath) }
    var cropSource by remember(character.id) { mutableStateOf("") }

    val avatarPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri != null) {
            bg({
                val dir = java.io.File(context.filesDir, "avatars").apply { mkdirs() }
                val temp = java.io.File(dir, "_pick_edit.png")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    temp.outputStream().use { output -> input.copyTo(output) }
                }
                temp.absolutePath
            }) { path -> cropSource = path }
        }
    }

    val avatarBitmap = remember(avatarPath) {
        if (avatarPath.isNotEmpty() && java.io.File(avatarPath).exists()) {
            try {
                android.graphics.BitmapFactory.decodeFile(avatarPath)?.asImageBitmap()
            } catch (_: Exception) {
                null
            }
        } else {
            null
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑角色卡") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                // ---- 头像区 ----
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .clickable { avatarPicker.launch(arrayOf("image/*")) },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (avatarBitmap != null) {
                            Image(
                                bitmap = avatarBitmap,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.size(64.dp),
                            )
                        } else {
                            Text(
                                Postprocess.avatarLines(name).joinToString(""),
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }
                    }
                    Spacer(Modifier.width(10.dp))
                    Column {
                        TextButton(onClick = { avatarPicker.launch(arrayOf("image/*")) }) {
                            Text("换头像")
                        }
                        if (avatarPath.isNotEmpty()) {
                            TextButton(onClick = {
                                val old = avatarPath
                                avatarPath = ""
                                bg({ runCatching { java.io.File(old).delete() } })
                            }) { Text("恢复默认头像") }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("名字") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("描述（角色是谁）") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = personality,
                    onValueChange = { personality = it },
                    label = { Text("性格") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = scenario,
                    onValueChange = { scenario = it },
                    label = { Text("场景") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = firstMes,
                    onValueChange = { firstMes = it },
                    label = { Text("开场白") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = mesExample,
                    onValueChange = { mesExample = it },
                    label = { Text("示例对话（<START> 分隔）") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = systemPrompt,
                    onValueChange = { systemPrompt = it },
                    label = { Text("角色专属系统提示（可留空）") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = tags,
                    onValueChange = { tags = it },
                    label = { Text("标签（逗号分隔）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = creator,
                    onValueChange = { creator = it },
                    label = { Text("作者") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = creatorNotes,
                    onValueChange = { creatorNotes = it },
                    label = { Text("备注") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(
                    character.copy(
                        name = name.trim().ifEmpty { character.name },
                        description = description,
                        personality = personality,
                        scenario = scenario,
                        firstMes = firstMes,
                        mesExample = mesExample,
                        systemPrompt = systemPrompt,
                        creator = creator,
                        creatorNotes = creatorNotes,
                        tags = tags.split(",", "，", "\n", "、")
                            .map { it.trim() }
                            .filter { it.isNotEmpty() },
                        avatarPath = avatarPath,
                    ),
                )
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )

    // ---- 头像裁剪（选完图后弹出） ----
    if (cropSource.isNotEmpty()) {
        AvatarCropDialog(
            sourcePath = cropSource,
            onCancel = { cropSource = "" },
            onSave = { bitmap ->
                cropSource = ""
                val path = saveAvatarBitmap(
                    java.io.File(context.filesDir, "avatars"),
                    character.id + "_" + System.currentTimeMillis(),
                    bitmap,
                )
                if (path != null) {
                    if (avatarPath.isNotEmpty()) {
                        runCatching { java.io.File(avatarPath).delete() }
                    }
                    avatarPath = path
                }
            },
        )
    }
}
