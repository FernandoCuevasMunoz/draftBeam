package cl.fernandocuevas.draftbeam.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val TokyoNightColorScheme = darkColorScheme(
    primary = Color(0xFF7AA2F7),
    onPrimary = Color(0xFF16161E),
    secondary = Color(0xFFBB9AF7),
    onSecondary = Color(0xFF1A1B26),
    tertiary = Color(0xFF7DCFFF),
    onTertiary = Color(0xFF1A1B26),
    error = Color(0xFFF7768E),
    onError = Color(0xFF1A1B26),
    background = Color(0xFF1A1B26),
    onBackground = Color(0xFFC0CAF5),
    surface = Color(0xFF1A1B26),
    onSurface = Color(0xFFC0CAF5),
    surfaceVariant = Color(0xFF24283B),
    onSurfaceVariant = Color(0xFFA9B1D6),
    outline = Color(0xFF565F89)
)

@Composable
fun DraftBeamTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = TokyoNightColorScheme,
        typography = Typography,
        content = content
    )
}
