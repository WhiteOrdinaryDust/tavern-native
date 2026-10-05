package com.tavern.chat

import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.luminance
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.tavern.data.RoomStore
import com.tavern.data.TavernDatabase
import com.tavern.domain.models.ApiSettings
import com.tavern.domain.models.Character
import com.tavern.domain.models.Chat
import com.tavern.domain.models.Group
import com.tavern.domain.models.Message
import com.tavern.domain.memory.Memory
import com.tavern.domain.memory.SUMMARY_MAX_TOKENS
import com.tavern.domain.postprocess.Postprocess
import com.tavern.domain.prompt.Prompt
import com.tavern.domain.rhythm.Rhythm
import com.tavern.domain.search.Search
import kotlinx.serialization.json.Json
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import com.tavern.domain.stats.ContextUsage
import com.tavern.domain.theme.Appearance
import com.tavern.domain.theme.FONT_OPTIONS
import com.tavern.domain.theme.BUBBLE_SWATCHES
import com.tavern.domain.theme.OPACITIES
import com.tavern.domain.theme.TEXT_SWATCHES
import com.tavern.domain.theme.FONT_SCALES
import com.tavern.domain.theme.THEMES
import com.tavern.domain.theme.Theme
import androidx.compose.ui.unit.sp
import com.tavern.domain.stats.Stats
import com.tavern.domain.worldbook.Worldbook
import com.tavern.net.Cancelable
import com.tavern.net.TavernClient
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            App()
        }
    }
}

/** 打开库的快捷方式（Room 默认不允许主线程查询，调用方都在后台线程里）。 */
internal fun store0(context: android.content.Context): RoomStore =
    RoomStore(TavernDatabase.open(context))

internal fun reloadMessages(
    context: android.content.Context,
    chatId: String,
    then: (List<Message>) -> Unit,
) {
    bg({ store0(context).listMessages(chatId) }, then)
}

/** 后台线程池：Room 默认不允许主线程查询，所有数据操作都放这里。 */
private val io = Executors.newSingleThreadExecutor()
private val main = Handler(Looper.getMainLooper())

internal fun <T> bg(work: () -> T, then: (T) -> Unit = {}) {
    io.execute {
        val result = runCatching(work)
        main.post {
            result.onSuccess(then).onFailure { /* 真机上先不弹错，保持界面可用 */ }
        }
    }
}

/**
 * 应用外壳：主页（角色列表）↔ 聊天页。
 *
 * 只用状态切换而不是 Navigation 库：这一层没有深层链接需求，少一个依赖。
 */
@Composable
private fun App() {
    val context = LocalContext.current
    var ready by remember { mutableStateOf(false) }
    var characters by remember { mutableStateOf<List<Character>>(emptyList()) }
    var current by remember { mutableStateOf<Character?>(null) }
    var settings by remember { mutableStateOf(ApiSettings()) }
    var appearance by remember { mutableStateOf(Appearance()) }
    var currentGroup by remember { mutableStateOf<Group?>(null) }
    var showSettingsPage by remember { mutableStateOf(false) }
    // 主页页签放在这里：进聊天再退出时，要回到原来的页签（群聊就回群聊）
    var homeTab by remember { mutableStateOf(0) }

    fun reload() {
        bg({
            val store = RoomStore(TavernDatabase.open(context))
            // 第一次启动：放一个角色，免得界面是空的
            if (store.listCharacters().isEmpty()) {
                store.upsertCharacter(
                    Character(name = "林月", firstMes = "*擦着杯子* 又来啦？"),
                )
            }
            Triple(store.listCharacters(), store.loadSettings(), store.loadAppearance())
        }) { triple ->
            characters = triple.first
            settings = triple.second
            appearance = triple.third
            ready = true
        }
    }

    LaunchedEffect(Unit) { reload() }

    TavernTheme(appearance) {
    Box(modifier = Modifier.fillMaxSize()) {
    // 主题自带的默认背景：没选图时铺一层柔和渐变，避免大片纯色显得空
    if (appearance.bgImage.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            MaterialTheme.colorScheme.background,
                            MaterialTheme.colorScheme.surfaceVariant,
                        ),
                    ),
                ),
        )
    }
    // 背景图：铺在最底层 + 可选压暗层。图片只在设置里选过一次后解码缓存。
    val bgPath = appearance.bgImage
    if (bgPath.isNotEmpty() && java.io.File(bgPath).exists()) {
        val bitmap = remember(bgPath) {
            try {
                android.graphics.BitmapFactory.decodeFile(bgPath)?.asImageBitmap()
            } catch (_: Exception) {
                null
            }
        }
        if (bitmap != null) {
            // 用 cropBox 精确裁切绘制：与外观里的取景预览是同一套数学，所见即所得
            val imgSize = remember(bgPath) {
                com.tavern.domain.media.Media.imageSize(bgPath)
            }
            val screen = com.tavern.domain.media.DEFAULT_SCREEN
            val center = com.tavern.domain.media.Media.centerForAlignment(
                imgSize,
                run {
                    val aspect = screen.second.toDouble() / screen.first
                    val w = minOf(imgSize.first.toDouble(), imgSize.second / aspect) / appearance.bgZoom
                    w to w * aspect
                },
                appearance.bgX to appearance.bgY,
            )
            val crop = com.tavern.domain.media.Media.cropBox(imgSize, screen, appearance.bgZoom, center)
            // 目标尺寸固定为"整屏"，而不是当前测量到的高度：
            // 键盘弹出时布局会变矮，若按测量高度绘制，背景就会被压扁（一打字就扭曲）。
            // 固定整屏尺寸后，效果是键盘把背景**遮住**，而不是压缩它。
            val density = androidx.compose.ui.platform.LocalDensity.current
            val screenCfg = androidx.compose.ui.platform.LocalConfiguration.current
            val fullWidthPx = with(density) { screenCfg.screenWidthDp.dp.toPx() }.toInt()
            val fullHeightPx = with(density) { screenCfg.screenHeightDp.dp.toPx() }.toInt()
            Canvas(modifier = Modifier.fillMaxSize()) {
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
                        fullWidthPx.coerceAtLeast(1),
                        fullHeightPx.coerceAtLeast(1),
                    ),
                )
            }
        }
        // 只有显式打开"压暗"才叠加滤镜（老设置里的 dim 值不再自动生效）
        val dim = if (appearance.bgDimOn && appearance.bgDim > 0.0) {
            com.tavern.domain.theme.Theme.dimColor(appearance)
        } else {
            ""
        }
        if (dim.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(colorOrNull(dim) ?: Color.Transparent),
            )
        }
    }
    val opened = current
    when {
        showSettingsPage -> SettingsScreen(
            settings = settings,
            appearance = appearance,
            onSettingsChanged = { updated ->
                settings = updated
                bg({ store0(context).saveSettings(updated) })
            },
            onAppearanceChanged = { updated ->
                appearance = updated
                bg({ store0(context).saveAppearance(updated) })
            },
            onBack = { showSettingsPage = false },
        )
        !ready -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("正在打开数据库…")
        }
        // 群聊分支必须排在主页分支之前（opened 为空时也要能进群）
        currentGroup != null && (opened != null || characters.isNotEmpty()) -> ChatScreen(
            character = opened ?: characters.first(),
            group = currentGroup,
            members = characters,
            onOpenSettings = { showSettingsPage = true },
            appearance = appearance,
            onAppearanceChanged = { updated ->
                appearance = updated
                bg({ store0(context).saveAppearance(updated) })
            },
            settings = settings,
            onSettingsChanged = { updated ->
                settings = updated
                bg({ store0(context).saveSettings(updated) })
            },
            onGroupChanged = { updated ->
                // 只更新群的禁言状态；消息列表由聊天页自己按需重载
                currentGroup = updated
                bg({ store0(context).upsertGroup(updated) })
            },
            onBack = {
                currentGroup = null
                reload()
            },
        )
        opened == null -> HomeScreen(
            characters = characters,
            tab = homeTab,
            onTabChanged = { homeTab = it },
            onOpen = { current = it },
            onOpenGroup = { currentGroup = it },
            onOpenSettings = { showSettingsPage = true },
            onChanged = { reload() },
        )
        else -> ChatScreen(
            character = opened,
            onOpenSettings = { showSettingsPage = true },
            appearance = appearance,
            onAppearanceChanged = { updated ->
                appearance = updated
                bg({ store0(context).saveAppearance(updated) })
            },
            settings = settings,
            onSettingsChanged = { updated ->
                settings = updated
                bg({ RoomStore(TavernDatabase.open(context)).saveSettings(updated) })
            },
            onBack = {
                current = null
                reload()
            },
        )
    }
    }
    }
}

