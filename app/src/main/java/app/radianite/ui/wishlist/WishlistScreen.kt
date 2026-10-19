package app.radianite.ui.wishlist

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.radianite.R
import app.radianite.data.store.StoreSkin
import app.radianite.ui.common.BackButton
import app.radianite.ui.common.WinGreen
import app.radianite.ui.theme.InkRaised
import app.radianite.ui.theme.Mono
import app.radianite.ui.theme.Muted
import app.radianite.ui.theme.ValorantRed
import app.radianite.ui.theme.rgbaColor
import coil3.compose.AsyncImage

/** Wishlisted skins; [inStoreNow] holds the skin UUIDs on offer in the shown account's store today. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WishlistScreen(
    skins: List<StoreSkin>,
    inStoreNow: Set<String>,
    onOpenSkin: (StoreSkin) -> Unit,
    onRemove: (StoreSkin) -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Wishlist", fontWeight = FontWeight.Bold) },
                navigationIcon = { BackButton(onBack) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        if (skins.isEmpty()) {
            Column(
                Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(painterResource(R.drawable.ic_star_outline), contentDescription = null, tint = Muted, modifier = Modifier.size(40.dp))
                Spacer(Modifier.height(12.dp))
                Text(
                    "Star a skin on its page and Radianite tells you when it turns up in your store or Night Market.",
                    color = Muted,
                    textAlign = TextAlign.Center,
                )
            }
            return@Scaffold
        }
        LazyColumn(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            item(key = "hint") {
                Text(
                    "Radianite checks every signed-in account's store after it resets and notifies you when one of these is in it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Muted,
                )
            }
            items(skins, key = { it.skinUuid ?: it.levelUuid }) { skin ->
                WishlistRow(skin, inStore = skin.skinUuid in inStoreNow, onClick = { onOpenSkin(skin) }, onRemove = { onRemove(skin) })
            }
        }
    }
}

@Composable
private fun WishlistRow(skin: StoreSkin, inStore: Boolean, onClick: () -> Unit, onRemove: () -> Unit) {
    val tierColor = remember(skin.tierColor) { rgbaColor(skin.tierColor)?.copy(alpha = 1f) ?: Muted }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Brush.horizontalGradient(listOf(tierColor.copy(alpha = 0.3f), InkRaised, InkRaised)))
            .clickable(onClick = onClick)
            .padding(start = 12.dp, top = 8.dp, bottom = 8.dp),
    ) {
        Box(Modifier.size(width = 96.dp, height = 48.dp), contentAlignment = Alignment.Center) {
            AsyncImage(model = skin.iconUrl, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(skin.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (inStore) {
                Text("IN YOUR STORE TODAY", fontFamily = Mono, fontSize = 10.sp, letterSpacing = 1.sp, color = WinGreen)
            } else {
                skin.tierName?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = Muted) }
            }
        }
        IconButton(onClick = onRemove) {
            Icon(painterResource(R.drawable.ic_star), contentDescription = "Remove ${skin.name} from wishlist", tint = ValorantRed)
        }
    }
}
