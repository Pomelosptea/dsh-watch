@file:OptIn(
    androidx.compose.ui.ExperimentalComposeUiApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package com.deepseek.dshwatch.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import android.text.InputType
import android.widget.Toast
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.sp
import com.deepseek.dshwatch.AppGraph
import com.deepseek.dshwatch.ChatMessage
import com.deepseek.dshwatch.ChatSyncService
import com.deepseek.dshwatch.DshClient
import com.deepseek.dshwatch.DshEvent
import com.deepseek.dshwatch.R
import com.deepseek.dshwatch.WatchConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.min

private enum class Screen { Home, Typing, Settings, History }
private enum class Phase { Idle, Listening, Sending, Done, Error }

private data class Chat(
    val user: String,
    val reply: String = "",
    val phase: Phase = Phase.Sending,
    val error: String? = null,
    val status: String? = null,
)

@Composable
fun DshWatchApp(config: WatchConfig, client: DshClient) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val store = AppGraph.store
    val messages by store.messages.collectAsState()

    var screen by remember { mutableStateOf(Screen.Home) }
    var listening by remember { mutableStateOf(false) }
    var online by remember { mutableStateOf<Boolean?>(null) }
    var configEpoch by remember { mutableStateOf(0) }
    var voiceText by remember { mutableStateOf<String?>(null) }
    var voiceIssue by remember { mutableStateOf<String?>(null) }
    var withdrawTarget by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        ChatSyncService.start(context)
    }

    LaunchedEffect(configEpoch) {
        while (true) {
            online = if (config.configured) client.health() else null
            delay(30_000)
        }
    }

    fun sendText(raw: String) {
        val text = raw.trim()
        if (text.isEmpty()) return
        store.enqueue(text)
        ChatSyncService.start(context)
        screen = Screen.History
    }

    val speechLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        listening = false
        if (result.resultCode == Activity.RESULT_OK) {
            val spoken = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
            if (!spoken.isNullOrBlank()) {
                voiceText = spoken
            } else {
                voiceIssue = "没有听清，再试一次"
            }
        }
    }

    fun startVoice() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "说出你的指令")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        }
        try {
            listening = true
            voiceIssue = null
            speechLauncher.launch(intent)
        } catch (_: ActivityNotFoundException) {
            listening = false
            voiceIssue = "手表上没有语音识别服务，请用键盘输入"
            Toast.makeText(context, "没有系统语音识别，已打开键盘；可点键盘麦克风说话", Toast.LENGTH_LONG).show()
            screen = Screen.Typing
        } catch (_: Exception) {
            listening = false
            voiceIssue = "语音识别启动失败，请用键盘输入"
            Toast.makeText(context, "语音识别启动失败，请用键盘或键盘麦克风", Toast.LENGTH_LONG).show()
            screen = Screen.Typing
        }
    }

    LaunchedEffect(voiceText) {
        val spoken = voiceText
        if (spoken != null) {
            voiceText = null
            sendText(spoken)
        }
    }

    LaunchedEffect(voiceIssue) {
        if (voiceIssue != null) {
            delay(4200)
            voiceIssue = null
        }
    }

    BackHandler(enabled = screen != Screen.Home) {
        screen = Screen.Home
    }

    Box(Modifier.fillMaxSize().background(Ink)) {
        GlowBackground()
        when (screen) {
            Screen.Home -> HomeScreen(
                host = config.host,
                port = config.port,
                configured = config.configured,
                online = online,
                listening = listening,
                issue = voiceIssue,
                latest = messages.lastOrNull(),
                onTapLogo = {
                    if (!config.configured) screen = Screen.Settings else screen = Screen.Typing
                },
                onLongPressLogo = {
                    if (!config.configured) {
                        voiceIssue = "先设置 ZeroTier 主机地址"
                        screen = Screen.Settings
                    } else {
                        startVoice()
                    }
                },
                onHistory = { screen = Screen.History },
                onSettings = { screen = Screen.Settings },
            )

            Screen.Typing -> TypingScreen(
                initial = "",
                onClose = { screen = Screen.Home },
                onSend = { sendText(it) },
            )

            Screen.Settings -> SettingsScreen(
                config = config,
                onClose = { screen = Screen.Home; configEpoch++ },
                onSaved = { configEpoch++ },
            )

            Screen.History -> ChatHistoryScreen(
                messages = messages,
                onClose = { screen = Screen.Home },
                onWithdraw = { withdrawTarget = it },
            )
        }
    }

    if (withdrawTarget != null) {
        AlertDialog(
            onDismissRequest = { withdrawTarget = null },
            title = { Text("撤回消息") },
            text = { Text("从本地记录撤回，并尽量停止主机上的执行。") },
            confirmButton = {
                TextButton(onClick = {
                    val id = withdrawTarget
                    withdrawTarget = null
                    if (id != null) {
                        store.withdraw(id)
                        ChatSyncService.start(context)
                        scope.launch { client.withdraw(id) }
                    }
                }) { Text("撤回", color = Danger) }
            },
            dismissButton = {
                TextButton(onClick = { withdrawTarget = null }) { Text("取消") }
            },
        )
    }
}

