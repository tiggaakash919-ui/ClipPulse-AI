package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

private val CreatorDarkColorScheme = darkColorScheme(
    primary = ElectricVolt,
    onPrimary = ObsidianInk,
    primaryContainer = ElectricVoltDim,
    onPrimaryContainer = ObsidianInk,
    secondary = CyberCyan,
    onSecondary = ObsidianInk,
    secondaryContainer = CyberCyanContainer,
    onSecondaryContainer = CyberCyan,
    tertiary = CrimsonLive,
    onTertiary = TextPrimary,
    tertiaryContainer = CrimsonContainer,
    onTertiaryContainer = CrimsonLive,
    background = StudioObsidian,
    onBackground = TextPrimary,
    surface = BroadcastSlate,
    onSurface = TextPrimary,
    surfaceVariant = ElevatedCard,
    onSurfaceVariant = TextSecondary,
    surfaceContainerLowest = StudioObsidian,
    surfaceContainerLow = BroadcastSlate,
    surfaceContainer = ElevatedCard,
    surfaceContainerHigh = SurfaceHighest,
    outline = SteelOutline,
    outlineVariant = SteelOutline,
    error = CrimsonLive,
    onError = TextPrimary
)

val ClipPulseShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun ClipPulseTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = CreatorDarkColorScheme,
        typography = ClipPulseTypography,
        shapes = ClipPulseShapes,
        content = content
    )
}