/** 聊天页：稳定 key + 位置锚定 + 流式写入。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatScreen(
    character: Character,
    group: Group? = null,
    members: List<Character> = emptyList(),
    settings: ApiSettings,
    onSettingsChanged: (ApiSettings) -> Unit,
    appearance: Appearance,
    onAppearanceChanged: (Appearance) -> Unit,
    onOpenSettings: () -> Unit = {},
    onGroupChanged: (Group) -> Unit = {},
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val client = remember { TavernClient() }
    val listState = rememberLazyListState()
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current

    var ready by remember { mutableStateOf(false) }
    var chat by remember { mutableStateOf<Chat?>(null) }
    var messages by remember { mutableStateOf<List<Message>>(emptyList()) }
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var streamingText by remember { mutableStateOf("") }
    var streamingReasoning by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var showSettings by remember { mutableStateOf(false) }
    var showAppearance by remember { mutableStateOf(false) }
    var showSessions by remember { mutableStateOf(false) }
    var showSummary by remember { mutableStateOf(false) }
    var showRhythm by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    var editingSpeaker by remember { mutableStateOf<Character?>(null) }
    var exportNotice by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    // 候选切换等"改了可变对象、列表内容却相等"的场景：用版本号强制相关子树重组
    var uiRev by remember { mutableStateOf(0) }
    var lastSentTokens by remember { mutableStateOf(0) }
    var showBooks by remember { mutableStateOf(false) }
    var showMembers by remember { mutableStateOf(false) }
    var call by remember { mutableStateOf<Cancelable?>(null) }
    var actionTarget by remember { mutableStateOf<Message?>(null) }
    var editingTarget by remember { mutableStateOf<Message?>(null) }
    var usage by remember { mutableStateOf<ContextUsage?>(null) }
    var showUsage by remember { mutableStateOf(false) }

    // 首次进入：打开库、取（或造）一个角色与会话、没有消息就放开场白
    LaunchedEffect(Unit) {
        bg({
            val store = RoomStore(TavernDatabase.open(context))
            val target = if (group != null) {
                store.primaryGroupChat(group.id, title = group.title)
            } else {
                store.primaryChat(character.id)
            }
            // 单聊：把开场白放进去；群聊一开始全员禁言，等解禁谁谁出场
            if (group == null && store.countMessages(target.id) == 0 && character.greeting.isNotEmpty()) {
                store.addMessage(target.id, "assistant", character.greeting)
            }
            target.id
        }) { chatId ->
            // 注意：这个回调在主线程，**不能**在这里查数据库（Room 会直接抛异常闪退）
            bg({ store0(context).getChat(chatId) }) { loaded ->
                chat = loaded
                ready = true
                reloadMessages(context, chatId) { messages = it }
            }
        }
    }

    // 上下文用量：口径与 Python 版一致（system + 每条历史各 4 token 开销）
    LaunchedEffect(messages, settings.contextTokens, chat?.summary, settings.userPersona, settings.systemExtra) {
        val char = character
        val system = Prompt.buildSystemPrompt(
            char, settings, settings.userName.ifEmpty { "User" }, chat?.summary ?: "",
        )
        usage = Stats.contextUsage(messages, systemText = system, budget = settings.contextTokens)
    }

    // 位置锚定：只有本来就在底部时才跟着新内容走（这就是"跟手"的来源）
    val atBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            last >= info.totalItemsCount - 2
        }
    }
    // 进入会话后贴到底部（每个会话只做一次）。
    // 只靠 atBottom 推断不行：首次进入时列表还没完成布局，visibleItemsInfo 是空的，
    // 结果新进来会停在顶部（这个 bug 就是这么来的）。
    var restoredForChat by remember { mutableStateOf("") }
    LaunchedEffect(chat?.id, messages.size) {
        val id = chat?.id ?: return@LaunchedEffect
        if (restoredForChat != id && messages.isNotEmpty()) {
            listState.scrollToItem(messages.size - 1)
            restoredForChat = id
        }
    }

    LaunchedEffect(messages.size, streamingText.length) {
        if (atBottom && (messages.isNotEmpty() || streamingText.isNotEmpty())) {
            listState.animateScrollToItem(if (busy) messages.size else messages.size - 1)
        }
    }

    /**
     * 给某个发言者生成一轮回复。
     *
     * `userText` 非空 = 这一轮由"你发言"触发；`rest` 是**群里还没轮到的成员**，
     * 这一轮结束后如果还有人就接着让他们说（"依次都回"）。
     */
    fun generateFor(
        speaker: Character,
        target: Chat,
        userText: String?,
        rest: List<Character>,
        variantTarget: Message? = null,
    ) {
        busy = true
        streamingText = ""
        streamingReasoning = ""
        error = ""
        // 服务端在流末尾回传用量；收集起来随回复落库（气泡下小字要用）
        var usageAcc: Map<String, Int> = emptyMap()
        bg({
            val store = RoomStore(TavernDatabase.open(context))
            val all = store.listMessages(target.id)
            // 重写模式：把这条回复**排除出上下文**（它之后的也不要），重新写一遍
            val history = if (variantTarget != null) {
                all.takeWhile { it.id != variantTarget.id }
            } else {
                all
            }

            // 前情提要：攒够 summaryInterval 条新消息就顺手压缩一次。
            // 刻意**不阻塞这一轮**（后台整理），失败也不影响对话。
            if (settings.summaryEnabled &&
                Memory.shouldRefresh(history, target.summaryUpto, settings.summaryInterval)
            ) {
                val batch = Memory.collectBatch(history, target.summaryUpto, settings.summaryMaxInput)
                if (batch.isNotEmpty()) {
                    client.complete(
                        Memory.summarySettings(settings),
                        Memory.buildSummaryMessages(speaker, settings, batch, target.summary),
                        SUMMARY_MAX_TOKENS,
                        onResult = { raw ->
                            val cleaned = Memory.cleanSummary(raw)
                            if (cleaned.isNotBlank()) {
                                val upto = Memory.nextUpto(history, target.summaryUpto, batch)
                                bg({ store0(context).setChatSummary(target.id, cleaned, upto) }) {
                                    chat = chat?.copy(summary = cleaned, summaryUpto = upto)
                                }
                            }
                        },
                        onError = { /* 后台整理，失败就算了，不打扰用户 */ },
                    )
                }
            }

            // 世界书按窗口应用：显式选过就只用选中的，否则全局 + 本角色的书
            // 按「角色 / 群聊」导入的世界书（kv 里存 id 列表）；空 = 这本都不注入
            // 只有「已勾选启用」的书参与注入（导入但停用的不注入）
            val ownerKey = "books_on_" + (group?.id ?: speaker.id)
            val ownerBooks = store.readKv(ownerKey).orEmpty()
                .split("\n").filter { it.isNotBlank() }
            val books = Worldbook.resolveWindowBooks(
                ownerBooks,
                emptyList(),
                store.allWorldBooks(),
            )
            val entries = Worldbook.entriesOfBooks(books, store.allWorldEntries())
            val selected = Worldbook.buildContext(
                entries,
                history,
                scanDepth = settings.worldScanDepth,
                tokenBudget = settings.worldTokenBudget,
                includeNames = settings.worldIncludeNames,
                charName = speaker.name,
                userName = settings.userName,
                minActivations = settings.worldMinActivations,
            )
            // 联网：先搜再答（失败不影响这一轮）
            var webContext = ""
            if (target.webSearch) {
                val query = (userText ?: history.lastOrNull { it.role == "user" }?.text ?: "").trim()
                if (query.isNotEmpty()) {
                    val (url, headers, body) = Search.buildRequest(settings, query, settings.searchResults)
                    val raw = client.postJsonBlocking(
                        url,
                        headers,
                        Json.encodeToString(JsonObject.serializer(), body),
                    )
                    if (raw != null) {
                        val parsed = try {
                            Search.parseResults(Json.parseToJsonElement(raw), settings.searchResults)
                        } catch (_: Exception) {
                            emptyList()
                        }
                        webContext = Search.formatContext(parsed, settings.searchMaxChars)
                    }
                }
            }

            // 节奏协议：七段文案支持用户在设置里覆盖
            val rhythmBlock = Rhythm.buildRhythmBlock(
                target.rhythmTime,
                target.rhythmAuthority,
                overrides = Rhythm.parseOverrides(settings.rhythmOverrides),
            )
            // 输出字数上下限拼进 systemExtra（不改动原有参数语义）
            val lengthRule = Rhythm.lengthRule(settings.outputMinChars, settings.outputMaxChars)
            val effective = if (lengthRule.isEmpty()) {
                settings
            } else {
                settings.copy(
                    systemExtra = listOf(settings.systemExtra, lengthRule)
                        .filter { it.isNotBlank() }
                        .joinToString("\n"),
                )
            }
            val base = if (group != null) {
                Prompt.buildGroupMessages(
                    speaker, members, effective, history,
                    userInput = userText,
                    summary = target.summary,
                    rhythm = rhythmBlock,
                    webContext = webContext,
                )
            } else {
                Prompt.buildMessages(
                    speaker, effective, history,
                    userInput = userText,
                    summary = target.summary,
                    rhythm = rhythmBlock,
                    webContext = webContext,
                )
            }
            Worldbook.inject(base, selected)
        }) { payload ->
            // 这次实际发出去多少（payload 就在手边，纯计算、不碰数据库）
            lastSentTokens = payload.sumOf { item ->
                com.tavern.domain.prompt.Prompt.estimateTokens(item["content"].orEmpty())
            }
            call = client.streamChat(
                settings, payload,
                onChunk = { chunk ->
                    val rawUsage = chunk.usage
                    if (rawUsage != null) {
                        fun num(key: String): Int = (rawUsage[key] as? Number)?.toInt() ?: 0
                        usageAcc = mapOf(
                            "prompt_tokens" to num("prompt_tokens"),
                            "completion_tokens" to num("completion_tokens"),
                            "total_tokens" to num("total_tokens"),
                        ).filterValues { it > 0 }
                    }
                    main.post {
                        when (chunk.kind) {
                            "content" -> streamingText += chunk.text
                            "reasoning" -> streamingReasoning += chunk.text
                        }
                    }
                },
                onError = { message ->
                    main.post {
                        busy = false
                        error = message
                    }
                },
                onDone = {
                    main.post {
                        val content = streamingText
                        val reasoning = streamingReasoning
                        bg({
                            val store = RoomStore(TavernDatabase.open(context))
                            if (content.isNotBlank()) {
                                val cleaned = Postprocess.cleanReply(
                                    content,
                                    speaker.name,
                                    stripPrefix = settings.cleanupReply,
                                    rulesText = settings.cleanupRules,
                                    userName = settings.userName,
                                )
                                if (variantTarget != null) {
                                    // 重写：旧回复保留着，随时能翻回去（候选条 ◀ n/m ▶）
                                    variantTarget.addVariant(cleaned)
                                    if (reasoning.isNotBlank()) variantTarget.reasoning = reasoning
                                    if (usageAcc.isNotEmpty()) variantTarget.usage = usageAcc
                                    store.saveMessage(variantTarget)
                                } else {
                                    val saved = store.addMessage(
                                        target.id,
                                        "assistant",
                                        cleaned,
                                        reasoning = reasoning,
                                        // 群里要按发言人署名（单聊留空）
                                        speaker = if (group != null) speaker.id else "",
                                    )
                                    if (usageAcc.isNotEmpty()) {
                                        saved.usage = usageAcc
                                        store.saveMessage(saved)
                                    }
                                }
                            }
                            store.listMessages(target.id)
                        }) { reloaded ->
                            messages = reloaded
                            streamingText = ""
                            streamingReasoning = ""
                            if (rest.isNotEmpty() && group != null) {
                                generateFor(rest.first(), target, null, rest.drop(1))
                            } else {
                                busy = false
                            }
                        }
                    }
                },
            )
        }
    }

    /** 重写某条回复：把它排除出上下文重新生成，结果作为新候选。 */
    fun regenerate(msg: Message) {
        val target = chat ?: return
        if (busy) return
        val speaker = if (group != null) {
            members.firstOrNull { it.id == msg.speaker } ?: members.firstOrNull()
        } else {
            character
        } ?: return
        actionTarget = null
        generateFor(speaker, target, null, emptyList(), variantTarget = msg)
    }

    fun send() {
        val text = input.trim()
        val target = chat ?: return
        if (text.isEmpty() || busy) return
        if (!settings.configured) {
            error = "请先在右上角填 API 地址与模型名"
            showSettings = true
            return
        }
        val currentGroup = group
        // 你发言之后：所有没被禁言的成员依次各回一句
        val queue = if (currentGroup != null) {
            val active = currentGroup.activeMembers().mapNotNull { id -> members.firstOrNull { it.id == id } }
            if (active.isEmpty()) members.take(1) else active
        } else {
            listOf(character)
        }
        if (queue.isEmpty()) return
        input = ""
        error = ""
        bg({
            val store = RoomStore(TavernDatabase.open(context))
            store.addMessage(target.id, "user", text)
            store.autotitleChat(target.id, text)
            store.listMessages(target.id)
        }) { list ->
            messages = list
            generateFor(queue.first(), target, text, queue.drop(1))
        }
    }

    // 传文件：把文本内容读进输入框当上下文（Flet 版也是这个做法）
    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        bg({
            val name = uri.lastPathSegment?.substringAfterLast('/') ?: "文件"
            val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?.toString(Charsets.UTF_8) ?: ""
            name to text
        }) { pair ->
            val (name, text) = pair
            if (text.isBlank()) {
                error = "这个文件读不出文本内容（可能不是纯文本）"
            } else {
                val limit = 20000
                val clipped = text.take(limit)
                input = (input.trimEnd() + "\n\n【文件：$name】\n" + clipped).trimStart()
                error = if (text.length > limit) "文件较长，只取前 $limit 字" else ""
            }
        }
    }

    fun reload() {
        val target = chat ?: return
        bg({ RoomStore(TavernDatabase.open(context)).listMessages(target.id) }) { list -> messages = list }
    }

    fun swipeVariant(msg: Message, delta: Int) {
        if (!msg.swipe(delta)) return
        // 关键：不动内存里的候选（重载会覆盖它），只递增版本号强制重建相关子树。
        // 之前用 messages = messages.toList() 是无效的：List.equals 比较元素，
        // 里面还是同一批可变对象 → 判定"没变化" → 界面不更新。
        messages = messages.toList()
        uiRev++          // 强制气泡与候选计数刷新
        bg({ RoomStore(TavernDatabase.open(context)).saveMessage(msg) })
        actionTarget = null
    }

    fun deleteMessage(msg: Message) {
        bg({ RoomStore(TavernDatabase.open(context)).deleteMessage(msg.id) })
        messages = messages.filterNot { it.id == msg.id }
        actionTarget = null
    }

    fun branchFrom(msg: Message) {
        val target = chat
        if (target == null) { actionTarget = null; return }
        bg({ RoomStore(TavernDatabase.open(context)).branchChat(target.id, msg.id) }) { created ->
            actionTarget = null
            if (created != null) {
                chat = created
                messages = emptyList()
                bg({ RoomStore(TavernDatabase.open(context)).listMessages(created.id) }) { list -> messages = list }
            }
        }
    }

    Scaffold(
        // 容器透明，背景图才透得出来；同时显式指定文字颜色（透明色无法推导）
        containerColor = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurface,
        topBar = {
            // 顶栏收窄：之前用默认高度，上下留白显得很空
            TopAppBar(
                modifier = Modifier.height(46.dp),
                colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(
                        alpha = LocalAppearance.current.topBarAlpha
                            .coerceIn(0.0, 1.0).toFloat(),
                    ),
                ),
                title = {
                    Text(
                        group?.title ?: character.name,
                        style = MaterialTheme.typography.titleMedium,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Text("‹") }
                },
                actions = {
                    // 高频操作放在外面：节奏（七个档位）人人要用；禁言只有群聊才有
                    IconButton(onClick = { showRhythm = true }) { Text("节奏") }
                    if (group != null) {
                        IconButton(onClick = { showMembers = true }) { Text("禁言") }
                    }
                    var moreOpen by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { moreOpen = true }) { Text("⋮") }
                        DropdownMenu(
                            expanded = moreOpen,
                            onDismissRequest = { moreOpen = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("详细信息") },
                                onClick = { moreOpen = false; showUsage = true },
                            )
                            DropdownMenuItem(
                                text = { Text("传文件") },
                                onClick = {
                                    moreOpen = false
                                    filePicker.launch(arrayOf("text/*", "*/*"))
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("节奏档位") },
                                onClick = { moreOpen = false; showRhythm = true },
                            )
                            DropdownMenuItem(
                                text = { Text("世界书") },
                                onClick = { moreOpen = false; showBooks = true },
                            )
                            DropdownMenuItem(
                                text = { Text("导出 / 分享 Markdown") },
                                onClick = {
                                    moreOpen = false
                                    val target = chat
                                    if (target != null) {
                                        val md = com.tavern.domain.postprocess.Postprocess.exportMarkdown(
                                            character = character,
                                            chat = target,
                                            messages = messages,
                                            includeReasoning = false,
                                            userName = settings.userName,
                                            speakerNames = members.associate { it.id to it.name },
                                        )
                                        // 同时放剪贴板 + 调系统分享：长文被某些应用截断时还有剪贴板兜底
                                        val clipboard = context.getSystemService(
                                            android.content.Context.CLIPBOARD_SERVICE,
                                        ) as android.content.ClipboardManager
                                        clipboard.setPrimaryClip(
                                            android.content.ClipData.newPlainText("会话记录", md),
                                        )
                                        val share = android.content.Intent(
                                            android.content.Intent.ACTION_SEND,
                                        ).apply {
                                            type = "text/plain"
                                            putExtra(android.content.Intent.EXTRA_SUBJECT, target.title)
                                            putExtra(android.content.Intent.EXTRA_TEXT, md)
                                        }
                                        context.startActivity(
                                            android.content.Intent.createChooser(share, "分享会话"),
                                        )
                                        exportNotice = "已生成 Markdown（${md.length} 字），并已复制到剪贴板"
                                    }
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("搜索本会话") },
                                onClick = { moreOpen = false; showSearch = true },
                            )
                            DropdownMenuItem(
                                text = { Text("前情提要") },
                                onClick = { moreOpen = false; showSummary = true },
                            )
                            DropdownMenuItem(
                                text = { Text("会话列表") },
                                onClick = { moreOpen = false; showSessions = true },
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(if (chat?.webSearch == true) "关闭联网" else "开启联网")
                                },
                                onClick = {
                                    moreOpen = false
                                    val target = chat
                                    if (target != null) {
                                        val updated = target.copy(webSearch = !target.webSearch)
                                        chat = updated
                                        bg({
                                            store0(context).setChatWebSearch(
                                                target.id,
                                                updated.webSearch,
                                            )
                                        })
                                    }
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("设置") },
                                onClick = { moreOpen = false; onOpenSettings() },
                            )
                        }
                    }
                },
            )
        },
        bottomBar = {
            Column(
                modifier = Modifier
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant.copy(
                            alpha = LocalAppearance.current.bottomBarAlpha
                                .coerceIn(0.0, 1.0).toFloat(),
                        ),
                    )
                    .padding(horizontal = 12.dp, vertical = 2.dp),
            ) {
                if (exportNotice.isNotEmpty()) {
                    Text(
                        exportNotice,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                if (error.isNotEmpty()) {
                    Text(
                        error,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                // 第一行：用量（可点开详情）+ 重写 + 候选切换，全挤在一行里省高度
                // 小字居中：对着"输入框"这段宽度居中 —— 右侧留出按钮+间距的等宽占位
                Row(
                    modifier = Modifier.fillMaxWidth().height(30.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Spacer(Modifier.width(42.dp))
                    val sentPercent = if (settings.contextTokens > 0) {
                        Math.rint(lastSentTokens * 100.0 / settings.contextTokens).toInt()
                    } else {
                        0
                    }
                    TextButton(
                        onClick = { showUsage = true },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            horizontal = 4.dp,
                        ),
                        modifier = Modifier.height(28.dp),
                    ) {
                        Text(
                            "发送 $sentPercent%",
                            style = MaterialTheme.typography.labelSmall,
                            color = when (Stats.usageLevel(usage?.percent ?: 0)) {
                                "danger" -> MaterialTheme.colorScheme.error
                                "warn" -> Color(0xFFE6B422)
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                    Text(
                        "· ${messages.size} 条 · ${usage?.used ?: 0} tk",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(42.dp))
                }
                // 第二行：紧凑输入框（BasicTextField，比 OutlinedTextField 矮）+ 小发送键
                // 学 DeepSeek：左右留边、不贴屏幕底、随输入自动增高
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 0.dp)
                        .padding(bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BasicTextField(
                        value = input,
                        onValueChange = { input = it },
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        // 最少 2 行、最多 6 行：随输入自动长高
                        maxLines = 6,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 46.dp, max = 138.dp)
                            .background(
                                MaterialTheme.colorScheme.surface,
                                RoundedCornerShape(14.dp),
                            )
                            .padding(horizontal = 14.dp, vertical = 11.dp),
                        decorationBox = { innerTextField: @Composable () -> Unit ->
                            if (input.isEmpty()) {
                                Text(
                                    "说点什么…",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            innerTextField()
                        },
                    )
                    Spacer(Modifier.width(8.dp))
                    if (busy) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(androidx.compose.foundation.shape.CircleShape)
                                .background(MaterialTheme.colorScheme.error)
                                .clickable { call?.cancel() },
                            contentAlignment = Alignment.Center,
                        ) { Text("■", color = MaterialTheme.colorScheme.onPrimary) }
                    } else {
                        // 圆形发送键（给输入框让出更多空间）
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(androidx.compose.foundation.shape.CircleShape)
                                .background(MaterialTheme.colorScheme.primary)
                                .clickable { send() },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "↑",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                        }
                    }
                }
            }
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (!ready) {
                Text("正在打开数据库…", modifier = Modifier.align(Alignment.Center))
                return@Box
            }
            
            LazyColumn(
                state = listState,
                // 点消息区的空白处：收起键盘并去掉输入焦点
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures(onTap = {
                            focusManager.clearFocus()
                            keyboard?.hide()
                        })
                    },
                contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // 稳定 key：列表只增不重建，滚动位置不会跳
                items(messages, key = { it.id + "#" + it.variantIndex + "#" + uiRev }) { message ->
                    Bubble(
                        text = message.text,
                        reasoning = message.reasoning,
                        isUser = message.role == "user",
                        speakerName = if (group != null && message.role != "user") {
                            members.firstOrNull { it.id == message.speaker }?.name ?: "（已退群）"
                        } else null,
                        streaming = false,
                        formatActions = settings.formatActions,
                        // 这三个原来漏传：导致气泡下小字永远为空、Markdown 也没生效
                        renderMarkdown = settings.renderMarkdown,
                        showReasoning = settings.showReasoning,
                        modelName = settings.model,
                        tokensUsed = message.usage["total_tokens"]
                            ?: message.usage["completion_tokens"]
                            ?: com.tavern.domain.prompt.Prompt.estimateTokens(message.text),
                        tokensEstimated = message.usage.isEmpty(),
                        createdAt = message.createdAt,
                        avatarPath = if (message.role == "user") {
                            ""
                        } else {
                            members.firstOrNull { it.id == message.speaker }?.avatarPath
                                ?: character.avatarPath
                        },
                        avatarName = if (message.role == "user") {
                            ""
                        } else {
                            members.firstOrNull { it.id == message.speaker }?.name
                                ?: character.name
                        },
                        onAvatarClick = {
                            if (message.role != "user") {
                                // 点这条消息的头像 = 编辑发言人的角色卡
                                editingSpeaker = members.firstOrNull { it.id == message.speaker }
                                    ?: character
                            }
                        },
                        onClick = { actionTarget = message },
                    )
                    // 最后一条回复下方：↻ 重写 + 候选左右切换（学 DeepSeek 的位置）
                    val lastReplyId = messages.lastOrNull { it.role != "user" }?.id
                    if (message.id == lastReplyId) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // 左边：打开气泡菜单（长按仍然可用，这只是多给一个入口）
                            TextButton(
                                onClick = { actionTarget = message },
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                    horizontal = 6.dp,
                                ),
                                modifier = Modifier.height(30.dp),
                            ) {
                                Text("☰", style = MaterialTheme.typography.labelLarge)
                            }
                            Spacer(Modifier.weight(1f))
                            // 右边：重写（↻）与候选切换
                            TextButton(
                                onClick = { regenerate(message) },
                                enabled = !busy,
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                    horizontal = 6.dp,
                                ),
                                modifier = Modifier.height(30.dp),
                            ) {
                                Text("↻", style = MaterialTheme.typography.titleMedium)
                            }
                            if (message.variantCount > 1) {
                                TextButton(
                                    onClick = { swipeVariant(message, -1) },
                                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                        horizontal = 2.dp,
                                    ),
                                    modifier = Modifier.height(30.dp),
                                ) { Text("◀", style = MaterialTheme.typography.labelMedium) }
                                Text(
                                    "${message.variantIndex + 1}/${message.variantCount}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                TextButton(
                                    onClick = { swipeVariant(message, 1) },
                                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                        horizontal = 2.dp,
                                    ),
                                    modifier = Modifier.height(30.dp),
                                ) { Text("▶", style = MaterialTheme.typography.labelMedium) }
                            }
                        }
                    }
                }
                if (busy) {
                    item(key = "__streaming__") {
                        Bubble(
                            text = streamingText.ifEmpty { "…" },
                            reasoning = streamingReasoning,
                            isUser = false,
                            streaming = true,
                            formatActions = settings.formatActions,
                            renderMarkdown = settings.renderMarkdown,
                            showReasoning = settings.showReasoning,
                        )
                    }
                }
            }
        }
    }

    actionTarget?.let { msg ->
        val count = msg.variantCount
        AlertDialog(
            onDismissRequest = { actionTarget = null },
            title = { Text(if (msg.role == "user") "你的消息" else "角色消息") },
            text = {
                Column {
                    if (count > 1) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { swipeVariant(msg, -1) }) { Text("◀") }
                            Text("${msg.variantIndex + 1}/$count")
                            TextButton(onClick = { swipeVariant(msg, 1) }) { Text("▶") }
                        }
                    }
                    Text(msg.text.take(120), style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    editingTarget = msg
                    actionTarget = null
                }) { Text("编辑") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        val clipboard = context.getSystemService(
                            android.content.Context.CLIPBOARD_SERVICE,
                        ) as android.content.ClipboardManager
                        clipboard.setPrimaryClip(
                            android.content.ClipData.newPlainText("消息", msg.text),
                        )
                        actionTarget = null
                    }) { Text("复制") }
                    if (msg.role != "user") {
                        TextButton(onClick = { regenerate(msg) }) { Text("重写") }
                    }
                    TextButton(onClick = { branchFrom(msg) }) { Text("开分支") }
                    TextButton(onClick = { deleteMessage(msg) }) { Text("删除") }
                    TextButton(onClick = { actionTarget = null }) { Text("取消") }
                }
            },
        )
    }

    editingTarget?.let { msg ->
        var draft by remember(msg.id) { mutableStateOf(msg.text) }
        AlertDialog(
            onDismissRequest = { editingTarget = null },
            title = { Text("编辑这条消息") },
            text = {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.fillMaxWidth().height(200.dp),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val text = draft.trim()
                    editingTarget = null
                    if (text.isNotEmpty()) {
                        bg({ RoomStore(TavernDatabase.open(context)).updateMessage(msg.id, text) })
                        reload()
                    }
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { editingTarget = null }) { Text("取消") } },
        )
    }

    val usageInfo = usage
    if (showUsage && usageInfo != null) {
        val info = usageInfo
        val stats = Stats.conversationStats(messages)
        AlertDialog(
            onDismissRequest = { showUsage = false },
            title = { Text("本对话信息") },
            text = {
                Column {
                    Text("消息：${info.messages} 条（你 ${stats.userMessages} · 角色 ${stats.assistantMessages}）")
                    Text("字数：${stats.chars} 字（其中思考 ${stats.reasoningChars} 字）")
                    Text("上下文：${info.used} / ${info.budget} tokens（${info.percent}%），剩余 ${info.remaining}")
                    if (stats.measuredReplies > 0) {
                        Text("实测用量（${stats.measuredReplies} 条）：输入 ${stats.promptTokens} · 输出 ${stats.completionTokens}")
                        if (stats.reasoningTokens > 0) Text("其中思考 ${stats.reasoningTokens}")
                        Text("缓存命中率：${Stats.cacheHitRate(stats.promptTokens, stats.cachedTokens)}%")
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showUsage = false }) { Text("好") } },
        )
    }

    if (showSearch) {
        var query by remember { mutableStateOf("") }
        val hits = if (query.isBlank()) {
            emptyList()
        } else {
            messages.filter { it.text.contains(query.trim(), ignoreCase = true) }.takeLast(50)
        }
        AlertDialog(
            onDismissRequest = { showSearch = false },
            title = { Text("搜索本会话") },
            text = {
                Column {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text("关键词") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (query.isBlank()) "输入关键词开始搜索（只搜本会话）" else "命中 ${hits.size} 条",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                        items(hits, key = { it.id }) { hit ->
                            val who = if (hit.role == "user") {
                                settings.userName.ifBlank { "你" }
                            } else {
                                members.firstOrNull { it.id == hit.speaker }?.name ?: character.name
                            }
                            TextButton(onClick = {
                                showSearch = false
                                val idx = messages.indexOfFirst { it.id == hit.id }
                                if (idx >= 0) {
                                    scope.launch { listState.animateScrollToItem(idx) }
                                }
                            }) {
                                Column {
                                    Text(
                                        "$who · ${hit.text.replace("\n", " ").take(48)}",
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showSearch = false }) { Text("关闭") } },
        )
    }

    if (showRhythm) {
        val target = chat
        AlertDialog(
            onDismissRequest = { showRhythm = false },
            title = { Text("节奏档位") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text("时间粒度", style = MaterialTheme.typography.labelLarge)
                    for ((key, label) in Rhythm.TIME_GEARS) {
                        TextButton(onClick = {
                            if (target != null) {
                                val updated = target.copy(rhythmTime = key)
                                chat = updated
                                bg({ store0(context).setChatRhythm(target.id, key, target.rhythmAuthority) })
                            }
                        }) {
                            val on = target?.rhythmTime == key
                            Text(if (on) "● $label" else "○ $label")
                        }
                    }
                    Text("动作权限", style = MaterialTheme.typography.labelLarge)
                    for ((key, label) in Rhythm.AUTHORITIES) {
                        TextButton(onClick = {
                            if (target != null) {
                                val updated = target.copy(rhythmAuthority = key)
                                chat = updated
                                bg({ store0(context).setChatRhythm(target.id, target.rhythmTime, key) })
                            }
                        }) {
                            val on = target?.rhythmAuthority == key
                            Text(if (on) "● $label" else "○ $label")
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "把鼠标放到设置里的「节奏」页签可以逐条改这七段提示词。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = { TextButton(onClick = { showRhythm = false }) { Text("好") } },
        )
    }

    if (showSummary) {
        val current = chat
        AlertDialog(
            onDismissRequest = { showSummary = false },
            title = { Text("前情提要") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    val digest = current?.summary.orEmpty().trim()
                    if (digest.isEmpty()) {
                        Text(
                            "还没有提要。攒够 ${settings.summaryInterval} 条新消息后会**自动**压缩一次" +
                                "（这一轮的回复不受影响，后台完成）。",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    } else {
                        Text(digest, style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(8.dp))
                        val upto = current?.summaryUpto.orEmpty()
                        val covered = if (upto.isEmpty()) 0 else messages.indexOfFirst { it.id == upto } + 1
                        Text(
                            "已覆盖前 $covered 条消息 · 约 ${com.tavern.domain.prompt.Prompt.estimateTokens(digest)} tokens",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showSummary = false }) { Text("好") } },
        )
    }

    editingSpeaker?.let { target ->
        CharacterEditDialog(
            character = target,
            onDismiss = { editingSpeaker = null },
            onSave = { updated ->
                editingSpeaker = null
                bg({ store0(context).upsertCharacter(updated) })
            },
        )
    }

    if (showMembers) {
        val activeGroup = group
        if (activeGroup != null) {
            MemberDialog(
                group = activeGroup,
                members = members.filter { it.id in activeGroup.members },
                candidates = members,
                chatId = chat?.id,
                onDismiss = { showMembers = false },
                onGroupChanged = { updated ->
                    showMembers = false
                    onGroupChanged(updated)
                },
            )
        }
    }

    if (showBooks) {
        WorldBookDialog(
            ownerId = group?.id ?: character.id,
            ownerLabel = group?.title ?: character.name,
            onDismiss = { showBooks = false },
        )
    }

    if (showSessions) {
        SessionDialog(
            character = character,
            currentChatId = chat?.id,
            onDismiss = { showSessions = false },
            onOpen = { picked ->
                showSessions = false
                chat = picked
                messages = emptyList()
                reloadMessages(context, picked.id) { messages = it }
            },
            onChanged = {},
        )
    }

    if (showAppearance) {
        AppearanceDialog(
            initial = appearance,
            onDismiss = { showAppearance = false },
            onSave = { updated ->
                showAppearance = false
                onAppearanceChanged(Theme.normalize(updated))
            },
        )
    }

    if (showSettings) {
        SettingsDialog(
            initial = settings,
            onDismiss = { showSettings = false },
            onSave = { updated ->
                showSettings = false
                onSettingsChanged(updated)
            },
        )
    }
}


/** 把 `*动作*` / `**强调**` 变成斜体/粗体（不引 Markdown 库，行为与 Flet 版的分段渲染一致）。 */

private fun styledText(text: String, formatActions: Boolean): AnnotatedString {
    if (!formatActions) return AnnotatedString(text)
    return buildAnnotatedString {
        for (span in Postprocess.toSpans(text)) {
            val style = when {
                span.bold -> SpanStyle(fontWeight = FontWeight.Bold)
                span.italic -> SpanStyle(fontStyle = FontStyle.Italic)
                else -> null
            }
            if (style == null) append(span.text) else withStyle(style) { append(span.text) }
        }
    }
}

@Composable
private fun Bubble(
    text: String,
    reasoning: String,
    isUser: Boolean,
    speakerName: String? = null,
    streaming: Boolean,
    formatActions: Boolean = true,
    renderMarkdown: Boolean = false,
    showReasoning: Boolean = true,
    modelName: String = "",
    tokensUsed: Int = 0,
    createdAt: Double = 0.0,
    tokensEstimated: Boolean = false,
    avatarPath: String = "",
    avatarName: String = "",
    onAvatarClick: (() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    val look = LocalAppearance.current
    // 气泡底色：优先自定义色，再套透明度；没设就用主题默认
    val bubbleColor = try {
        Color(android.graphics.Color.parseColor(com.tavern.domain.theme.Theme.bubbleColor(look, isUser)))
    } catch (_: Exception) {
        MaterialTheme.colorScheme.surfaceVariant
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        // 角色消息左侧显示头像；没有图就用名字首字（与角色列表一致的老约定）
        if (!isUser) {
            val look0 = LocalAppearance.current
            val face = remember(avatarPath) {
                if (java.io.File(avatarPath).exists()) {
                    try {
                        android.graphics.BitmapFactory.decodeFile(avatarPath)?.asImageBitmap()
                    } catch (_: Exception) {
                        null
                    }
                } else {
                    null
                }
            }
            val avatarSize = (30 * look0.fontScale).dp.coerceAtLeast(26.dp)
            if (face != null) {
                Image(
                    bitmap = face,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(avatarSize)
                        .clip(CircleShape)
                        .clickable { onAvatarClick?.invoke() }
                        .padding(end = 2.dp),
                )
                Spacer(Modifier.width(6.dp))
            } else {
                Box(
                    modifier = Modifier
                        .size(avatarSize)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { onAvatarClick?.invoke() },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        com.tavern.domain.postprocess.Postprocess.avatarLines(avatarName.ifEmpty { speakerName ?: "" })
                            .joinToString(""),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                Spacer(Modifier.width(6.dp))
            }
        }
        val shape = RoundedCornerShape(14.dp)
        val cardModifier = Modifier.widthIn(max = 320.dp)
        if (onClick != null) {
            // 长按唤起菜单；点一下 = 收起键盘（整片消息区都这样，手感统一）
            val bubbleFocus = androidx.compose.ui.platform.LocalFocusManager.current
            val bubbleKeyboard =
                androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
            Card(
                modifier = cardModifier.combinedClickable(
                    onClick = {
                        bubbleFocus.clearFocus()
                        bubbleKeyboard?.hide()
                    },
                    onLongClick = { onClick?.invoke() },
                ),
                shape = shape,
                colors = CardDefaults.cardColors(containerColor = bubbleColor),
            ) {
                BubbleBody(
                    text, reasoning, streaming, formatActions, speakerName, renderMarkdown,
                    showReasoning, modelName, tokensUsed, createdAt, isUser, tokensEstimated, avatarPath, avatarName,
                    onAvatarClick,
                )
            }
        } else {
            Card(
                modifier = cardModifier,
                shape = shape,
                colors = CardDefaults.cardColors(containerColor = bubbleColor),
            ) {
                BubbleBody(
                    text, reasoning, streaming, formatActions, speakerName, renderMarkdown,
                    showReasoning, modelName, tokensUsed, createdAt, isUser, tokensEstimated, avatarPath, avatarName,
                )
            }
        }
    }
}

@Composable
private fun BubbleBody(
    text: String,
    reasoning: String,
    streaming: Boolean,
    formatActions: Boolean,
    speakerName: String? = null,
    renderMarkdown: Boolean = false,
    showReasoning: Boolean = true,
    modelName: String = "",
    tokensUsed: Int = 0,
    createdAt: Double = 0.0,
    isUser: Boolean = false,
    tokensEstimated: Boolean = false,
    avatarPath: String = "",
    avatarName: String = "",
    onAvatarClick: (() -> Unit)? = null,
) {
    Column(modifier = Modifier.padding(10.dp)) {
        if (speakerName != null) {
            Text(
                speakerName,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        // 思考过程：默认收起，点标题展开/收起；设置里关掉就完全不显示
        if (reasoning.isNotBlank() && showReasoning) {
            var open by remember { mutableStateOf(false) }
            Text(
                if (open) "💭 收起思考" else "💭 思考过程（点开看）",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .padding(bottom = if (open) 4.dp else 0.dp)
                    .clickable { open = !open },
            )
            if (open) {
                Text(
                    reasoning,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }
        }
        val look2 = LocalAppearance.current
        // 正文字色：用户设过就用用户的；没设就按主题底色的明暗挑一个高对比色
        // （之前直接用配色表里的 text，米黄/浅绿这类主题偏灰，读起来发虚）
        val themeBg = runCatching {
            Color(
                android.graphics.Color.parseColor(
                    com.tavern.domain.theme.Theme.palette(look2.theme)["bg"] ?: "#000000",
                ),
            )
        }.getOrDefault(Color.Black)
        val autoText = if (themeBg.luminance() > 0.5f) Color(0xFF1A1A1A) else Color(0xFFF5F5F5)
        val bodyColor = if (look2.textColor.isNotEmpty()) {
            colorOrNull(look2.textColor) ?: autoText
        } else {
            autoText
        }
        // 气泡下小字：由「设置 → 美化」里的四个开关控制
        val look3 = LocalAppearance.current
        if (look3.showModel || look3.showTokens || look3.showWords || look3.showTime) {
            val bits = mutableListOf<String>()
            // 模型名只在角色回复下显示（自己的输入不必重复显示当前模型）
            if (!isUser && look3.showModel && modelName.isNotEmpty()) bits += modelName
            if (look3.showTokens && tokensUsed > 0) {
                bits += if (tokensEstimated) "≈ $tokensUsed tokens" else "$tokensUsed tokens"
            }
            if (look3.showWords && text.isNotBlank()) bits += "${text.length} 字"
            if (look3.showTime && createdAt > 0) {
                bits += java.text.SimpleDateFormat("HH:mm", java.util.Locale.ROOT)
                    .format(java.util.Date((createdAt * 1000).toLong()))
            }
            if (bits.isNotEmpty()) {
                Text(
                    bits.joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
        }
        if (renderMarkdown) {
            // Markdown 里的 Text 默认取 LocalContentColor —— 给它提供正文字色，
            // 否则「正文字色」这个设置对 Markdown 内容完全无效（深色主题下正文会发黑）
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.material3.LocalContentColor provides bodyColor,
            ) {
                MarkdownBody(text)
            }
        } else {
            Text(
                styledText(text, formatActions),
                color = bodyColor,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = Theme.scaled(14, LocalAppearance.current.fontScale).sp,
                ),
                textAlign = TextAlign.Start,
            )
        }
    }
}

@Composable
internal fun SettingsDialog(
    initial: ApiSettings,
    onDismiss: () -> Unit,
    onSave: (ApiSettings) -> Unit,
) {
    var baseUrl by remember { mutableStateOf(initial.baseUrl) }
    var apiKey by remember { mutableStateOf(initial.apiKey) }
    var model by remember { mutableStateOf(initial.model) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("接口设置") },
        text = {
            var showKey by remember { mutableStateOf(false) }
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(value = baseUrl, onValueChange = { baseUrl = it }, label = { Text("API 地址") })
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("API Key") },
                    singleLine = true,
                    // 默认打码，旁边可以临时显示
                    visualTransformation = if (showKey) VisualTransformation.None
                    else PasswordVisualTransformation(),
                    trailingIcon = {
                        TextButton(onClick = { showKey = !showKey }) {
                            Text(if (showKey) "隐藏" else "显示", style = MaterialTheme.typography.labelSmall)
                        }
                    },
                )
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(value = model, onValueChange = { model = it }, label = { Text("模型名") })
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(initial.copy(baseUrl = baseUrl.trim(), apiKey = apiKey.trim(), model = model.trim()))
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 一排色块（第一项「跟随主题」= 空串）。不懂色号就点一下。 */
@Composable
private fun SwatchRow(current: String, colors: List<String>, onPick: (String) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.horizontalScroll(rememberScrollState()),
    ) {
        TextButton(onClick = { onPick("") }) {
            Text(if (current.isEmpty()) "● 跟随主题" else "○ 跟随主题", style = MaterialTheme.typography.labelSmall)
        }
        for (hex in colors) {
            val selected = current.equals(hex, ignoreCase = true)
            Box(
                modifier = Modifier
                    .padding(horizontal = 3.dp)
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(colorOrNull(hex) ?: MaterialTheme.colorScheme.surfaceVariant)
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

/** 把选中的图片拷进应用目录，返回路径（失败返回 null）。 */
internal fun copyBackground(context: android.content.Context, uri: Uri): String? = try {
    val target = java.io.File(context.filesDir, "bg_image")
    context.contentResolver.openInputStream(uri)?.use { input ->
        target.outputStream().use { output -> input.copyTo(output) }
    }
    target.absolutePath
} catch (_: Exception) {
    null
}

private fun colorOrNull(hex: String): Color? = try {
    Color(android.graphics.Color.parseColor(hex))
} catch (_: Exception) {
    null
}

/** 外观设置：主题 + 字号 + 气泡色/透明度 + 正文字色（都直接对应 `:domain` 里测过的档位）。 */
@Composable
private fun AppearanceDialog(
    initial: Appearance,
    onDismiss: () -> Unit,
    onSave: (Appearance) -> Unit,
) {
    val context = LocalContext.current
    var theme by remember { mutableStateOf(initial.theme) }
    var scale by remember { mutableStateOf(initial.fontScale) }
    var bubbleUser by remember { mutableStateOf(initial.bubbleUser) }
    var bubbleChar by remember { mutableStateOf(initial.bubbleChar) }
    var opacity by remember { mutableStateOf(initial.bubbleOpacity) }
    var textColor by remember { mutableStateOf(initial.textColor) }
    var bgImage by remember { mutableStateOf(initial.bgImage) }
    var bgX by remember { mutableStateOf(initial.bgX) }
    var bgY by remember { mutableStateOf(initial.bgY) }
    var bgZoom by remember { mutableStateOf(initial.bgZoom) }
    var fontFamily by remember { mutableStateOf(initial.fontFamily) }

    // 选一张背景图：拷进应用目录（原始位置可能拿不到权限）
    val bgPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri != null) {
            val saved = copyBackground(context, uri)
            if (saved != null) bgImage = saved
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("外观") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text("主题", style = MaterialTheme.typography.labelLarge)
                // 四个主题竖排：横排会被窄屏挤成竖字（浅绿还会被挤出可视区）
                Column {
                    for ((key, label) in THEMES) {
                        TextButton(onClick = { theme = key }) {
                            Text(if (theme == key) "● $label" else "○ $label")
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text("字号", style = MaterialTheme.typography.labelLarge)
                Column {
                    for ((key, label) in FONT_SCALES) {
                        val value = key.toDoubleOrNull() ?: 1.0
                        TextButton(onClick = { scale = value }) {
                            Text(if (scale == value) "● $label" else "○ $label")
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text("字体", style = MaterialTheme.typography.labelLarge)
                Column {
                    for ((key, label) in FONT_OPTIONS) {
                        TextButton(onClick = { fontFamily = key }) {
                            Text(if (fontFamily == key) "● $label" else "○ $label")
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text("你的气泡", style = MaterialTheme.typography.labelLarge)
                SwatchRow(current = bubbleUser, colors = BUBBLE_SWATCHES) { bubbleUser = it }
                Spacer(Modifier.height(4.dp))
                Text("角色的气泡", style = MaterialTheme.typography.labelLarge)
                SwatchRow(current = bubbleChar, colors = BUBBLE_SWATCHES) { bubbleChar = it }
                Spacer(Modifier.height(8.dp))
                Text("气泡透明度", style = MaterialTheme.typography.labelLarge)
                Column {
                    for ((key, label) in OPACITIES) {
                        val value = key.toDoubleOrNull() ?: 1.0
                        TextButton(onClick = { opacity = value }) {
                            Text(if (opacity == value) "● $label" else "○ $label")
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text("正文字色", style = MaterialTheme.typography.labelLarge)
                SwatchRow(current = textColor, colors = TEXT_SWATCHES) { textColor = it }

                Spacer(Modifier.height(8.dp))
                Text("背景图", style = MaterialTheme.typography.labelLarge)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { bgPicker.launch(arrayOf("image/*")) }) { Text("选图") }
                    TextButton(onClick = { bgImage = "" }) { Text("清除") }
                    Text(
                        if (bgImage.isEmpty()) "未设置" else com.tavern.domain.theme.Theme.positionLabel(
                            initial.copy(bgX = bgX, bgY = bgY),
                        ) + " · " + com.tavern.domain.theme.Theme.zoomLabel(initial.copy(bgZoom = bgZoom)),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                Text("背景图缩放（放大后配合九宫格定位到画面局部）", style = MaterialTheme.typography.labelSmall)
                Slider(
                    value = bgZoom.toFloat(),
                    onValueChange = { bgZoom = it.toDouble() },
                    valueRange = com.tavern.domain.theme.BG_ZOOM_MIN.toFloat()..
                        com.tavern.domain.theme.BG_ZOOM_MAX.toFloat(),
                )
                Text("取景框（框内就是最终效果，拖动图片选位置）", style = MaterialTheme.typography.labelSmall)
                val img = remember(bgImage) { com.tavern.domain.media.Media.imageSize(bgImage) }
                val screen = com.tavern.domain.media.DEFAULT_SCREEN
                val frameW = 150.dp
                val frameH = frameW * (screen.second.toFloat() / screen.first.toFloat())
                val framePx = with(LocalDensity.current) { frameW.toPx() }
                val bmp = remember(bgImage) {
                    try {
                        android.graphics.BitmapFactory.decodeFile(bgImage)?.asImageBitmap()
                    } catch (_: Exception) {
                        null
                    }
                }
                val aspect = screen.second.toDouble() / screen.first
                val boxW = minOf(img.first.toDouble(), img.second / aspect) / bgZoom
                val boxSize = boxW to (boxW * aspect)
                // 用可变的中心点：拖动时读它，避免闭包拿到旧值导致画面乱跳
                val center = remember(bgImage) {
                    mutableStateOf(
                        com.tavern.domain.media.Media.centerForAlignment(img, boxSize, bgX to bgY),
                    )
                }
                if (bmp != null) {
                    Box(
                        modifier = Modifier
                            .width(frameW)
                            .height(frameH)
                            .background(Color.Black)
                            .clipToBounds()
                            .pointerInput(bgImage, bgZoom) {
                                detectDragGestures { change, drag ->
                                    change.consume()
                                    // 手指往右拖 = 画面往右走 = 取景区往左（除以框宽换算成图片像素）
                                    val scale = img.first.toDouble() / framePx
                                    val c = center.value
                                    val next = com.tavern.domain.media.Media.clamp01(
                                        c.first - drag.x * scale / img.first,
                                    ) to com.tavern.domain.media.Media.clamp01(
                                        c.second - drag.y * scale / img.second,
                                    )
                                    center.value = next
                                    val align = com.tavern.domain.media.Media.alignmentForCenter(
                                        img, boxSize, next,
                                    )
                                    bgX = align.first
                                    bgY = align.second
                                }
                            },
                    ) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val crop = com.tavern.domain.media.Media.cropBox(
                                img, screen, bgZoom, center.value,
                            )
                            drawImage(
                                image = bmp,
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
                Spacer(Modifier.height(6.dp))
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(
                    initial.copy(
                        theme = theme,
                        fontScale = scale,
                        bubbleUser = bubbleUser,
                        bubbleChar = bubbleChar,
                        bubbleOpacity = opacity,
                        textColor = textColor,
                        bgImage = bgImage,
                        bgX = bgX,
                        bgY = bgY,
                        bgZoom = bgZoom,
                        fontFamily = fontFamily,
                    ),
                )
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
