package app.radianite.ui.bundle

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.radianite.R
import app.radianite.data.store.BundleExtra
import app.radianite.data.store.BundleSkin
import app.radianite.data.store.StoreBundle
import app.radianite.data.store.StoreSkin
import app.radianite.ui.common.Amount
import app.radianite.ui.common.BackButton
import app.radianite.ui.common.SectionHeader
import app.radianite.ui.common.Skeleton
import app.radianite.ui.common.countdown
import app.radianite.ui.common.label
import app.radianite.ui.common.rememberNow
import app.radianite.ui.theme.Display
import app.radianite.ui.theme.Ink
import app.radianite.ui.theme.InkRaised
import app.radianite.ui.theme.Mono
import app.radianite.ui.theme.Muted
import app.radianite.ui.theme.ValorantRed
import app.radianite.ui.theme.rgbaColor
import coil3.compose.AsyncImage

/**
 * A featured bundle's contents: its skins, each with its rarity, prices and variants, opening the skin's own
 * page, then the buddies, sprays, cards and titles that come with them. [wishlisted] holds wishlisted skin UUIDs.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BundleDetailScreen(
    bundle: StoreBundle,
    viewModel: BundleDetailViewModel,
    wishlisted: Set<String>,
    onOpenSkin: (StoreSkin) -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val state by viewModel.state.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    // The hero already names the bundle; the bar takes the name only once the hero scrolls away.
    val heroGone by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 } }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    AnimatedVisibility(heroGone, enter = fadeIn(), exit = fadeOut()) {
                        Text(bundle.name, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                navigationIcon = { BackButton(onBack) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        val loaded = (state as? BundleDetailUiState.Loaded)?.contents
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            item(key = "hero") { Hero(bundle, heroUrl = loaded?.heroUrl ?: bundle.artUrl) }
            when (val s = state) {
                BundleDetailUiState.Loading -> items(3, key = { "skeleton-$it" }) { Skeleton(Modifier.fillMaxWidth().height(88.dp)) }
                is BundleDetailUiState.Error -> item(key = "error") {
                    Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(s.message, color = Muted)
                        TextButton(onClick = viewModel::load) { Text("Retry", color = ValorantRed) }
                    }
                }
                is BundleDetailUiState.Loaded -> {
                    val contents = s.contents
                    contents.description?.let { item(key = "description") { Text(it, color = Muted, style = MaterialTheme.typography.bodyMedium) } }
                    if (contents.skins.isEmpty() && contents.extras.isEmpty()) {
                        item(key = "empty") {
                            Text("Pull down on the store to load what's in this bundle.", color = Muted, modifier = Modifier.padding(vertical = 24.dp))
                        }
                    }
                    if (contents.skins.isNotEmpty()) {
                        item(key = "skins-header") { SectionHeader("SKINS") }
                        itemsIndexed(contents.skins, key = { index, _ -> "skin-$index" }) { _, entry ->
                            SkinRow(entry, wishlisted = entry.skin.skinUuid in wishlisted, onClick = { onOpenSkin(entry.skin) })
                        }
                    }
                    if (contents.extras.isNotEmpty()) {
                        item(key = "extras-header") { SectionHeader("ALSO INCLUDED") }
                        itemsIndexed(contents.extras, key = { index, _ -> "extra-$index" }) { _, extra -> ExtraRow(extra) }
                    }
                }
            }
        }
    }
}

@Composable
private fun Hero(bundle: StoreBundle, heroUrl: String?) {
    val now by rememberNow(60_000)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(190.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(InkRaised),
        ) {
            AsyncImage(model = heroUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Ink.copy(alpha = 0.85f)))))
            Text(
                bundle.name.uppercase(),
                fontFamily = Display,
                fontSize = 28.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(14.dp),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            bundle.priceVp?.let { Amount(it, fontWeight = FontWeight.Bold, fontSize = 18.sp) }
            val base = bundle.basePriceVp
            val price = bundle.priceVp
            if (base != null && price != null && base > price) {
                Spacer(Modifier.width(8.dp))
                Text("%,d".format(base), color = Muted, textDecoration = TextDecoration.LineThrough, fontSize = 13.sp)
                Spacer(Modifier.width(8.dp))
                Text(
                    "SAVE ${(base - price) * 100 / base}%",
                    fontFamily = Mono,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    color = Color.White,
                    modifier = Modifier
                        .background(ValorantRed, RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 1.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            val remaining = bundle.expiresAtMillis - now
            if (remaining > 0) Text("ENDS IN ${countdown(remaining).uppercase()}", fontFamily = Mono, fontSize = 11.sp, color = ValorantRed)
        }
    }
}

/**
 * A skin with its rarity, prices and variant swatches; opens the skin's page, which plays its upgrades and
 * variants, so the row doesn't count them.
 */
