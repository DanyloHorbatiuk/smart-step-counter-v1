package com.example.smartstepcounter.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

val StillColor = Color(0xFF78909C)
val WalkingColor = Color(0xFF43A047)
val RunningColor = Color(0xFFE53935)
val ShakeColor = Color(0xFF8E24AA)

val AxisXColor = Color(0xFFE53935)
val AxisYColor = Color(0xFF43A047)
val AxisZColor = Color(0xFF1E88E5)

@Composable
fun SmartStepCounterTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    // minSdk is 31, so Material You dynamic colors are always available.
    val context = LocalContext.current
    val colorScheme = if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    MaterialTheme(colorScheme = colorScheme, content = content)
}
