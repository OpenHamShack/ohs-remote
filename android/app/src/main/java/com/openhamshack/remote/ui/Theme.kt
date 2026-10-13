package com.openhamshack.remote.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** ON AIR is red everywhere, as on the console; nothing else is. */
val OnAirRed = Color(0xFFE53935)
val Amber = Color(0xFFFFB300)
val LockGreen = Color(0xFF66BB6A)
val Dim = Color(0xFF9E9E9E)

@Composable
fun OpenHamShackTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFF4FC3F7),
            onPrimary = Color.Black,
            secondary = Amber,
            background = Color.Black,
            surface = Color(0xFF121212),
            surfaceVariant = Color(0xFF1E1E1E),
            error = OnAirRed,
        ),
        content = content,
    )
}
