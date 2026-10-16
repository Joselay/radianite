package app.radianite.ui.collection

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.radianite.data.player.EquippedWeapon
import app.radianite.data.player.PlayerCollection
import app.radianite.data.player.PlayerRepository
import app.radianite.data.riot.SessionExpiredException
import app.radianite.data.store.StoreSkin
import app.radianite.ui.common.MessageBanner
import app.radianite.ui.common.SectionHeader
import app.radianite.ui.common.StatTile
import app.radianite.ui.store.toUserMessage
import app.radianite.ui.theme.InkRaised
import app.radianite.ui.theme.Mono
import app.radianite.ui.theme.Muted
import app.radianite.ui.theme.rgbaColor
import coil3.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CollectionUiState(
    val collection: PlayerCollection? = null,
    val refreshing: Boolean = true,
    val message: String? = null,
)

class CollectionViewModel(private val repository: PlayerRepository) : ViewModel() {
    private val _state = MutableStateFlow(CollectionUiState())
    val state: StateFlow<CollectionUiState> = _state.asStateFlow()

    private var job: Job? = null

    init {
        refresh()
    }

    fun refresh() {
        job?.cancel()
        job = viewModelScope.launch {
            _state.update { it.copy(refreshing = true, message = null) }
            try {
                val collection = repository.collection()
                _state.update { it.copy(collection = collection, refreshing = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: SessionExpiredException) {
                // The app returns to sign-in.
            } catch (e: Exception) {
                _state.update { it.copy(refreshing = false, message = e.toUserMessage()) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollectionScreen(viewModel: CollectionViewModel, onOpenSkin: (StoreSkin) -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    val collection = state.collection
    val skins = remember(collection, query) {
        collection?.skins.orEmpty().filter { query.isBlank() || it.name.contains(query.trim(), ignoreCase = true) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Collection", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = viewModel::refresh,
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                state.message?.let { item(key = "message", span = { GridItemSpan(maxLineSpan) }) { MessageBanner(it) } }
                if (collection != null) {
                    item(key = "counts", span = { GridItemSpan(maxLineSpan) }) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            StatTile("SKINS OWNED", "${collection.skins.size}", Modifier.weight(1f))
                            StatTile(
                                "AGENTS",
                                collection.ownedAgents?.let { "$it / ${collection.totalAgents}" } ?: "–",
                                Modifier.weight(1f),
                            )
                        }
                    }
                    if (collection.loadout.isNotEmpty()) {
                        item(key = "equipped-header", span = { GridItemSpan(maxLineSpan) }) { SectionHeader("EQUIPPED") }
                        items(collection.loadout, key = { "equipped-" + it.weaponId }) { weapon ->
                            EquippedTile(weapon, onClick = { weapon.skin?.let(onOpenSkin) })
                        }
                    }
                    item(key = "owned-header", span = { GridItemSpan(maxLineSpan) }) {
                        Column {
                            SectionHeader("OWNED SKINS")
                            OutlinedTextField(
                                value = query,
                                onValueChange = { query = it },
                                placeholder = { Text("Search skins") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                    items(skins, key = { "owned-" + it.levelUuid }) { skin -> SkinTile(skin, onClick = { onOpenSkin(skin) }) }
                    if (skins.isEmpty()) {
                        item(key = "no-skins", span = { GridItemSpan(maxLineSpan) }) {
                            Text(
                                if (query.isBlank()) "No skins yet." else "No owned skins match \"$query\".",
                                color = Muted,
                                modifier = Modifier.padding(vertical = 16.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EquippedTile(weapon: EquippedWeapon, onClick: () -> Unit) {
    val skin = weapon.skin
    Column(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(InkRaised)
            .clickable(enabled = skin?.skinUuid != null, onClick = onClick)
            .padding(10.dp),
    ) {
        Text(weapon.weaponName.uppercase(), fontFamily = Mono, fontSize = 10.sp, color = Muted)
        AsyncImage(
            model = skin?.iconUrl,
            contentDescription = skin?.name,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(vertical = 6.dp),
        )
        Text(skin?.name ?: "Standard", fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SkinTile(skin: StoreSkin, onClick: () -> Unit) {
    val tierColor = remember(skin.tierColor) { rgbaColor(skin.tierColor)?.copy(alpha = 1f) ?: Muted }
    Column(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Brush.linearGradient(listOf(tierColor.copy(alpha = 0.3f), InkRaised, InkRaised)))
            .clickable(onClick = onClick)
            .padding(10.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(2f),
            contentAlignment = Alignment.Center,
        ) {
            AsyncImage(model = skin.iconUrl, contentDescription = skin.name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (skin.tierIconUrl != null) {
                AsyncImage(model = skin.tierIconUrl, contentDescription = skin.tierName, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
            }
            Text(skin.name, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
