package com.deepseek.dshwatch

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.deepseek.dshwatch.ui.DshTheme
import com.deepseek.dshwatch.ui.DshWatchApp

class MainActivity : ComponentActivity() {
    private lateinit var config: WatchConfig
    private lateinit var client: DshClient

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        config = WatchConfig(this)
        applyConfig(intent)
        client = DshClient(config)
        setContent {
            DshTheme {
                DshWatchApp(config = config, client = client)
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyConfig(intent)
    }

    /**
     * Allows the PC installer to preconfigure the watch over ADB:
     *   adb shell am start -n com.deepseek.dshwatch/.MainActivity \
     *     --es dsh_host 10.42.0.7 --ei dsh_port 18787 --es dsh_token change-me
     */
    private fun applyConfig(intent: android.content.Intent?) {
        val host = intent?.getStringExtra("dsh_host")?.trim().orEmpty()
        if (host.isEmpty()) return
        config.host = host
        if (intent!!.hasExtra("dsh_port")) {
            config.port = intent.getIntExtra("dsh_port", config.port)
        }
        intent.getStringExtra("dsh_token")?.let { config.token = it }
    }

    override fun onDestroy() {
        client.cancel()
        super.onDestroy()
    }
}
