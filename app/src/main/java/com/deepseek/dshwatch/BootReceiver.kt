package com.deepseek.dshwatch

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Resume the mailbox worker after a watch reboot. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED) {
            ChatSyncService.start(context)
        }
    }
}
