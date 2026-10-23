package app.radianite.ui.skin

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.compose.PlayerSurface
import app.radianite.R
import app.radianite.data.content.SkinVariant
import app.radianite.data.player.SkinUpgrades
import app.radianite.data.store.StoreSkin
import app.radianite.ui.common.Amount
import app.radianite.ui.common.BackButton
import app.radianite.ui.common.Currency
import app.radianite.ui.theme.InkRaised
import app.radianite.ui.theme.Mono
import app.radianite.ui.theme.Muted
import app.radianite.ui.theme.ValorantRed
import app.radianite.ui.theme.rgbaColor
import coil3.compose.AsyncImage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkinDetailScreen(
    skin: StoreSkin,
    viewModel: SkinDetailViewModel,
    newPlayer: (android.content.Context) -> ExoPlayer,
    wishlisted: Boolean,
    onToggleWishlist: () -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // Store alerts are notifications; ask for them when the first skin goes on the wishlist, not before.
    val askForNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val accent = remember(skin.tierColor) { rgbaColor(skin.tierColor)?.copy(alpha = 1f) ?: ValorantRed }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(skin.name, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            skin.tierName?.let { Text(if (skin.priceVp != null) "$it · " else it, style = MaterialTheme.typography.labelMedium, color = accent) }
                            skin.priceVp?.let { Amount(it, style = MaterialTheme.typography.labelMedium, color = accent) }
                        }
                    }
                },
                navigationIcon = { BackButton(onBack) },
                actions = {
                    IconButton(onClick = {
                        if (!wishlisted && Build.VERSION.SDK_INT >= 33 &&
                            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                        ) {
                            askForNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                        onToggleWishlist()
                    }) {
                        Icon(
                            painterResource(if (wishlisted) R.drawable.ic_star else R.drawable.ic_star_outline),
                            contentDescription = if (wishlisted) "Remove from wishlist" else "Add to wishlist",
                            tint = if (wishlisted) ValorantRed else Muted,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Box(
            Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            when (val s = state) {
                SkinDetailUiState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                is SkinDetailUiState.Error -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(s.message, color = Muted)
                    TextButton(onClick = viewModel::load) { Text("Retry", color = ValorantRed) }
                }
                is SkinDetailUiState.Loaded -> LoadedDetail(s, accent, newPlayer, onSelect = viewModel::select)
            }
        }
    }
}

@Composable
private fun LoadedDetail(
    state: SkinDetailUiState.Loaded,
    accent: Color,
    newPlayer: (android.content.Context) -> ExoPlayer,
    onSelect: (SkinVariant) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        // The stage stays pinned while the variant list scrolls underneath.
        PreviewStage(state.selected, accent, newPlayer)
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp), modifier = Modifier.weight(1f)) {
            item(key = "selected") {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text(state.selected.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    state.selected.subtitle?.let { Text(it, color = accent) }
                }
            }

            val upgrades = state.upgrades
            upgrades?.let { item(key = "radianite") { RadianiteSummary(it, accent) } }

            if (state.detail.levels.size > 1) {
                item(key = "levels-header") { SectionHeader("UPGRADES") }
                items(state.detail.levels, key = { "level-" + it.id }) { level ->
                    LevelRow(level, selected = level.id == state.selected.id, accent = accent, unlock = upgrades?.unlockOf(level.id), onClick = { onSelect(level) })
                }
            }

            if (state.detail.chromas.size > 1) {
                item(key = "chromas-header") { SectionHeader("VARIANTS") }
                item(key = "chromas") {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(state.detail.chromas, key = { it.id }) { chroma ->
                            ChromaTile(chroma, selected = chroma.id == state.selected.id, accent = accent, unlock = upgrades?.unlockOf(chroma.id), onClick = { onSelect(chroma) })
                        }
                    }
                }
            }
        }
    }
}

