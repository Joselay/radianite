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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.radianite.data.content.CurrencyArt
import app.radianite.data.store.KINGDOM_CREDITS_UUID
import app.radianite.data.store.RADIANITE_POINTS_UUID
import app.radianite.data.store.StoreBundle
import app.radianite.data.store.StoreSkin
import app.radianite.data.store.VALORANT_POINTS_UUID
import app.radianite.data.store.Wallet
import app.radianite.ui.VersionLabel
import app.radianite.ui.common.MessageBanner
import app.radianite.ui.common.SectionHeader
import app.radianite.ui.common.countdown
import app.radianite.ui.theme.Display
import app.radianite.ui.theme.Ink
import app.radianite.ui.theme.InkRaised
import app.radianite.ui.theme.Mono
import app.radianite.ui.theme.Muted
import app.radianite.ui.theme.ValorantRed
import app.radianite.ui.theme.rgbaColor
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StoreScreen(
    riotId: String,
    viewModel: StoreViewModel,
    onSignOut: () -> Unit,
    onOpenSkin: (StoreSkin) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val onRefresh = viewModel::refresh
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Daily Store", fontWeight = FontWeight.Bold)
                        Text(riotId, style = MaterialTheme.typography.labelMedium, color = Muted)
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
                // One always-present top item: rows that appear above the first visible item would
                // otherwise be inserted off-screen, since the list keeps its scroll anchored to that item.
                item(key = "header") {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        state.wallet?.let { WalletRow(it) }
                        state.store?.let { ResetCountdown(it.expiresAtMillis, onExpired = onRefresh) }
                    }
                }
                state.message?.let { message ->
                    item(key = "message") { MessageBanner(message) }
                }
                state.store?.let { store ->
                    items(store.skins, key = { it.levelUuid }) { SkinCard(it, onClick = { onOpenSkin(it) }) }
                    store.nightMarketExpiresAtMillis?.takeIf { store.nightMarket.isNotEmpty() }?.let { endsAt ->
                        item(key = "night-market-header") { TimedHeader("NIGHT MARKET", endsAt) }
                        items(store.nightMarket, key = { "night-" + it.skin.levelUuid }) { offer ->
                            SkinCard(offer.skin, onClick = { onOpenSkin(offer.skin) }, basePriceVp = offer.basePriceVp, discountPercent = offer.discountPercent)
                        }
                    }
                    if (store.bundles.isNotEmpty()) {
                        item(key = "bundles-header") { SectionHeader("FEATURED BUNDLES") }
                        items(store.bundles, key = { "bundle-" + it.name + it.expiresAtMillis }) { BundleCard(it) }
                    }
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
private fun WalletRow(wallet: Wallet) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Balance(VALORANT_POINTS_UUID, wallet.valorantPoints)
        Balance(RADIANITE_POINTS_UUID, wallet.radianite)
        Balance(KINGDOM_CREDITS_UUID, wallet.kingdomCredits)
    }
}

@Composable
private fun Balance(currencyUuid: String, amount: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        AsyncImage(model = CurrencyArt.icon(currencyUuid), contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text("%,d".format(amount), fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 13.sp)
    }
}

/** Section title with a coarse countdown, for offers that run for days. */
@Composable
private fun TimedHeader(title: String, endsAtMillis: Long) {
    Row(verticalAlignment = Alignment.Bottom) {
        SectionHeader(title, Modifier.weight(1f))
        val remaining = endsAtMillis - System.currentTimeMillis()
        if (remaining > 0) {
            Text("ENDS IN ${countdown(remaining).uppercase()}", fontFamily = Mono, fontSize = 11.sp, color = ValorantRed, modifier = Modifier.padding(bottom = 8.dp))
        }
    }
}

@Composable
private fun BundleCard(bundle: StoreBundle) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(180.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(InkRaised),
    ) {
        AsyncImage(model = bundle.artUrl, contentDescription = bundle.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Ink.copy(alpha = 0.9f)))))
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .padding(14.dp),
        ) {
            Text(bundle.name.uppercase(), fontFamily = Display, fontSize = 24.sp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                bundle.priceVp?.let { Text("%,d VP".format(it), fontWeight = FontWeight.Bold) }
                if (bundle.basePriceVp != null && bundle.priceVp != null && bundle.basePriceVp > bundle.priceVp) {
                    Spacer(Modifier.width(8.dp))
                    Text("%,d".format(bundle.basePriceVp), color = Muted, textDecoration = TextDecoration.LineThrough, fontSize = 12.sp)
                }
                Spacer(Modifier.weight(1f))
                val remaining = bundle.expiresAtMillis - System.currentTimeMillis()
                if (remaining > 0) Text(countdown(remaining), fontFamily = Mono, fontSize = 11.sp, color = Muted)
            }
        }
    }
}

@Composable
private fun SkinCard(skin: StoreSkin, onClick: () -> Unit, basePriceVp: Int? = null, discountPercent: Int = 0) {
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
            if (discountPercent > 0) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                    Text(
                        "−$discountPercent%",
                        fontFamily = Mono,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        color = Color.White,
                        modifier = Modifier
                            .background(ValorantRed, RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 1.dp),
                    )
                    Spacer(Modifier.weight(1f))
                    basePriceVp?.let { Text("%,d VP".format(it), color = Muted, textDecoration = TextDecoration.LineThrough, fontSize = 12.sp) }
                }
            }
            skin.tierName?.let {
                Text(it, style = MaterialTheme.typography.labelMedium, color = Muted)
            }
        }
    }
}
