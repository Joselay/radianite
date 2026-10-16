package app.radianite.ui.store

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.radianite.data.store.StoreSkin
import app.radianite.ui.VersionLabel
import app.radianite.ui.theme.InkRaised
import app.radianite.ui.theme.Muted
import app.radianite.ui.theme.ValorantRed
import app.radianite.ui.theme.rgbaColor
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StoreScreen(
    state: AppUiState.Store,
    onRefresh: () -> Unit,
    onSignOut: () -> Unit,
    onOpenSkin: (StoreSkin) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Daily Store", fontWeight = FontWeight.Bold)
                        Text(state.riotId, style = MaterialTheme.typography.labelMedium, color = Muted)
                    }
                },
                actions = { TextButton(onClick = onSignOut) { Text("Sign out", color = Muted) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = onRefresh,
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                state.store?.let { store ->
                    item(key = "countdown") { ResetCountdown(store.expiresAtMillis, onExpired = onRefresh) }
                }
                state.message?.let { message ->
                    item(key = "message") { MessageBanner(message) }
                }
                state.store?.let { store ->
                    items(store.skins, key = { it.levelUuid }) { SkinCard(it, onClick = { onOpenSkin(it) }) }
                }
                if (state.store == null && !state.refreshing && state.message == null) {
                    item(key = "empty") {
                        Text("Pull down to load your store.", color = Muted, modifier = Modifier.padding(vertical = 32.dp))
                    }
                }
                item(key = "version") {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) { VersionLabel() }
                }
            }
        }
    }
}

/** Ticks once a second in its own scope, so the cards below never recompose for the timer. */
@Composable
private fun ResetCountdown(expiresAtMillis: Long, onExpired: () -> Unit) {
    val remaining by produceState(expiresAtMillis - System.currentTimeMillis(), expiresAtMillis) {
        val countedDown = value > 0
        while (true) {
            value = expiresAtMillis - System.currentTimeMillis()
            if (value <= 0) {
                // Only auto-refresh on a real rollover, never for a store that arrived already expired.
                if (countedDown) onExpired()
                break
            }
            delay(1_000)
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("RESETS IN ", style = MaterialTheme.typography.labelLarge, color = Muted)
        Text(
            formatDuration(remaining),
            style = MaterialTheme.typography.labelLarge,
            color = ValorantRed,
            fontWeight = FontWeight.Bold,
        )
    }
}

internal fun formatDuration(millis: Long): String {
    val total = (millis / 1000).coerceAtLeast(0)
    return "%02d:%02d:%02d".format(total / 3600, (total % 3600) / 60, total % 60)
}

@Composable
private fun MessageBanner(message: String) {
    Text(
        message,
        color = Color.White,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier
            .fillMaxWidth()
            .background(ValorantRed.copy(alpha = 0.25f), RoundedCornerShape(8.dp))
            .padding(12.dp),
    )
}

@Composable
private fun SkinCard(skin: StoreSkin, onClick: () -> Unit) {
    val tierColor = remember(skin.tierColor) { rgbaColor(skin.tierColor)?.copy(alpha = 1f) ?: Muted }
    val background = remember(tierColor) {
        Brush.linearGradient(listOf(tierColor.copy(alpha = 0.35f), InkRaised, InkRaised))
    }
    Card(
        onClick = onClick,
        enabled = skin.skinUuid != null,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent, disabledContainerColor = Color.Transparent),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier
                .background(background)
                .padding(16.dp),
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(120.dp),
                contentAlignment = Alignment.Center,
            ) {
                AsyncImage(
                    model = skin.iconUrl,
                    contentDescription = skin.name,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (skin.tierIconUrl != null) {
                    AsyncImage(model = skin.tierIconUrl, contentDescription = skin.tierName, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    skin.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                skin.priceVp?.let { price ->
                    Text("%,d VP".format(price), style = MaterialTheme.typography.titleMedium, color = tierColor)
                }
            }
            skin.tierName?.let {
                Text(it, style = MaterialTheme.typography.labelMedium, color = Muted)
            }
        }
    }
}
