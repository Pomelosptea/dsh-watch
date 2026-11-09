package com.deepseek.dshwatch

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground mailbox worker:
 *  - drains locally queued messages whenever the link returns;
 *  - long-polls /api/events so replies arrive even when the UI is in background;
 *  - persists everything through ChatStore, so a process restart resumes.
 */
class ChatSyncService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loop: Job? = null

    override fun onCreate() {
        super.onCreate()
        AppGraph.init(applicationContext)
        createChannel()
        startForeground(
            1001,
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_dsh_logo)
                .setContentTitle("DSH 持续收信")
                .setContentText("后台等待发送并接收 DeepSeek 回复")
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setContentIntent(
                    PendingIntent.getActivity(
                        this,
                        0,
                        Intent(this, MainActivity::class.java),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    )
                )
                .build(),
        )
        startLoop()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startLoop()
        return START_STICKY
    }

    private fun startLoop() {
        if (loop?.isActive == true) return
        loop = scope.launch {
            while (isActive) {
                try {
                    drainOutbox()
                    val batch = AppGraph.client.events(AppGraph.store.lastSeq, 20)
                    if (batch == null) {
                        delay(5_000)
                    } else if (batch.events.length() > 0) {
                        AppGraph.store.applyServerEvents(batch.events, batch.lastSeq)
                    }
                } catch (_: Exception) {
                    delay(5_000)
                }
            }
        }
    }

    private suspend fun drainOutbox() {
        for (message in AppGraph.store.pending()) {
            when (AppGraph.client.enqueue(message.id, message.text)) {
                EnqueueResult.OK -> AppGraph.store.markSent(message.id)
                EnqueueResult.WITHDRAWN -> AppGraph.store.withdraw(message.id)
                EnqueueResult.FAILED -> {
                    delay(5_000)
                    return
                }
            }
        }
    }

    private fun createChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(CHANNEL_ID, "DSH 后台收信", NotificationManager.IMPORTANCE_LOW).apply {
            description = "持续发送待发消息并接收 DeepSeek 回复"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "dsh_sync"

        fun start(context: Context) {
            val intent = Intent(context, ChatSyncService::class.java)
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }
    }
}