/** Preview/test entry point: a completed conversation on a 466x466 round screen. */
@Composable
fun DshConversationPreview(
    user: String = "看一下 D 盘还剩多少空间",
    reply: String = "D 盘剩余 214 GB，共 512 GB。\n\n需要我顺便把下载目录里超过 100MB 的文件列出来吗？",
    status: String? = null,
) {
    Box(Modifier.fillMaxSize().background(Ink)) {
        GlowBackground()
        ConversationScreen(
            chat = Chat(user = user, reply = reply, phase = if (status == null) Phase.Done else Phase.Sending, status = status),
            onClose = {},
            onStop = {},
            onVoice = {},
        )
    }
}

/** Preview/test entry point: the visual home screen without a live bridge. */
@Composable
fun DshHomePreview(
    host: String = "10.42.0.7",
    port: Int = 18787,
    configured: Boolean = true,
    online: Boolean? = true,
    listening: Boolean = false,
    issue: String? = null,
) {
    Box(Modifier.fillMaxSize().background(Ink)) {
        GlowBackground()
        HomeScreen(
            host = host,
            port = port,
            configured = configured,
            online = online,
            listening = listening,
            issue = issue,
            onTapLogo = {},
            onLongPressLogo = {},
            onSettings = {},
        )
    }
}

@Composable
private fun GlowBackground() {
    Canvas(Modifier.fillMaxSize()) {
        drawRect(Brush.verticalGradient(listOf(Ink, Color(0xFF070A12), Ink)))
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(DshBlue.copy(alpha = 0.20f), Color.Transparent),
                center = center,
                radius = size.minDimension * 0.55f,
            ),
            radius = size.minDimension * 0.55f,
            center = center,
        )
        drawCircle(
            color = Color.White.copy(alpha = 0.035f),
            radius = size.minDimension * 0.455f,
            center = center,
            style = Stroke(width = 1.dp.toPx()),
        )
        drawCircle(
            color = DshBlue.copy(alpha = 0.05f),
            radius = size.minDimension * 0.405f,
            center = center,
            style = Stroke(width = 1.dp.toPx()),
        )
    }
}

