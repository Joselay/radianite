package app.radianite.ui.collection

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.radianite.R
import app.radianite.data.player.EquippedWeapon
import app.radianite.data.player.OwnedSkin
import app.radianite.data.player.PlayerCollection
import app.radianite.data.player.PlayerRepository
import app.radianite.data.store.StoreSkin
import app.radianite.ui.common.AccountsButton
import app.radianite.ui.common.Amount
import app.radianite.ui.common.Currency
import app.radianite.ui.common.MessageBanner
import app.radianite.ui.common.Skeleton
import app.radianite.ui.common.loadReporting
import app.radianite.ui.theme.Display
import app.radianite.ui.theme.InkRaised
import app.radianite.ui.theme.Mono
import app.radianite.ui.theme.Muted
import app.radianite.ui.theme.rgbaColor
import coil3.compose.AsyncImage
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

/** Shared by the Collection tab and the pages it opens, so a weapon's skins and the upgrade list need no reload. */
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
            loadReporting({ message -> _state.update { it.copy(refreshing = false, message = message) } }) {
                val collection = repository.collection()
                _state.update { it.copy(collection = collection, refreshing = false) }
            }
        }
    }
}

/**
 * The loadout as the game's Collection shows it: every weapon with its equipped skin, each opening the skins
 * owned for it. A line above sums up the skins and what maxing them would cost; search finds any owned skin.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollectionScreen(
    viewModel: CollectionViewModel,
    onOpenAccounts: () -> Unit,
    onOpenSkin: (StoreSkin) -> Unit,
    onOpenWeapon: (EquippedWeapon) -> Unit,
    onOpenUpgrades: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Null while not searching; blank while the field is open but empty.
    var query by rememberSaveable { mutableStateOf<String?>(null) }
    val collection = state.collection
    BackHandler(enabled = query != null) { query = null }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    val q = query
                    if (q == null) {
                        Text("Collection", fontWeight = FontWeight.Bold)
                    } else {
                        SearchField(q, onChange = { query = it })
                    }
                },
                navigationIcon = {
                    if (query != null) {
                        IconButton(onClick = { query = null }) { Icon(painterResource(R.drawable.ic_close), contentDescription = "Close search") }
                    }
                },
                actions = {
                    if (query == null) {
                        IconButton(onClick = { query = "" }) { Icon(painterResource(R.drawable.ic_search), contentDescription = "Search skins", tint = Muted) }
                        AccountsButton(onOpenAccounts)
                    }
                },
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
            val q = query
            if (q != null && collection != null) {
                val results = remember(collection, q) { collection.search(q) }
                OwnedSkinGrid(
                    skins = results,
                    onOpenSkin = onOpenSkin,
                    empty = if (q.isBlank()) "Type a skin's name." else "No owned skins match \"${q.trim()}\".",
                )
                return@PullToRefreshBox
            }
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                state.message?.let { fullWidth("message") { MessageBanner(it) } }
                if (collection == null && state.refreshing) {
                    fullWidth("summary-skeleton") { Skeleton(Modifier.fillMaxWidth().height(64.dp), RoundedCornerShape(10.dp)) }
                    items(PLACEHOLDER_TILES, key = { "skeleton-$it" }) { Skeleton(Modifier.fillMaxWidth().height(96.dp), RoundedCornerShape(10.dp)) }
                }
                if (collection != null) {
                    fullWidth("summary") { Summary(collection, onOpenUpgrades) }
                    items(collection.loadout, key = { "weapon-" + it.weaponId }) { weapon ->
                        WeaponTile(weapon, owned = collection.skinsFor(weapon.weaponId).size, onClick = { onOpenWeapon(weapon) })
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchField(query: String, onChange: (String) -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    TextField(
        value = query,
        onValueChange = onChange,
        placeholder = { Text("Search your skins") },
        singleLine = true,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focus),
    )
}

/** How many skins, and what maxing them all would cost against the wallet; opens the skins still to upgrade. */
@Composable
private fun Summary(collection: PlayerCollection, onOpenUpgrades: () -> Unit) {
    val toMax = collection.radianiteToMax
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(InkRaised)
            .clickable(enabled = (toMax ?: 0) > 0, role = Role.Button, onClickLabel = "See skins to upgrade", onClick = onOpenUpgrades)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Figure("SKINS", Modifier.weight(1f)) { Text("%,d".format(collection.skins.size), fontFamily = Display, fontSize = 22.sp) }
        if (toMax != null) {
            Figure(if (toMax > 0) "TO MAX" else "ALL MAXED", Modifier.weight(1f)) {
                Amount(toMax, currency = Currency.RADIANITE_POINTS, fontFamily = Display, fontSize = 22.sp)
            }
            collection.radianite?.let { rp ->
                Figure("YOU HAVE", Modifier.weight(1f)) { Amount(rp, currency = Currency.RADIANITE_POINTS, fontFamily = Display, fontSize = 22.sp, color = Muted) }
            }
            if (toMax > 0) Icon(painterResource(R.drawable.ic_arrow_forward), contentDescription = null, tint = Muted, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun Figure(label: String, modifier: Modifier, value: @Composable () -> Unit) {
    Column(modifier) {
        Text(label, fontFamily = Mono, fontSize = 10.sp, letterSpacing = 1.sp, color = Muted)
        value()
    }
}

/** One weapon with its equipped skin and how many skins are owned for it. */
@Composable
private fun WeaponTile(weapon: EquippedWeapon, owned: Int, onClick: () -> Unit) {
    val skin = weapon.skin
    Column(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(InkRaised)
            .clickable(role = Role.Button, onClickLabel = "See ${weapon.weaponName} skins", onClick = onClick)
            .padding(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(weapon.weaponName.uppercase(), fontFamily = Mono, fontSize = 9.sp, color = Muted, maxLines = 1, modifier = Modifier.weight(1f))
            if (owned > 0) Text("$owned", fontFamily = Mono, fontSize = 9.sp, color = Muted)
        }
        AsyncImage(
            model = skin?.iconUrl,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp)
                .padding(vertical = 4.dp),
        )
        Text(skin?.name ?: "Standard", fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Owned skins two to a row, or [empty] when there are none. [equipped] is the skin UUID to mark as equipped. */
@Composable
internal fun OwnedSkinGrid(
    skins: List<OwnedSkin>,
    onOpenSkin: (StoreSkin) -> Unit,
    empty: String,
    equipped: String? = null,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(skins, key = { "owned-" + it.skin.levelUuid }) { owned ->
            SkinTile(owned, equipped = equipped != null && owned.skin.skinUuid == equipped, onClick = { onOpenSkin(owned.skin) })
        }
        if (skins.isEmpty()) fullWidth("empty") { Text(empty, color = Muted, modifier = Modifier.padding(vertical = 16.dp)) }
    }
}

@Composable
private fun SkinTile(owned: OwnedSkin, equipped: Boolean, onClick: () -> Unit) {
    val skin = owned.skin
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
                .aspectRatio(2.4f),
            contentAlignment = Alignment.Center,
        ) {
            AsyncImage(model = skin.iconUrl, contentDescription = skin.name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            if (equipped) {
                Text("EQUIPPED", fontFamily = Mono, fontSize = 9.sp, color = tierColor, modifier = Modifier.align(Alignment.TopStart))
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (skin.tierIconUrl != null) {
                AsyncImage(model = skin.tierIconUrl, contentDescription = skin.tierName, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
            }
            Text(skin.name, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        }
        // What's left to unlock, as a nudge to finish the skin.
        owned.radianiteToMax?.takeIf { it > 0 }?.let {
            Amount(it, currency = Currency.RADIANITE_POINTS, prefix = "MAX", fontFamily = Mono, fontSize = 10.sp, color = Muted, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

private fun LazyGridScope.fullWidth(key: String, content: @Composable () -> Unit) =
    item(key = key, span = { GridItemSpan(maxLineSpan) }) { content() }

private const val PLACEHOLDER_TILES = 9
