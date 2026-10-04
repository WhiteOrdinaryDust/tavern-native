package com.tavern.chat

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.tavern.domain.media.DEFAULT_SCREEN
import com.tavern.domain.media.Media
import com.tavern.domain.models.ApiSettings
import com.tavern.domain.rhythm.Rhythm
import com.tavern.domain.theme.Appearance
import com.tavern.domain.theme.BG_ZOOM_MAX
import com.tavern.domain.theme.BG_ZOOM_MIN
import com.tavern.domain.theme.BUBBLE_SWATCHES
import com.tavern.domain.theme.FONT_OPTIONS
import com.tavern.domain.theme.FONT_SCALES
import com.tavern.domain.theme.OPACITIES
import com.tavern.domain.theme.TEXT_SWATCHES
import com.tavern.domain.theme.THEMES
import com.tavern.domain.theme.Theme

/**
 * 设置页（独立页面，不是弹窗）：改一下立刻生效并落库，不用点保存。
 *
 * 页签：美化 / 接口（开关、角色两项待补 —— 见 PARITY.md 待办）。
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: ApiSettings,
    appearance: Appearance,
    onSettingsChanged: (ApiSettings) -> Unit,
    onAppearanceChanged: (Appearance) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var tab by remember { mutableStateOf(0) }

    Scaffold(
        // 透明容器必须显式给 contentColor：透明色无法推导文字颜色，
        // Compose 会回退成黑色 —— 深色主题下「主题/字体/字号」这些标签就看不见了
        containerColor = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurface,
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = { TextButton(onClick = onBack) { Text("‹ 返回") } },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = tab) {
                TextButton(onClick = { tab = 0 }) { Text("美化") }
                TextButton(onClick = { tab = 1 }) { Text("接口") }
                TextButton(onClick = { tab = 2 }) { Text("节奏") }
                TextButton(onClick = { tab = 3 }) { Text("输出") }
                TextButton(onClick = { tab = 4 }) { Text("开关") }
                TextButton(onClick = { tab = 5 }) { Text("角色") }
            }
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(12.dp),
            ) {
                when (tab) {
                    0 -> LookTab(appearance, onAppearanceChanged)
                    1 -> ApiTab(settings, onSettingsChanged)
                    2 -> RhythmTab(settings, onSettingsChanged)
                    3 -> OutputTab(settings, onSettingsChanged)
                    4 -> SwitchTab(
                        settings,
                        appearance,
                        onSettingsChanged,
                        onAppearanceChanged,
                    )
                    else -> RoleTab(settings, onSettingsChanged)
                }
            }
        }
    }
}

@Composable
private fun LookTab(appearance: Appearance, onChange: (Appearance) -> Unit) {
    val context = LocalContext.current

    Text("主题", style = MaterialTheme.typography.labelLarge)
    Column {
        for ((key, label) in THEMES) {
            TextButton(onClick = { onChange(appearance.copy(theme = key)) }) {
                Text(if (appearance.theme == key) "● $label" else "○ $label")
            }
        }
    }

    Text("字号", style = MaterialTheme.typography.labelLarge)
    Column {
        for ((key, label) in FONT_SCALES) {
            val value = key.toDoubleOrNull() ?: 1.0
            TextButton(onClick = { onChange(appearance.copy(fontScale = value)) }) {
                Text(if (appearance.fontScale == value) "● $label" else "○ $label")
            }
        }
    }

    Text("字体", style = MaterialTheme.typography.labelLarge)
    Column {
        for ((key, label) in FONT_OPTIONS) {
            TextButton(onClick = { onChange(appearance.copy(fontFamily = key)) }) {
                Text(if (appearance.fontFamily == key) "● $label" else "○ $label")
            }
        }
    }

    Text("你的气泡", style = MaterialTheme.typography.labelLarge)
    Swatches(appearance.bubbleUser) { onChange(appearance.copy(bubbleUser = it)) }
    Text("角色的气泡", style = MaterialTheme.typography.labelLarge)
    Swatches(appearance.bubbleChar) { onChange(appearance.copy(bubbleChar = it)) }

    Text("气泡透明度", style = MaterialTheme.typography.labelLarge)
    Slider(
        value = appearance.bubbleOpacity.toFloat(),
        onValueChange = { onChange(appearance.copy(bubbleOpacity = it.toDouble())) },
        valueRange = 0f..1f,
    )
    Text(
        "当前 ${(appearance.bubbleOpacity * 100).toInt()}%（0 = 完全透明）",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Text("顶部信息栏透明度", style = MaterialTheme.typography.labelLarge)
    Slider(
        value = appearance.topBarAlpha.toFloat(),
        onValueChange = { onChange(appearance.copy(topBarAlpha = it.toDouble())) },
        valueRange = 0f..1f,
    )
    Text(
        "当前 ${(appearance.topBarAlpha * 100).toInt()}%（0 = 完全透明）",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Text("底部信息栏透明度", style = MaterialTheme.typography.labelLarge)
    Slider(
        value = appearance.bottomBarAlpha.toFloat(),
        onValueChange = { onChange(appearance.copy(bottomBarAlpha = it.toDouble())) },
        valueRange = 0f..1f,
    )
    Text(
        "当前 ${(appearance.bottomBarAlpha * 100).toInt()}%（调低可透出背景图）",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Text("正文字色", style = MaterialTheme.typography.labelLarge)
    Swatches(appearance.textColor) { onChange(appearance.copy(textColor = it)) }

    Spacer(Modifier.height(8.dp))
    Text("背景图", style = MaterialTheme.typography.labelLarge)
    val bgPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri != null) {
            val saved = copyBackground(context, uri)
            if (saved != null) onChange(appearance.copy(bgImage = saved))
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { bgPicker.launch(arrayOf("image/*")) }) { Text("选图") }
        TextButton(onClick = { onChange(appearance.copy(bgImage = "")) }) { Text("清除") }
        Text(
            if (appearance.bgImage.isEmpty()) "未设置" else Theme.zoomLabel(appearance),
            style = MaterialTheme.typography.labelSmall,
        )
    }
    Text("缩放（连续）", style = MaterialTheme.typography.labelSmall)
    Slider(
        value = appearance.bgZoom.toFloat(),
        onValueChange = { onChange(appearance.copy(bgZoom = it.toDouble())) },
        valueRange = BG_ZOOM_MIN.toFloat()..BG_ZOOM_MAX.toFloat(),
    )

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("背景压暗（默认关，原色显示）", style = MaterialTheme.typography.labelSmall)
        androidx.compose.material3.Switch(
            checked = appearance.bgDimOn,
            onCheckedChange = { onChange(appearance.copy(bgDimOn = it)) },
        )
    }
    if (appearance.bgDimOn) {
        Slider(
            value = appearance.bgDim.toFloat(),
            onValueChange = { onChange(appearance.copy(bgDim = it.toDouble())) },
            valueRange = 0f..0.9f,
        )
    }

    if (appearance.bgImage.isNotEmpty()) {
        Text("取景框（框内就是最终效果，拖动图片选位置）", style = MaterialTheme.typography.labelSmall)
        CropPreview(appearance, onChange)
    }
}

/** 固定成手机比例的取景框：图在框里拖动（所见即所得）。 */
@Composable
private fun CropPreview(appearance: Appearance, onChange: (Appearance) -> Unit) {
    val img = remember(appearance.bgImage) { Media.imageSize(appearance.bgImage) }
    val bitmap = remember(appearance.bgImage) {
        try {
            android.graphics.BitmapFactory.decodeFile(appearance.bgImage)?.asImageBitmap()
        } catch (_: Exception) {
            null
        }
    }
    if (bitmap == null) return
    val frameW = 150.dp
    val frameH = frameW * (com.tavern.domain.media.DEFAULT_SCREEN.second.toFloat() / com.tavern.domain.media.DEFAULT_SCREEN.first.toFloat())
    val framePx = with(LocalDensity.current) { frameW.toPx() }
    val aspect = com.tavern.domain.media.DEFAULT_SCREEN.second.toDouble() / com.tavern.domain.media.DEFAULT_SCREEN.first
    val boxW = minOf(img.first.toDouble(), img.second / aspect) / appearance.bgZoom
    val boxSize = boxW to (boxW * aspect)
    val center = remember(appearance.bgImage) {
        mutableStateOf(Media.centerForAlignment(img, boxSize, appearance.bgX to appearance.bgY))
    }

    Box(
        modifier = Modifier
            .width(frameW)
            .height(frameH)
            .background(Color.Black)
            .clipToBounds()
            .pointerInput(appearance.bgImage, appearance.bgZoom) {
                detectDragGestures { change, drag ->
                    change.consume()
                    val scale = img.first.toDouble() / framePx
                    val c = center.value
                    val next = Media.clamp01(c.first - drag.x * scale / img.first) to
                        Media.clamp01(c.second - drag.y * scale / img.second)
                    center.value = next
                    val align = Media.alignmentForCenter(img, boxSize, next)
                    onChange(appearance.copy(bgX = align.first, bgY = align.second))
                }
            },
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val crop = Media.cropBox(img, com.tavern.domain.media.DEFAULT_SCREEN, appearance.bgZoom, center.value)
            drawImage(
                image = bitmap,
                srcOffset = androidx.compose.ui.unit.IntOffset(
                    crop.left.toInt().coerceAtLeast(0),
                    crop.top.toInt().coerceAtLeast(0),
                ),
                srcSize = androidx.compose.ui.unit.IntSize(
                    crop.width.toInt().coerceAtLeast(1),
                    crop.height.toInt().coerceAtLeast(1),
                ),
                dstOffset = androidx.compose.ui.unit.IntOffset.Zero,
                dstSize = androidx.compose.ui.unit.IntSize(
                    size.width.toInt().coerceAtLeast(1),
                    size.height.toInt().coerceAtLeast(1),
                ),
            )
        }
    }
}

