package cl.fernandocuevas.draftbeam.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable

import androidx.compose.material3.darkColorScheme

private val DraftBeamColorScheme = darkColorScheme(
    primary = DraftBeamColors.Primary,
    onPrimary = DraftBeamColors.TextPrimary,
    secondary = DraftBeamColors.Violet,
    onSecondary = DraftBeamColors.TextPrimary,
    tertiary = DraftBeamColors.LaserCyan,
    onTertiary = DraftBeamColors.BackgroundDeep,
    error = DraftBeamColors.Error,
    onError = DraftBeamColors.BackgroundDeep,
    background = DraftBeamColors.Background,
    onBackground = DraftBeamColors.TextPrimary,
    surface = DraftBeamColors.Surface,
    onSurface = DraftBeamColors.TextPrimary,
    surfaceVariant = DraftBeamColors.PanelBackground,
    onSurfaceVariant = DraftBeamColors.TextSecondary,
    outline = DraftBeamColors.Border
)

@Composable
fun DraftBeamTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DraftBeamColorScheme,
        typography = Typography,
        content = content
    )
}
