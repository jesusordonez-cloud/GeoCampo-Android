package mx.org.fraac.geocampo

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF176B57),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD8F2E7),
    onPrimaryContainer = Color(0xFF06251D),
    secondary = Color(0xFF4E635B),
    tertiary = Color(0xFF526A92),
    background = Color(0xFFF6F8F4),
    surface = Color(0xFFFCFDF9),
    surfaceVariant = Color(0xFFE4E9E4)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF88D5BB),
    onPrimary = Color(0xFF00382B),
    primaryContainer = Color(0xFF00513F),
    secondary = Color(0xFFB5CCC2),
    tertiary = Color(0xFFB8C7ED)
)

@Composable
fun GeoCampoTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content
    )
}
