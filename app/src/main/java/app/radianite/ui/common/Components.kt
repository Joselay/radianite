package app.radianite.ui.common

import android.provider.Settings
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import app.radianite.R
import app.radianite.data.content.GameContent
import app.radianite.data.match.Outcome
import app.radianite.ui.theme.Display
import app.radianite.ui.theme.InkRaised
import app.radianite.ui.theme.Mono
import app.radianite.ui.theme.Muted
import app.radianite.ui.theme.ValorantRed
import app.radianite.ui.theme.rgbaColor
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay

/** The current patch's names and art, from the Content module; null until it first loads. */
val LocalGameContent = compositionLocalOf<GameContent?> { null }

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

/** The top bar's way back from an overlay. */
@Composable
fun BackButton(onBack: () -> Unit) {
    IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "Back") }
}

/** Opens the accounts sheet: switching, adding and signing out. */
@Composable
fun AccountsButton(onClick: () -> Unit) {
    IconButton(onClick = onClick) { Icon(painterResource(R.drawable.ic_account), contentDescription = "Accounts", tint = Muted) }
}

/** A filter chip in the app's colors: red when picked, quiet otherwise. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HudChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        colors = FilterChipDefaults.filterChipColors(
            containerColor = InkRaised,
            labelColor = Muted,
            selectedContainerColor = ValorantRed.copy(alpha = 0.18f),
            selectedLabelColor = ValorantRed,
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = true,
            selected = selected,
            borderColor = Color.Transparent,
            selectedBorderColor = ValorantRed.copy(alpha = 0.5f),
        ),
    )
}

/**
 * The current time, updated every [intervalMillis] for countdowns that should keep moving. Ticks only while
 * the app is in front, so it never wakes the phone in the background, and catches up on return.
 */
@Composable
fun rememberNow(intervalMillis: Long): State<Long> {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    return produceState(System.currentTimeMillis(), intervalMillis, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                value = System.currentTimeMillis()
                delay(intervalMillis - System.currentTimeMillis() % intervalMillis)
            }
        }
    }
}

/** False when the user has turned system animations off; autoplay and loops then hold still. */
@Composable
fun rememberMotionEnabled(): Boolean {
    val context = LocalContext.current
    return remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f
    }
}

/** A placeholder block that pulses gently while its content loads; holds still when animations are off. */
@Composable
fun Skeleton(modifier: Modifier = Modifier, shape: Shape = RoundedCornerShape(12.dp)) {
    val motion = rememberMotionEnabled()
    val alpha = if (motion) {
        val pulse by rememberInfiniteTransition(label = "skeleton").animateFloat(
            initialValue = 0.45f,
            targetValue = 0.9f,
            animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
            label = "skeleton-alpha",
        )
        pulse
    } else {
        0.7f
    }
    Box(modifier.graphicsLayer { this.alpha = alpha }.clip(shape).background(InkRaised))
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
fun AgentIcon(agentId: String?, size: Dp, modifier: Modifier = Modifier) {
    val agent = LocalGameContent.current?.agent(agentId)
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
fun RankIcon(tier: Int, size: Dp, modifier: Modifier = Modifier) {
    val rank = LocalGameContent.current?.rank(tier)
    AsyncImage(model = rank?.icon, contentDescription = rank?.name, modifier = modifier.size(size))
}

/** Rank name in its tier color, e.g. "DIAMOND 2". */
@Composable
fun RankName(tier: Int, fontSize: Int = 14) {
    val rank = LocalGameContent.current?.rank(tier)
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
