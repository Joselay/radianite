package app.radianite.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.radianite.data.content.GameContent
import app.radianite.data.match.Outcome
import app.radianite.ui.theme.Display
import app.radianite.ui.theme.InkRaised
import app.radianite.ui.theme.Mono
import app.radianite.ui.theme.Muted
import app.radianite.ui.theme.ValorantRed
import app.radianite.ui.theme.rgbaColor
import coil3.compose.AsyncImage

val WinGreen = Color(0xFF3DDC97)
val DrawGrey = Color(0xFFB0B8B4)

fun Outcome.color(): Color = when (this) {
    Outcome.WIN -> WinGreen
    Outcome.LOSS -> ValorantRed
    Outcome.DRAW -> DrawGrey
}

fun Outcome.label(): String = when (this) {
    Outcome.WIN -> "VICTORY"
    Outcome.LOSS -> "DEFEAT"
    Outcome.DRAW -> "DRAW"
}

/** Small monospace caps label, the HUD style used for section titles. */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        fontFamily = Mono,
        fontSize = 11.sp,
        letterSpacing = 1.5.sp,
        color = Muted,
        modifier = modifier.padding(top = 16.dp, bottom = 8.dp),
    )
}

@Composable
fun MessageBanner(message: String, modifier: Modifier = Modifier) {
    Text(
        message,
        color = Color.White,
        style = MaterialTheme.typography.bodyMedium,
        modifier = modifier
            .fillMaxWidth()
            .background(ValorantRed.copy(alpha = 0.25f), RoundedCornerShape(8.dp))
            .padding(12.dp),
    )
}

@Composable
fun LoadingPane(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

@Composable
fun ErrorPane(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(message, color = Muted, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 64.dp))
        TextButton(onClick = onRetry) { Text("Retry", color = ValorantRed) }
    }
}

/** A labelled number, e.g. "K/D" over "1.24". */
@Composable
fun StatTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = Color.Unspecified,
    background: Color = InkRaised,
) {
    Column(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(background)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(label, fontFamily = Mono, fontSize = 10.sp, letterSpacing = 1.sp, color = Muted)
        Text(value, fontFamily = Display, fontSize = 22.sp, color = valueColor)
    }
}

@Composable
fun AgentIcon(content: GameContent?, agentId: String?, size: Dp, modifier: Modifier = Modifier) {
    val agent = content?.agent(agentId)
    AsyncImage(
        model = agent?.icon,
        contentDescription = agent?.name,
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(6.dp))
            .background(InkRaised),
    )
}

@Composable
fun RankIcon(content: GameContent?, tier: Int, size: Dp, modifier: Modifier = Modifier) {
    val rank = content?.rank(tier)
    AsyncImage(model = rank?.icon, contentDescription = rank?.name, modifier = modifier.size(size))
}

/** Rank name in its tier color, e.g. "DIAMOND 2". */
@Composable
fun RankName(content: GameContent?, tier: Int, fontSize: Int = 14) {
    val rank = content?.rank(tier)
    val color = remember(rank) { rgbaColor(rank?.color)?.copy(alpha = 1f) ?: Muted }
    Text(rank?.name ?: "UNRANKED", fontFamily = Display, fontSize = fontSize.sp, color = color, fontWeight = FontWeight.Normal)
}

/** A thin horizontal progress bar. */
@Composable
fun ProgressBar(fraction: Float, color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(Muted.copy(alpha = 0.25f)),
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .height(6.dp)
                .background(color),
        )
    }
}

/** "just now", "12m ago", "5h ago", "3d ago". */
fun relativeTime(millis: Long, now: Long = System.currentTimeMillis()): String {
    val minutes = (now - millis) / 60_000
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "${minutes}m ago"
        minutes < 60 * 24 -> "${minutes / 60}h ago"
        else -> "${minutes / (60 * 24)}d ago"
    }
}

/** "34m" or "1h 02m". */
fun shortDuration(millis: Long): String {
    val totalMinutes = (millis / 60_000).coerceAtLeast(0)
    return if (totalMinutes < 60) "${totalMinutes}m" else "%dh %02dm".format(totalMinutes / 60, totalMinutes % 60)
}

/** "2d 4h", "5h 12m", "8m": coarse countdowns for multi-day timers. */
fun countdown(millis: Long): String {
    val minutes = (millis / 60_000).coerceAtLeast(0)
    val days = minutes / (60 * 24)
    val hours = (minutes / 60) % 24
    return when {
        days > 0 -> "${days}d ${hours}h"
        hours > 0 -> "${hours}h ${minutes % 60}m"
        else -> "${minutes}m"
    }
}

/** "1 match", "3 matches". */
fun plural(count: Int, one: String, many: String) = "$count ${if (count == 1) one else many}"

fun signed(value: Int): String = if (value > 0) "+$value" else value.toString()
