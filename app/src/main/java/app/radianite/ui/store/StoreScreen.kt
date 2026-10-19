package app.radianite.ui.store

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import app.radianite.R
import app.radianite.data.content.CurrencyArt
import app.radianite.data.store.KINGDOM_CREDITS_UUID
import app.radianite.data.store.RADIANITE_POINTS_UUID
import app.radianite.data.store.StoreBundle
import app.radianite.data.store.StoreSkin
import app.radianite.data.store.VALORANT_POINTS_UUID
import app.radianite.data.store.Wallet
import app.radianite.ui.VersionLabel
import app.radianite.ui.common.AccountsButton
import app.radianite.ui.common.MessageBanner
import app.radianite.ui.common.SectionHeader
import app.radianite.ui.common.Skeleton
import app.radianite.ui.common.countdown
import app.radianite.ui.common.plural
import app.radianite.ui.common.rememberNow
import app.radianite.ui.theme.Display
import app.radianite.ui.theme.Ink
import app.radianite.ui.theme.InkRaised
import app.radianite.ui.theme.Mono
import app.radianite.ui.theme.Muted
import app.radianite.ui.theme.ValorantRed
import app.radianite.ui.theme.rgbaColor
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay

/** [wishlisted] holds the skin UUIDs on the wishlist, which get a star on their cards. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StoreScreen(
    riotId: String,
    viewModel: StoreViewModel,
    wishlisted: Set<String>,
    onOpenAccounts: () -> Unit,
    onOpenSkin: (StoreSkin) -> Unit,
    onOpenWishlist: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val onRefresh = viewModel::refresh
    val vp = state.wallet?.valorantPoints
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Daily Store", fontWeight = FontWeight.Bold)
                        Text(riotId, style = MaterialTheme.typography.labelMedium, color = Muted)
                    }
                },
                actions = { AccountsButton(onOpenAccounts) },
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
            // Two columns on a phone, so the whole store fits on screen; more on wide screens.
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 156.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                // One always-present top item: rows that appear above the first visible item would
                // otherwise be inserted off-screen, since the list keeps its scroll anchored to that item.
                fullWidth("header") {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        state.wallet?.let { WalletRow(it) }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1f)) {
                                state.store?.let { ResetCountdown(it.expiresAtMillis, onExpired = onRefresh) }
                            }
                            WishlistChip(wishlisted.size, onOpenWishlist)
                        }
                    }
                }
                state.message?.let { message -> fullWidth("message") { MessageBanner(message) } }
                val store = state.store
                if (store != null) {
                    items(store.skins, key = { it.levelUuid }) {
                        SkinCard(it, wishlisted = it.skinUuid in wishlisted, valorantPoints = vp, onClick = { onOpenSkin(it) })
                    }
                    store.nightMarketExpiresAtMillis?.takeIf { store.nightMarket.isNotEmpty() }?.let { endsAt ->
                        fullWidth("night-market-header") { TimedHeader("NIGHT MARKET", endsAt) }
                        items(store.nightMarket, key = { "night-" + it.skin.levelUuid }) { offer ->
                            SkinCard(
                                offer.skin,
                                wishlisted = offer.skin.skinUuid in wishlisted,
                                valorantPoints = vp,
                                onClick = { onOpenSkin(offer.skin) },
                                basePriceVp = offer.basePriceVp,
                                discountPercent = offer.discountPercent,
                            )
                        }
                    }
                    if (store.bundles.isNotEmpty()) {
                        fullWidth("bundles-header") { SectionHeader("FEATURED BUNDLES") }
                        store.bundles.forEach { bundle -> fullWidth("bundle-" + bundle.name + bundle.expiresAtMillis) { BundleCard(bundle) } }
                    }
                } else if (state.refreshing) {
                    items(DAILY_OFFERS, key = { "skeleton-$it" }) { Skeleton(Modifier.fillMaxWidth().height(skinCardHeight())) }
                } else if (state.message == null) {
                    fullWidth("empty") { Text("Pull down to load your store.", color = Muted, modifier = Modifier.padding(vertical = 32.dp)) }
                }
                fullWidth("version") {
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

private fun LazyGridScope.fullWidth(key: String, content: @Composable () -> Unit) =
    item(key = key, span = { GridItemSpan(maxLineSpan) }) { content() }

/**
 * Ticks once a second in its own scope, so the cards below never recompose for the timer, and only while
 * the app is in front: a rollover that happened in the background refreshes the store on return.
 */