/**
 * 节奏：时间粒度 4 档 + 动作权限 3 档 = 七个按钮的提示词，逐条可改。
 * 输入框留空 = 用内置文案（占位符里显示的就是内置的那句）。
 */
@Composable
private fun RhythmTab(settings: ApiSettings, onChange: (ApiSettings) -> Unit) {
    var draft by remember { mutableStateOf(Rhythm.parseOverrides(settings.rhythmOverrides)) }

    Text(
        "这七段文案会注入每次请求的 system prompt。" +
            "输入框里**已经是内置文案**，直接在它基础上改即可（不用从零重写）；" +
            "「恢复默认」会填回内置内容。",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(6.dp))
    for ((key, label) in Rhythm.overrideKeys()) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        OutlinedTextField(
            // 关键：默认文案直接作为可编辑的初值（以前只是 placeholder，一打字就没了）
            value = draft[key] ?: Rhythm.defaultText(key),
            onValueChange = { draft = draft + (key to it) },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(4.dp))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = {
            // 与内置一致的就不必存（保持配置精简）
            val cleaned = draft.filter { (k, v) ->
                v.isNotBlank() && v.trim() != Rhythm.defaultText(k).trim()
            }
            onChange(settings.copy(rhythmOverrides = Rhythm.overridesToJson(cleaned)))
        }) { Text("保存") }
        TextButton(onClick = {
            // 清掉覆盖 → 界面重新显示内置文案，仍然是可编辑的
            draft = emptyMap()
        }) { Text("恢复默认") }
        TextButton(onClick = {
            draft = Rhythm.parseOverrides(settings.rhythmOverrides)
        }) { Text("撤销") }
    }
    Spacer(Modifier.height(8.dp))
    Text("预览（以当前聊天档位为例）", style = MaterialTheme.typography.labelSmall)
    Text(
        Rhythm.buildRhythmBlock(
            settings.let { "scene" },
            "strict",
            overrides = draft,
        ),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** 输出：字数上下限（0 = 不限）。 */
@Composable
private fun OutputTab(settings: ApiSettings, onChange: (ApiSettings) -> Unit) {
    var minText by remember { mutableStateOf(settings.outputMinChars.toString()) }
    var maxText by remember { mutableStateOf(settings.outputMaxChars.toString()) }
    // 这几个原本在「角色」页签，属于"长度/预算"类，挪到这里更合适
    var ctx by remember { mutableStateOf(settings.contextTokens.toString()) }
    var maxOut by remember { mutableStateOf(settings.maxTokens.toString()) }
    var temp by remember { mutableStateOf(settings.temperature.toString()) }

    Text(
        "限制回复的篇幅。0 表示不限制；两端都填会写成「控制在 X–Y 字之间」。",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(6.dp))
    OutlinedTextField(
        value = minText,
        onValueChange = { minText = it.filter { ch -> ch.isDigit() } },
        label = { Text("字数下限（0 = 不限）") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(6.dp))
    OutlinedTextField(
        value = maxText,
        onValueChange = { maxText = it.filter { ch -> ch.isDigit() } },
        label = { Text("字数上限（0 = 不限）") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = {
            onChange(
                settings.copy(
                    outputMinChars = minText.toIntOrNull() ?: 0,
                    outputMaxChars = maxText.toIntOrNull() ?: 0,
                    contextTokens = ctx.toIntOrNull() ?: settings.contextTokens,
                    maxTokens = maxOut.toIntOrNull() ?: settings.maxTokens,
                    temperature = temp.toDoubleOrNull() ?: settings.temperature,
                ),
            )
        }) { Text("保存") }
        TextButton(onClick = {
            minText = settings.outputMinChars.toString()
            maxText = settings.outputMaxChars.toString()
        }) { Text("撤销") }
    }
    Spacer(Modifier.height(8.dp))
    Spacer(Modifier.height(6.dp))
    NumberField("上下文预算（tokens）", ctx) { ctx = it }
    NumberField("最大输出（tokens）", maxOut) { maxOut = it }
    NumberField("温度（0–2，越高越随机）", temp) { temp = it }
    val preview = Rhythm.lengthRule(minText.toIntOrNull() ?: 0, maxText.toIntOrNull() ?: 0)
    Text(
        "预览：" + preview.ifEmpty { "不注入长度规则" },
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
    )
}

/** 开关：每一项改一下立刻生效（不用保存）。 */
@Composable
private fun SwitchTab(
    settings: ApiSettings,
    appearance: Appearance,
    onChange: (ApiSettings) -> Unit,
    onAppearance: (Appearance) -> Unit,
) {
    var settings2 = appearance
    SwitchRow("显示思考过程", settings.showReasoning, "关掉后模型思考内容完全不显示") {
        onChange(settings.copy(showReasoning = it))
    }
    SwitchRow("Markdown 渲染", settings.renderMarkdown, "标题/列表/表格/代码块；关掉则纯文本") {
        onChange(settings.copy(renderMarkdown = it))
    }
    SwitchRow("动作斜体（*动作*）", settings.formatActions, "把 *拥抱* 这类动作显示为斜体") {
        onChange(settings.copy(formatActions = it))
    }
    SwitchRow("自动清洗回复", settings.cleanupReply, "去掉回复开头的「角色名：」这类前缀") {
        onChange(settings.copy(cleanupReply = it))
    }
    SwitchRow("世界书带角色名", settings.worldIncludeNames, "注入世界书条目时附带触发角色名") {
        onChange(settings.copy(worldIncludeNames = it))
    }
    SwitchRow("自动前情提要", settings.summaryEnabled, "攒够消息数后在后台压缩成提要") {
        onChange(settings.copy(summaryEnabled = it))
    }

    Spacer(Modifier.height(6.dp))
    Text("气泡下小字", style = MaterialTheme.typography.labelLarge)
    SwitchRow("显示模型名", settings2.showModel, "气泡下方显示当前模型") {
        settings2 = settings2.copy(showModel = it)
        onAppearance(settings2)
    }
    SwitchRow("显示 tokens", settings2.showTokens, "该条消息消耗的 tokens") {
        settings2 = settings2.copy(showTokens = it)
        onAppearance(settings2)
    }
    SwitchRow("显示字数", settings2.showWords, "该条回复的字数") {
        settings2 = settings2.copy(showWords = it)
        onAppearance(settings2)
    }
    SwitchRow("显示时间", settings2.showTime, "该条消息的时间（时:分）") {
        settings2 = settings2.copy(showTime = it)
        onAppearance(settings2)
    }
}

/** 角色与上下文：数字/文本类参数，改完点保存。 */
@Composable
private fun RoleTab(settings: ApiSettings, onChange: (ApiSettings) -> Unit) {
    var name by remember { mutableStateOf(settings.userName) }
    var persona by remember { mutableStateOf(settings.userPersona) }
    var interval by remember { mutableStateOf(settings.summaryInterval.toString()) }
    var depth by remember { mutableStateOf(settings.worldScanDepth.toString()) }
    var budget by remember { mutableStateOf(settings.worldTokenBudget.toString()) }

    OutlinedTextField(
        value = name,
        onValueChange = { name = it },
        label = { Text("你的名字（提示词里用它称呼你）") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(6.dp))
    OutlinedTextField(
        value = persona,
        onValueChange = { persona = it },
        label = { Text("你的人设（可留空）") },
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(6.dp))
    NumberField("前情提要间隔（条）", interval) { interval = it }
    NumberField("世界书扫描深度（条消息）", depth) { depth = it }
    NumberField("世界书 token 预算", budget) { budget = it }

    Spacer(Modifier.height(10.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = {
            onChange(
                settings.copy(
                    userName = name.trim().ifEmpty { "User" },
                    userPersona = persona,
                    summaryInterval = interval.toIntOrNull() ?: settings.summaryInterval,
                    worldScanDepth = depth.toIntOrNull() ?: settings.worldScanDepth,
                    worldTokenBudget = budget.toIntOrNull() ?: settings.worldTokenBudget,
                ),
            )
        }) { Text("保存") }
    }
}

@Composable
private fun SwitchRow(
    label: String,
    value: Boolean,
    hint: String = "",
    onChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            if (hint.isNotEmpty()) {
                Text(
                    hint,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        androidx.compose.material3.Switch(checked = value, onCheckedChange = onChange)
    }
}

@Composable
private fun NumberField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun Swatches(current: String, onPick: (String) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.horizontalScroll(rememberScrollState()),
    ) {
        TextButton(onClick = { onPick("") }) {
            Text(if (current.isEmpty()) "● 跟随主题" else "○ 跟随主题")
        }
        for (hex in BUBBLE_SWATCHES + TEXT_SWATCHES) {
            if (hex !in BUBBLE_SWATCHES) break
            val selected = current.equals(hex, ignoreCase = true)
            Box(
                modifier = Modifier
                    .padding(horizontal = 3.dp)
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(colorOf(hex))
                    .border(
                        width = if (selected) 3.dp else 1.dp,
                        color = if (selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outline,
                        shape = CircleShape,
                    )
                    .clickable { onPick(hex) },
            )
        }
    }
}

/**
 * Key 打码：保留前 4 位与后 4 位，中间逐字符替换成圆点。
 *
 * 关键点：**打码后的长度必须和原文一致**，否则 OffsetMapping.Identity 会越界，
 * 一进这个页签就闪退（这也是之前"点接口就退"的根因）。
 * 短于 12 位时全部打码，避免"露首尾"反而暴露太多。
 */
private class MaskKeyTransformation : VisualTransformation {
    override fun filter(text: androidx.compose.ui.text.AnnotatedString): TransformedText {
        val raw = text.text
        val masked = if (raw.length <= 12) {
            "•".repeat(raw.length)
        } else {
            raw.take(4) + "•".repeat(raw.length - 8) + raw.takeLast(4)
        }
        return TransformedText(
            androidx.compose.ui.text.AnnotatedString(masked),
            OffsetMapping.Identity,
        )
    }
}

private fun colorOf(hex: String): Color = try {
    Color(android.graphics.Color.parseColor(hex))
} catch (_: Exception) {
    Color.Gray
}

@Composable
private fun ApiTab(settings: ApiSettings, onChange: (ApiSettings) -> Unit) {
    var baseUrl by remember { mutableStateOf(settings.baseUrl) }
    var apiKey by remember { mutableStateOf(settings.apiKey) }
    var model by remember { mutableStateOf(settings.model) }
    // 联网检索（可选）：只在聊天页开启「联网」时才会用到
    var provider by remember { mutableStateOf(settings.searchProvider) }
    var searchKey by remember { mutableStateOf(settings.searchApiKey) }
    var searchUrl by remember { mutableStateOf(settings.searchUrl) }
    var results by remember { mutableStateOf(settings.searchResults.toString()) }

    OutlinedTextField(
        value = baseUrl,
        onValueChange = { baseUrl = it },
        label = { Text("API 地址") },
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = apiKey,
        onValueChange = { apiKey = it },
        label = { Text("API Key") },
        singleLine = true,
        // 按惯例只露首尾，中间打码（不提供"全部显示"）
        visualTransformation = MaskKeyTransformation(),
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = model,
        onValueChange = { model = it },
        label = { Text("模型名") },
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(12.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = {
            onChange(
            settings.copy(
                baseUrl = baseUrl.trim(),
                apiKey = apiKey.trim(),
                model = model.trim(),
                searchProvider = provider.trim().ifEmpty { "tavily" },
                searchApiKey = searchKey.trim(),
                searchUrl = searchUrl.trim(),
                searchResults = results.toIntOrNull() ?: settings.searchResults,
            ),
        )
        }) { Text("保存") }
        TextButton(onClick = {
            baseUrl = settings.baseUrl
            apiKey = settings.apiKey
            model = settings.model
        }) { Text("还原") }
    }
    Spacer(Modifier.height(8.dp))
    Spacer(Modifier.height(16.dp))
    Text("联网检索（可选）", style = MaterialTheme.typography.labelLarge)
    Text(
        "只在聊天页开启「联网」时才会调用；服务商名按接口要求填写（如 tavily、bocha），自定义反代可只填地址。",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(6.dp))
    OutlinedTextField(
        value = provider,
        onValueChange = { provider = it },
        label = { Text("检索服务商") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(6.dp))
    OutlinedTextField(
        value = searchKey,
        onValueChange = { searchKey = it },
        label = { Text("检索 API Key") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(6.dp))
    OutlinedTextField(
        value = searchUrl,
        onValueChange = { searchUrl = it },
        label = { Text("自定义端点（留空用默认）") },
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(6.dp))
    OutlinedTextField(
        value = results,
        onValueChange = { results = it.filter { ch -> ch.isDigit() } },
        label = { Text("结果条数") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    Text(
        "提示：Key 明文保存在应用私有目录（受系统沙箱保护），界面里默认打码显示。",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
