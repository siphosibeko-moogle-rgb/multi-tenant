package com.example.inventory.mobile.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object Mono {
    val Black = Color(0xFF000000)
    val Grey15 = Color(0xFF262626)
    val Grey35 = Color(0xFF595959)
    val Grey55 = Color(0xFF8C8C8C)
    val Grey80 = Color(0xFFCCCCCC)
    val Grey90 = Color(0xFFE6E6E6)
    val Grey94 = Color(0xFFF0F0F0)
    val Grey97 = Color(0xFFF7F7F7)
    val White = Color(0xFFFFFFFF)
}

internal val MonochromeLight: ColorScheme = lightColorScheme(
    primary = Mono.Black,
    onPrimary = Mono.White,
    primaryContainer = Mono.Grey90,
    onPrimaryContainer = Mono.Black,
    inversePrimary = Mono.Grey80,
    secondary = Mono.Grey15,
    onSecondary = Mono.White,
    secondaryContainer = Mono.Grey90,
    onSecondaryContainer = Mono.Black,
    tertiary = Mono.Grey15,
    onTertiary = Mono.White,
    tertiaryContainer = Mono.Grey90,
    onTertiaryContainer = Mono.Black,
    background = Mono.White,
    onBackground = Mono.Black,
    surface = Mono.White,
    onSurface = Mono.Black,
    surfaceVariant = Mono.Grey94,
    onSurfaceVariant = Mono.Grey35,
    surfaceTint = Mono.Grey55,
    inverseSurface = Mono.Grey15,
    inverseOnSurface = Mono.White,
    error = Mono.Black,
    onError = Mono.White,
    errorContainer = Mono.Grey90,
    onErrorContainer = Mono.Black,
    outline = Mono.Grey55,
    outlineVariant = Mono.Grey80,
    scrim = Mono.Black,
    surfaceBright = Mono.White,
    surfaceDim = Mono.Grey90,
    surfaceContainerLowest = Mono.White,
    surfaceContainerLow = Mono.Grey97,
    surfaceContainer = Mono.Grey94,
    surfaceContainerHigh = Mono.Grey90,
    surfaceContainerHighest = Mono.Grey90,
)

@Composable
fun InventoryTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = MonochromeLight, content = content)
}