@Composable
private fun ResetCountdown(expiresAtMillis: Long, onExpired: () -> Unit) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val remaining by produceState(expiresAtMillis - System.currentTimeMillis(), expiresAtMillis, lifecycle) {
        val countedDown = value > 0
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                value = expiresAtMillis - System.currentTimeMillis()
                if (value <= 0) {
                    // Only auto-refresh on a real rollover, never for a store that arrived already expired.
                    if (countedDown) onExpired()
                    break
                }
                delay(1_000 - System.currentTimeMillis() % 1_000)
            }
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
private fun WishlistChip(count: Int, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(InkRaised)
            .clickable(onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = "Wishlist, ${plural(count, "skin", "skins")}" }
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Icon(painterResource(if (count > 0) R.drawable.ic_star else R.drawable.ic_star_outline), contentDescription = null, tint = ValorantRed, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(if (count > 0) "WISHLIST · $count" else "WISHLIST", fontFamily = Mono, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun WalletRow(wallet: Wallet) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Balance(VALORANT_POINTS_UUID, wallet.valorantPoints, "VALORANT Points")
        Balance(RADIANITE_POINTS_UUID, wallet.radianite, "Radianite Points")
        Balance(KINGDOM_CREDITS_UUID, wallet.kingdomCredits, "Kingdom Credits")
    }
}

@Composable
private fun Balance(currencyUuid: String, amount: Int, currencyName: String) {
    val formatted = "%,d".format(amount)
    // Read as "1,250 VALORANT Points" rather than an unlabelled icon and a number.
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = "$formatted $currencyName" }) {
        AsyncImage(model = CurrencyArt.icon(currencyUuid), contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(formatted, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 13.sp)
    }
}

/** Section title with a coarse countdown, for offers that run for days. */
@Composable
private fun TimedHeader(title: String, endsAtMillis: Long) {
    val now by rememberNow(60_000)
    Row(verticalAlignment = Alignment.Bottom) {
        SectionHeader(title, Modifier.weight(1f))
        val remaining = endsAtMillis - now
        if (remaining > 0) {
            Text("ENDS IN ${countdown(remaining).uppercase()}", fontFamily = Mono, fontSize = 11.sp, color = ValorantRed, modifier = Modifier.padding(bottom = 8.dp))
        }
    }
}

@Composable
private fun BundleCard(bundle: StoreBundle) {
    val now by rememberNow(60_000)
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
                val remaining = bundle.expiresAtMillis - now
                if (remaining > 0) Text(countdown(remaining), fontFamily = Mono, fontSize = 11.sp, color = Muted)
            }
        }
    }
}

/**
 * One offer as a grid tile. The price dims, and a chip says how much more VP is needed, when the wallet
 * can't cover it; [valorantPoints] is null until the wallet loads.
 */
@Composable
private fun SkinCard(
    skin: StoreSkin,
    wishlisted: Boolean,
    valorantPoints: Int?,
    onClick: () -> Unit,
    basePriceVp: Int? = null,
    discountPercent: Int = 0,
) {
    val tierColor = remember(skin.tierColor) { rgbaColor(skin.tierColor)?.copy(alpha = 1f) ?: Muted }
    val background = remember(tierColor) {
        Brush.linearGradient(listOf(tierColor.copy(alpha = 0.35f), InkRaised, InkRaised))
    }
    val shortBy = skin.priceVp?.let { price -> valorantPoints?.let { price - it } }?.takeIf { it > 0 }
    Card(
        onClick = onClick,
        enabled = skin.skinUuid != null,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent, disabledContainerColor = Color.Transparent),
        modifier = Modifier
            .fillMaxWidth()
            .height(skinCardHeight()),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .background(background)
                .padding(12.dp),
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(SKIN_ART_HEIGHT),
                contentAlignment = Alignment.Center,
            ) {
                AsyncImage(
                    model = skin.iconUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().padding(4.dp),
                )
                if (wishlisted) {
                    Icon(
                        painterResource(R.drawable.ic_star),
                        contentDescription = "On your wishlist",
                        tint = Color.White,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .background(ValorantRed, CircleShape)
                            .padding(4.dp)
                            .size(14.dp),
                    )
                }
                if (discountPercent > 0) {
                    Text(
                        "−$discountPercent%",
                        fontFamily = Mono,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        color = Color.White,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .background(ValorantRed, RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 1.dp),
                    )
                }
                shortBy?.let {
                    Text(
                        "NEED %,d VP".format(it),
                        fontFamily = Mono,
                        fontSize = 9.sp,
                        color = Muted,
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .background(Ink.copy(alpha = 0.8f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 5.dp, vertical = 1.dp),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                skin.name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                minLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.weight(1f))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (skin.tierIconUrl != null) {
                    AsyncImage(model = skin.tierIconUrl, contentDescription = skin.tierName, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                }
                basePriceVp?.takeIf { discountPercent > 0 }?.let {
                    Text("%,d".format(it), color = Muted, textDecoration = TextDecoration.LineThrough, fontSize = 11.sp, maxLines = 1)
                }
                Spacer(Modifier.weight(1f))
                skin.priceVp?.let { price ->
                    Text(
                        "%,d VP".format(price),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (shortBy == null) tierColor else Muted,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

private const val DAILY_OFFERS = 4
private val SKIN_ART_HEIGHT = 96.dp

/** Every tile in a row the same height: the art and padding, plus two lines of name and a price line at the user's font size. */
@Composable
private fun skinCardHeight(): Dp = SKIN_ART_HEIGHT + 32.dp + with(LocalDensity.current) { 66.sp.toDp() }
