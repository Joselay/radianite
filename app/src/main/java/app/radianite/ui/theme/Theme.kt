package app.radianite.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val ValorantRed = Color(0xFFFF4655)
val Ink = Color(0xFF0F1923)
val InkRaised = Color(0xFF1B2733)
val Bone = Color(0xFFECE8E1)
val Muted = Color(0xFF8B978F)

private val colors = darkColorScheme(
    primary = ValorantRed,
    onPrimary = Color.White,
    background = Ink,
    onBackground = Bone,
    surface = Ink,
    onSurface = Bone,
    surfaceVariant = InkRaised,
    onSurfaceVariant = Muted,
    surfaceContainer = InkRaised,
    error = ValorantRed,
)

@Composable
fun RadianiteTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, content = content)
}

/** Parses valorant-api.com's RRGGBBAA hex into a Compose color. */
fun rgbaColor(hex: String?): Color? {
    val value = hex?.toLongOrNull(16) ?: return null
    if (hex.length != 8) return null
    return Color(
        red = ((value shr 24) and 0xFF).toInt(),
        green = ((value shr 16) and 0xFF).toInt(),
        blue = ((value shr 8) and 0xFF).toInt(),
        alpha = (value and 0xFF).toInt(),
    )
}
