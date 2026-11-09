package com.deepseek.dshwatch.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val DshBlue = Color(0xFF4D6BFE)
val DshCyan = Color(0xFF79E2FF)
val Ink = Color(0xFF05060A)
val Panel = Color(0xFF0D101A)
val TextPrimary = Color(0xFFF4F7FF)
val TextMuted = Color(0xFF8B93AA)
val TextFaint = Color(0xFF5B6377)
val Danger = Color(0xFFFF7A8A)

private val DshColors = darkColorScheme(
    primary = DshBlue,
    onPrimary = Color.White,
    secondary = DshCyan,
    background = Ink,
    onBackground = TextPrimary,
    surface = Panel,
    onSurface = TextPrimary,
    error = Danger,
)

private val DshType = Typography(
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        color = TextPrimary,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        color = TextPrimary,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 9.sp,
        lineHeight = 12.sp,
        letterSpacing = 0.6.sp,
        color = TextMuted,
    ),
)

@Composable
fun DshTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DshColors, typography = DshType, content = content)
}