@Composable
private fun SkinRow(entry: BundleSkin, wishlisted: Boolean, onClick: () -> Unit) {
    val skin = entry.skin
    val tierColor = remember(skin.tierColor) { rgbaColor(skin.tierColor)?.copy(alpha = 1f) ?: Muted }
    val openable = skin.skinUuid != null
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Brush.horizontalGradient(listOf(tierColor.copy(alpha = 0.3f), InkRaised, InkRaised)))
            .clickable(enabled = openable, role = Role.Button, onClick = onClick)
            .padding(12.dp),
    ) {
        AsyncImage(
            model = skin.iconUrl,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .width(104.dp)
                .height(56.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    skin.name,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (wishlisted) {
                    Spacer(Modifier.width(6.dp))
                    Icon(painterResource(R.drawable.ic_star), contentDescription = "On your wishlist", tint = ValorantRed, modifier = Modifier.size(14.dp))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (skin.tierIconUrl != null) {
                    AsyncImage(model = skin.tierIconUrl, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                }
                skin.tierName?.let { Text(it, color = tierColor, fontSize = 12.sp) }
                Spacer(Modifier.weight(1f))
                Prices(skin.priceVp, entry.basePriceVp, color = tierColor)
            }
            val swatches = entry.detail?.chromas.orEmpty().mapNotNull { it.swatchUrl }.takeIf { it.size > 1 }
            if (swatches != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    swatches.take(MAX_SWATCHES).forEach { swatch ->
                        AsyncImage(
                            model = swatch,
                            contentDescription = null,
                            modifier = Modifier
                                .padding(end = 4.dp)
                                .size(16.dp)
                                .clip(CircleShape)
                                .background(Ink),
                        )
                    }
                }
            }
        }
        if (openable) {
            Spacer(Modifier.width(4.dp))
            Icon(painterResource(R.drawable.ic_arrow_forward), contentDescription = null, tint = Muted, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun ExtraRow(extra: BundleExtra) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(InkRaised)
            .padding(12.dp),
    ) {
        Box(
            Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Ink),
            contentAlignment = Alignment.Center,
        ) {
            if (extra.cosmetic.imageUrl != null) {
                AsyncImage(model = extra.cosmetic.imageUrl, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(4.dp))
            } else {
                Text("T", fontFamily = Display, fontSize = 20.sp, color = Muted)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(extra.kind.label().uppercase(), fontFamily = Mono, fontSize = 10.sp, color = Muted, letterSpacing = 1.sp)
            Text(
                extra.cosmetic.name.ifEmpty { extra.kind.label() } + if (extra.amount > 1) " ×${extra.amount}" else "",
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Prices(extra.priceVp, extra.basePriceVp, color = Color.Unspecified)
    }
}

/** The bundle price, with the price on its own struck through when the bundle is cheaper; free items say so. */
@Composable
private fun Prices(priceVp: Int?, basePriceVp: Int?, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (basePriceVp != null && priceVp != null && basePriceVp > priceVp) {
            Text("%,d".format(basePriceVp), color = Muted, textDecoration = TextDecoration.LineThrough, fontSize = 11.sp)
            Spacer(Modifier.width(6.dp))
        }
        when (priceVp) {
            null -> Unit
            0 -> Text("FREE", fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = ValorantRed)
            else -> Amount(priceVp, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = color)
        }
    }
}

private const val MAX_SWATCHES = 6