/** One ExoPlayer for the whole screen; switching variants just swaps the media item. */
// PlayerSurface is still marked unstable in media3-ui-compose.
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun PreviewStage(variant: SkinVariant, accent: Color, newPlayer: (android.content.Context) -> ExoPlayer) {
    val context = LocalContext.current
    val player = remember { newPlayer(context).apply { repeatMode = Player.REPEAT_MODE_ONE } }
    var buffering by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf(true) }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                buffering = playbackState == Player.STATE_BUFFERING
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = isPlaying
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    LaunchedEffect(variant.videoUrl) {
        val url = variant.videoUrl
        if (url == null) {
            player.stop()
            player.clearMediaItems()
        } else {
            player.setMediaItem(MediaItem.fromUri(url))
            player.prepare()
            player.playWhenReady = true
        }
    }

    // Pause when the app goes to the background; resume when it comes back.
    LifecycleStartEffect(player) {
        if (player.mediaItemCount > 0) player.play()
        onStopOrDispose { player.pause() }
    }

    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            // Video plays on black; a still image (Riot has no video for many variants) sits on the
            // rarity gradient of the store cards, so it doesn't look like a video that failed to load.
            .background(if (variant.videoUrl != null) SolidColor(Color.Black) else stillBackground(accent))
            .clickable(enabled = variant.videoUrl != null) { if (player.isPlaying) player.pause() else player.play() },
        contentAlignment = Alignment.Center,
    ) {
        if (variant.videoUrl != null) {
            PlayerSurface(player = player, modifier = Modifier.fillMaxSize())
            if (buffering) CircularProgressIndicator(color = Color.White)
            else if (!playing) Icon(painterResource(R.drawable.ic_play), contentDescription = "Play", tint = Color.White, modifier = Modifier.size(56.dp))
        } else {
            AsyncImage(
                model = variant.imageUrl,
                contentDescription = variant.title,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
            )
        }
    }
}

@Composable
private fun stillBackground(accent: Color): Brush =
    remember(accent) { Brush.linearGradient(listOf(accent.copy(alpha = 0.35f), InkRaised, InkRaised)) }

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = Muted,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
    )
}

/** Where a level or chroma stands: owned, or locked behind [rp] Radianite Points. Null when it comes with the skin. */
private sealed interface Unlock {
    data object Owned : Unlock
    data class Locked(val rp: Int) : Unlock
}

/** Prices show as locked for a skin the account doesn't own, since its upgrades can't be bought yet either. */
private fun SkinUpgrades.unlockOf(variantUuid: String): Unlock? {
    val rp = rp(variantUuid) ?: return null
    return if (ownsSkin && isUnlocked(variantUuid)) Unlock.Owned else Unlock.Locked(rp)
}

/** What the skin's upgrades cost all told, or what's left of it for an owned skin. */
@Composable
private fun RadianiteSummary(upgrades: SkinUpgrades, accent: Color) {
    val remaining = upgrades.remainingRp
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(InkRaised)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text(
            when {
                !upgrades.ownsSkin -> "TO FULLY UPGRADE"
                remaining > 0 -> "LEFT TO UNLOCK"
                else -> "FULLY UPGRADED"
            },
            fontFamily = Mono,
            fontSize = 11.sp,
            letterSpacing = 1.sp,
            color = Muted,
            modifier = Modifier.weight(1f),
        )
        if (upgrades.ownsSkin && remaining == 0) {
            Icon(painterResource(R.drawable.ic_check), contentDescription = "Everything unlocked", tint = accent, modifier = Modifier.size(20.dp))
        } else {
            Amount(remaining, currency = Currency.RADIANITE_POINTS, fontWeight = FontWeight.Bold, color = accent)
        }
    }
}

/** A tick for an owned upgrade, its price for a locked one. */
@Composable
private fun UnlockBadge(unlock: Unlock, accent: Color, fontSize: TextUnit) {
    when (unlock) {
        Unlock.Owned -> Icon(painterResource(R.drawable.ic_check), contentDescription = "Unlocked", tint = accent, modifier = Modifier.size(with(LocalDensity.current) { (fontSize * 1.4f).toDp() }))
        is Unlock.Locked -> Amount(unlock.rp, currency = Currency.RADIANITE_POINTS, fontFamily = Mono, fontSize = fontSize, color = Muted)
    }
}

@Composable
private fun LevelRow(level: SkinVariant, selected: Boolean, accent: Color, unlock: Unlock?, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) accent.copy(alpha = 0.18f) else InkRaised)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text(level.title, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(72.dp))
        Text(level.subtitle.orEmpty(), color = if (selected) accent else Muted, modifier = Modifier.weight(1f))
        unlock?.let {
            UnlockBadge(it, accent, fontSize = 12.sp)
            Spacer(Modifier.width(10.dp))
        }
        if (level.videoUrl != null) Icon(painterResource(R.drawable.ic_play), contentDescription = "Has video", tint = if (selected) accent else Muted, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun ChromaTile(chroma: SkinVariant, selected: Boolean, accent: Color, unlock: Unlock?, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(84.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
    ) {
        AsyncImage(
            model = chroma.swatchUrl ?: chroma.imageUrl,
            contentDescription = chroma.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .border(BorderStroke(if (selected) 3.dp else 1.dp, if (selected) accent else Muted), CircleShape),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            chroma.title,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) accent else MaterialTheme.colorScheme.onBackground,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            unlock?.let { UnlockBadge(it, accent, fontSize = 10.sp) }
            if (chroma.videoUrl != null) Icon(painterResource(R.drawable.ic_play), contentDescription = "Has video", tint = Muted, modifier = Modifier.size(14.dp))
        }
    }
}