@Composable
fun HomeScreen(
    host: String,
    port: Int,
    configured: Boolean,
    online: Boolean?,
    listening: Boolean,
    issue: String?,
    latest: ChatMessage? = null,
    onTapLogo: () -> Unit,
    onLongPressLogo: () -> Unit,
    onHistory: () -> Unit = {},
    onSettings: () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val unit = min(maxWidth.value, maxHeight.value).dp
        val logo = unit * 0.42f
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    start = unit * 0.15f,
                    end = unit * 0.15f,
                    top = unit * 0.05f,
                    bottom = unit * 0.16f,
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            StatusPill(
                label = when {
                    !configured -> "未设置主机"
                    online == true -> "主机在线 · $host"
                    online == false -> "主机离线 · 消息会待命"
                    else -> "正在连接 · $host"
                },
                color = when (online) {
                    true -> Color(0xFF63E6A6)
                    false -> Danger
                    null -> TextFaint
                },
            )
            Spacer(Modifier.weight(0.35f))
            PressableLogo(
                size = logo,
                listening = listening,
                onTap = onTapLogo,
                onLongPress = onLongPressLogo,
            )
            Spacer(Modifier.height(unit * 0.022f))
            Text(
                text = "DEEPSEEK HARNESS",
                color = TextPrimary,
                fontSize = (unit.value * 0.043f).sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 1.4.sp,
                maxLines = 1,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(unit * 0.012f))
            Text(
                text = when {
                    listening -> "正在聆听…"
                    !configured -> "点按设置主机地址"
                    latest == null && online == false -> "网络离线 · 发送会待命补发"
                    latest == null -> "按住说话 · 点击输入"
                    latest.withdrawn -> "已撤回 · 点击输入新指令"
                    latest.pending -> "待发送 · 等待网络"
                    latest.status == ChatMessage.STATUS_SENT -> "已发送 · 等待主机接收"
                    latest.running -> latest.phase ?: "DSH 正在处理…"
                    latest.status == ChatMessage.STATUS_ERROR -> "发送失败 · 点记录查看"
                    latest.finished -> "收到回复 · 点记录查看"
                    else -> "按住说话 · 点击输入"
                },
                color = when {
                    listening -> DshCyan
                    latest != null && (latest.pending || latest.running || latest.status == ChatMessage.STATUS_SENT) -> DshCyan
                    latest != null && latest.status == ChatMessage.STATUS_ERROR -> Danger
                    else -> TextMuted
                },
                fontSize = (unit.value * 0.036f).sp,
                maxLines = 1,
                textAlign = TextAlign.Center,
            )
            if (issue != null) {
                Spacer(Modifier.height(unit * 0.02f))
                Text(
                    text = issue,
                    color = Danger,
                    fontSize = (unit.value * 0.034f).sp,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.weight(0.25f))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (configured) "$host:$port" else "DSH REMOTE",
                    color = TextFaint,
                    fontSize = (unit.value * 0.032f).sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(4.dp))
                HistoryButton(onClick = onHistory)
                Spacer(Modifier.width(4.dp))
                GearButton(onClick = onSettings)
            }
        }
    }
}

@Composable
private fun StatusPill(label: String, color: Color) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(Color.White.copy(alpha = 0.055f))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(5.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(Modifier.width(5.dp))
        Text(
            text = label,
            color = TextMuted,
            fontSize = 8.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun PressableLogo(
    size: Dp,
    listening: Boolean,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    var pressed by remember { mutableStateOf(false) }
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.94f else 1f,
        animationSpec = tween(120),
        label = "logo-press",
    )
    val pulseTransition = rememberInfiniteTransition(label = "logo-pulse")
    val pulse by pulseTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (listening) 1.07f else 1.025f,
        animationSpec = infiniteRepeatable(
            animation = tween(1100, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "logo-pulse-value",
    )

    Box(
        modifier = Modifier
            .size(size)
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false).consume()
                    pressed = true
                    try {
                        val up = withTimeoutOrNull(380L) { waitForUpOrCancellation() }
                        if (up == null) {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onLongPress()
                            waitForUpOrCancellation()
                        } else {
                            onTap()
                        }
                    } finally {
                        pressed = false
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        DshBlue.copy(alpha = if (listening) 0.42f else 0.24f),
                        Color.Transparent,
                    ),
                    center = center,
                    radius = this.size.minDimension * 0.50f,
                ),
                radius = this.size.minDimension * 0.50f,
                center = center,
            )
            if (listening) {
                drawCircle(
                    color = DshCyan.copy(alpha = 0.35f),
                    radius = this.size.minDimension * 0.44f,
                    center = center,
                    style = Stroke(width = 1.2.dp.toPx()),
                )
            }
        }
        androidx.compose.foundation.Image(
            painter = painterResource(R.drawable.ic_dsh_logo),
            contentDescription = "DeepSeek Harness",
            modifier = Modifier
                .size(size * 0.86f)
                .graphicsLayerScale(pressScale * pulse),
        )
    }
}

