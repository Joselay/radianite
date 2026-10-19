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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
import app.radianite.data.store.StoreSkin
import app.radianite.ui.common.BackButton
import app.radianite.ui.theme.InkRaised
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
                        val subtitle = listOfNotNull(skin.tierName, skin.priceVp?.let { "%,d VP".format(it) }).joinToString(" · ")
                        if (subtitle.isNotEmpty()) Text(subtitle, style = MaterialTheme.typography.labelMedium, color = accent)
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
        PreviewStage(state.selected, newPlayer)
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp), modifier = Modifier.weight(1f)) {
            item(key = "selected") {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text(state.selected.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    state.selected.subtitle?.let { Text(it, color = accent) }
                    if (state.selected.videoUrl == null) Text("No video for this one", color = Muted, style = MaterialTheme.typography.bodySmall)
                }
            }

            if (state.detail.levels.size > 1) {
                item(key = "levels-header") { SectionHeader("UPGRADES") }
                items(state.detail.levels, key = { "level-" + it.id }) { level ->
                    LevelRow(level, selected = level.id == state.selected.id, accent = accent, onClick = { onSelect(level) })
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
                            ChromaTile(chroma, selected = chroma.id == state.selected.id, accent = accent, onClick = { onSelect(chroma) })
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
private fun PreviewStage(variant: SkinVariant, newPlayer: (android.content.Context) -> ExoPlayer) {
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
            .background(Color.Black)
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
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = Muted,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
    )
}

@Composable
private fun LevelRow(level: SkinVariant, selected: Boolean, accent: Color, onClick: () -> Unit) {
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
        if (level.videoUrl != null) Icon(painterResource(R.drawable.ic_play), contentDescription = "Has video", tint = if (selected) accent else Muted, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun ChromaTile(chroma: SkinVariant, selected: Boolean, accent: Color, onClick: () -> Unit) {
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
        if (chroma.videoUrl != null) Icon(painterResource(R.drawable.ic_play), contentDescription = "Has video", tint = Muted, modifier = Modifier.size(14.dp))
    }
}
