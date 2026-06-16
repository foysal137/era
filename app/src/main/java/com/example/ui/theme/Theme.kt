package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val DarkColorScheme = darkColorScheme(
    primary = CosmicTeal,
    secondary = SlateCard,
    tertiary = MysticMagenta,
    background = DeepSlateNoir,
    surface = SlateCard,
    onPrimary = DeepSlateNoir,
    onSecondary = PureWhite,
    onBackground = PureWhite,
    onSurface = PureWhite
)

@Composable
fun MyApplicationTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = Typography,
        content = content
    )
}