@Composable
private fun GearButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(30.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.05f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(13.dp)) {
            val stroke = 1.3.dp.toPx()
            for (i in 0..2) {
                val y = size.height * (0.25f + i * 0.25f)
                drawLine(
                    color = TextMuted,
                    start = Offset(0f, y),
                    end = Offset(size.width, y),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round,
                )
                val knobX = size.width * (0.28f + i * 0.20f)
                drawCircle(color = DshCyan, radius = 1.7.dp.toPx(), center = Offset(knobX, y))
            }
        }
    }
}

@Composable
private fun HistoryButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(30.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.05f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(14.dp)) {
            val stroke = 1.2.dp.toPx()
            drawRoundRect(
                color = TextMuted,
                topLeft = Offset(1f, 1.5f),
                size = androidx.compose.ui.geometry.Size(size.width * 0.78f, size.height * 0.62f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.4f.dp.toPx()),
                style = Stroke(width = stroke),
            )
            drawRoundRect(
                color = DshCyan,
                topLeft = Offset(size.width * 0.40f, size.height * 0.36f),
                size = androidx.compose.ui.geometry.Size(size.width * 0.58f, size.height * 0.52f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.4f.dp.toPx()),
                style = Stroke(width = stroke),
            )
        }
    }
}

@Composable
private fun TypingScreen(
    initial: String,
    onClose: () -> Unit,
    onSend: (String) -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    fun submit() {
        val text = value.trim()
        if (text.isEmpty()) return
        value = ""
        keyboard?.hide()
        onSend(text)
    }

    LaunchedEffect(Unit) {
        delay(180)
        focus.requestFocus()
        keyboard?.show()
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val unit = min(maxWidth.value, maxHeight.value).dp
        Column(
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
                .padding(horizontal = unit * 0.10f, vertical = unit * 0.07f),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("输入指令", color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.weight(1f))
                SmallClose(onClick = onClose)
            }
            Spacer(Modifier.height(10.dp))
            BasicTextField(
                value = value,
                onValueChange = { if (it.length <= 2000) value = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Panel)
                    .padding(12.dp)
                    .focusRequester(focus),
                textStyle = TextStyle(color = TextPrimary, fontSize = 13.sp, lineHeight = 18.sp),
                cursorBrush = SolidColor(DshBlue),
                maxLines = 4,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Send,
                ),
                keyboardActions = KeyboardActions(
                    onSend = { submit() },
                    onDone = { submit() },
                ),
                decorationBox = { inner ->
                    Box(Modifier.fillMaxSize()) {
                        if (value.isEmpty()) {
                            Text(
                                "例如：帮我看一下 D 盘剩余空间，然后整理下载目录",
                                color = TextFaint,
                                fontSize = 12.sp,
                                lineHeight = 17.sp,
                            )
                        }
                        inner()
                    }
                },
            )
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("${value.length}/2000", color = TextFaint, fontSize = 8.sp)
                Spacer(Modifier.weight(1f))
                SendButton(enabled = value.isNotBlank()) { submit() }
            }
        }
    }
}

