package com.deepseek.dshwatch.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.deepseek.dshwatch.ChatMessage
import kotlinx.coroutines.delay
import kotlin.math.min

@Composable
fun ChatHistoryScreen(
    messages: List<ChatMessage>,
    onClose: () -> Unit,
    onWithdraw: (String) -> Unit,
) {
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size, messages.lastOrNull()?.updatedAt) {
        if (messages.isNotEmpty()) {
            delay(30)
            listState.animateScrollToItem(messages.lastIndex)
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val unit = min(maxWidth.value, maxHeight.value).dp
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    start = unit * 0.10f,
                    end = unit * 0.10f,
                    top = unit * 0.11f,
                    bottom = unit * 0.07f,
                ),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("对话记录", color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.weight(1f))
                Text("${messages.size}", color = TextFaint, fontSize = 8.sp)
                Spacer(Modifier.padding(horizontal = 5.dp))
                HistoryClose(onClose)
            }
            Spacer(Modifier.height(7.dp))
            if (messages.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("还没有记录", color = TextFaint, fontSize = 10.sp)
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    verticalArrangement = Arrangement.spacedBy(7.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 8.dp),
                ) {
                    items(items = messages, key = { it.id }) { message ->
                        HistoryItem(message, onWithdraw)
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryItem(message: ChatMessage, onWithdraw: (String) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("你", color = TextFaint, fontSize = 8.sp)
            Spacer(Modifier.weight(1f))
            Text(
                statusLabel(message),
                color = statusColor(message),
                fontSize = 8.sp,
                maxLines = 1,
            )
            if (!message.withdrawn) {
                Spacer(Modifier.padding(horizontal = 3.dp))
                Text(
                    "撤回",
                    color = Danger,
                    fontSize = 8.sp,
                    modifier = Modifier.clickable { onWithdraw(message.id) },
                )
            }
        }
        Spacer(Modifier.height(2.dp))
        Text(
            message.text,
            color = if (message.withdrawn) TextFaint else TextPrimary,
            fontSize = 10.sp,
            lineHeight = 14.sp,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(9.dp))
                .background(if (message.withdrawn) Color.White.copy(alpha = 0.03f) else DshBlue.copy(alpha = 0.18f))
                .padding(horizontal = 8.dp, vertical = 6.dp),
        )
        if (message.withdrawn) {
            Spacer(Modifier.height(3.dp))
            Text("已撤回", color = TextFaint, fontSize = 8.sp)
        } else if (message.reply.isNotBlank()) {
            Spacer(Modifier.height(3.dp))
            Text(
                message.reply,
                color = TextPrimary,
                fontSize = 9.sp,
                lineHeight = 13.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(9.dp))
                    .background(Color.White.copy(alpha = 0.04f))
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            )
        } else if (message.status == ChatMessage.STATUS_RUNNING) {
            Spacer(Modifier.height(3.dp))
            Text(
                message.phase ?: "DSH 正在处理…",
                color = DshCyan,
                fontSize = 8.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        } else if (message.status == ChatMessage.STATUS_ERROR) {
            Spacer(Modifier.height(3.dp))
            Text(
                message.error ?: "发送失败",
                color = Danger,
                fontSize = 8.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun statusLabel(message: ChatMessage): String = when (message.status) {
    ChatMessage.STATUS_QUEUED -> "待发送"
    ChatMessage.STATUS_SENT -> "已发送"
    ChatMessage.STATUS_RUNNING -> "执行中"
    ChatMessage.STATUS_DONE -> "完成"
    ChatMessage.STATUS_ERROR -> "失败"
    ChatMessage.STATUS_WITHDRAWN -> "已撤回"
    else -> message.status
}

private fun statusColor(message: ChatMessage): Color = when (message.status) {
    ChatMessage.STATUS_QUEUED, ChatMessage.STATUS_SENT, ChatMessage.STATUS_RUNNING -> DshCyan
    ChatMessage.STATUS_ERROR -> Danger
    ChatMessage.STATUS_DONE -> Color(0xFF63E6A6)
    else -> TextFaint
}

@Composable
private fun HistoryClose(onClick: () -> Unit) {
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
