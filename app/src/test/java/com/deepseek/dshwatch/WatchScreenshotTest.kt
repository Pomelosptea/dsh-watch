package com.deepseek.dshwatch

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.Density
import com.android.resources.ScreenRound
import com.deepseek.dshwatch.ui.DshConversationPreview
import com.deepseek.dshwatch.ui.DshHomePreview
import com.deepseek.dshwatch.ui.DshTheme
import org.junit.Rule
import org.junit.Test

class WatchScreenshotTest {

    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.WEAR_OS_SMALL_ROUND.copy(
            screenWidth = 466,
            screenHeight = 466,
            density = Density.XHIGH,
            screenRound = ScreenRound.ROUND,
            softButtons = false,
        ),
        theme = "android:style/Theme.Material.NoActionBar",
        showSystemUi = false,
    )

    @Test
    fun home() {
        paparazzi.snapshot {
            DshTheme {
                DshHomePreview()
            }
        }
    }

    @Test
    fun homeListening() {
        paparazzi.snapshot {
            DshTheme {
                DshHomePreview(listening = true)
            }
        }
    }

    @Test
    fun conversation() {
        paparazzi.snapshot {
            DshTheme {
                DshConversationPreview()
            }
        }
    }
}