@Composable
private fun ConversationScreen(
    chat: Chat,
    onClose: () -> Unit,
    onStop: () -> Unit,
    onVoice: () -> Unit,
) {
    val scroll = rememberScrollState()
    val busy = chat.phase == Phase.Sending || chat.phase == Phase.Listening

    LaunchedEffect(chat.reply, chat.phase) {
        delay(40)
        scroll.animateScrollTo(scroll.maxValue)
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val unit = min(maxWidth.value, maxHeight.value).dp
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    start = unit * 0.18f,
                    end = unit * 0.18f,
                    top = unit * 0.12f,
                    bottom = unit * 0.12f,
                ),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("DSH", color = DshBlue, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Spacer(Modifier.width(6.dp))
                Text(
                    when (chat.phase) {
                        Phase.Sending -> "执行中"
                        Phase.Error -> "出错"
                        Phase.Listening -> "聆听中"
                        else -> "已完成"
                    },
                    color = when (chat.phase) {
                        Phase.Error -> Danger
                        Phase.Sending, Phase.Listening -> DshCyan
                        else -> Color(0xFF63E6A6)
                    },
                    fontSize = 8.sp,
                )
                Spacer(Modifier.weight(1f))
                SmallClose(onClick = onClose)
            }
            Spacer(Modifier.height(6.dp))
            Text("你", color = TextFaint, fontSize = 8.sp)
            Text(
                chat.user,
                color = TextMuted,
                fontSize = 9.sp,
                lineHeight = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(unit * 0.30f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.White.copy(alpha = 0.045f))
                    .padding(10.dp),
            ) {
                Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
                    val text = when {
                        chat.error != null -> chat.error
                        chat.reply.isNotBlank() -> chat.reply
                        chat.phase == Phase.Sending -> chat.status ?: "正在思考…"
                        else -> "（没有返回内容）"
                    }
                    Text(
                        text = text,
                        color = if (chat.error != null) Danger else TextPrimary,
                        fontSize = 9.sp,
                        lineHeight = 12.sp,
                    )
                    if (chat.phase == Phase.Sending && chat.reply.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text("▍", color = DshCyan, fontSize = 11.sp)
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (busy) {
                    PillButton("停止", filled = false, onClick = onStop)
                } else {
                    PillButton("关闭", filled = false, onClick = onClose)
                }
                Spacer(Modifier.weight(1f))
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(DshBlue)
                        .clickable(onClick = onVoice),
                    contentAlignment = Alignment.Center,
                ) {
                    Canvas(Modifier.size(15.dp)) {
                        drawCircle(color = Color.White, radius = size.minDimension * 0.28f, center = Offset(size.width / 2, size.height * 0.34f))
                        drawArc(
                            color = Color.White,
                            startAngle = 0f,
                            sweepAngle = 180f,
                            useCenter = false,
                            topLeft = Offset(size.width * 0.22f, size.height * 0.22f),
                            size = androidx.compose.ui.geometry.Size(size.width * 0.56f, size.height * 0.56f),
                            style = Stroke(width = 1.4.dp.toPx(), cap = StrokeCap.Round),
                        )
                        drawLine(
                            color = Color.White,
                            start = Offset(size.width / 2, size.height * 0.72f),
                            end = Offset(size.width / 2, size.height * 0.92f),
                            strokeWidth = 1.4.dp.toPx(),
                            cap = StrokeCap.Round,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(
    config: WatchConfig,
    onClose: () -> Unit,
    onSaved: () -> Unit,
) {
    var host by remember { mutableStateOf(config.host) }
    var port by remember { mutableStateOf(config.port.toString()) }
    var token by remember { mutableStateOf(config.token) }
    var status by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val unit = min(maxWidth.value, maxHeight.value).dp
        Column(
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
                .padding(
                    start = unit * 0.12f,
                    end = unit * 0.12f,
                    top = unit * 0.10f,
                    bottom = unit * 0.10f,
                )
                .verticalScroll(rememberScrollState()),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("主机设置", color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.weight(1f))
                SmallClose(onClick = onClose)
            }
            Spacer(Modifier.height(8.dp))
            LabeledField(
                label = "ZeroTier IP / 主机名",
                value = host,
                onValueChange = { host = it.trim() },
                keyboardType = KeyboardType.Text,
            )
            Spacer(Modifier.height(6.dp))
            LabeledField(
                label = "端口",
                value = port,
                onValueChange = { port = it.filter(Char::isDigit).take(5) },
                keyboardType = KeyboardType.Number,
            )
            Spacer(Modifier.height(6.dp))
            LabeledField(
                label = "令牌（可留空）",
                value = token,
                onValueChange = { token = it },
                keyboardType = KeyboardType.Text,
            )
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                PillButton(
                    text = if (testing) "测试中…" else "保存并测试",
                    filled = true,
                    enabled = !testing,
                    onClick = {
                        val parsedPort = port.toIntOrNull()
                        if (host.isBlank() || parsedPort == null || parsedPort !in 1..65535) {
                            status = "地址或端口不正确"
                            return@PillButton
                        }
                        config.host = host
                        config.port = parsedPort
                        config.token = token
                        onSaved()
                        keyboard?.hide()
                        testing = true
                        status = null
                        scope.launch {
                            val ok = config.configured && com.deepseek.dshwatch.DshClient(config).health()
                            testing = false
                            status = if (ok) "已连接，可以开始使用" else "已保存，但暂时连不上主机"
                        }
                    },
                )
            }
            if (status != null) {
                Spacer(Modifier.height(7.dp))
                Text(
                    status ?: "",
                    color = if (status?.startsWith("已连接") == true) Color(0xFF63E6A6) else Danger,
                    fontSize = 9.sp,
                    lineHeight = 13.sp,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "提示：主机与手表需在同一个 ZeroTier 网络；Windows 防火墙放行 8787/TCP。",
                color = TextFaint,
                fontSize = 8.sp,
                lineHeight = 12.sp,
            )
        }
    }
}

@Composable
private fun LabeledField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    keyboardType: KeyboardType,
) {
    Column {
        Text(label, color = TextMuted, fontSize = 8.sp)
        Spacer(Modifier.height(3.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(34.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(Panel)
                .padding(horizontal = 9.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            AndroidView(
                factory = { ctx ->
                    EditText(ctx).apply {
                        setSingleLine(true)
                        background = null
                        setPadding(0, 0, 0, 0)
                        setTextColor(android.graphics.Color.WHITE)
                        setHintTextColor(0xFF5B6377.toInt())
                        textSize = 11f
                        includeFontPadding = false
                        minHeight = 0
                        minimumHeight = 0
                        inputType = when (keyboardType) {
                            KeyboardType.Number -> InputType.TYPE_CLASS_NUMBER
                            else -> InputType.TYPE_CLASS_TEXT
                        }
                        imeOptions = EditorInfo.IME_ACTION_DONE
                        setText(value)
                        setSelection(text.length)
                        addTextChangedListener(object : android.text.TextWatcher {
                            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                                onValueChange(s?.toString().orEmpty())
                            }
                            override fun afterTextChanged(s: android.text.Editable?) = Unit
                        })
                    }
                },
                update = { edit ->
                    if (edit.text.toString() != value) {
                        edit.setText(value)
                        edit.setSelection(value.length)
                    }
                },
                modifier = Modifier.fillMaxWidth().requiredHeight(34.dp),
            )
        }
    }
}

@Composable
private fun SmallClose(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(26.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.06f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text("×", color = TextMuted, fontSize = 15.sp)
    }
}

@Composable
private fun SendButton(enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(if (enabled) DshBlue else Panel)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text("↑", color = if (enabled) Color.White else TextFaint, fontSize = 19.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun PillButton(
    text: String,
    filled: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .height(32.dp)
            .clip(RoundedCornerShape(50))
            .background(if (filled) DshBlue else Color.White.copy(alpha = 0.06f))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = if (filled) Color.White else TextMuted,
            fontSize = 9.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
    }
}

// A tiny local graphicsLayer helper keeps call sites readable.
private fun Modifier.graphicsLayerScale(scale: Float): Modifier =
    this.then(Modifier.graphicsLayer(scaleX = scale, scaleY = scale))
