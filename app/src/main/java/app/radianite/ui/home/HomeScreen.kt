package app.radianite.ui.home

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.radianite.AppContainer
import app.radianite.R
import app.radianite.data.match.MatchSummary
import app.radianite.data.store.StoreSkin
import app.radianite.ui.AppUiState
import app.radianite.ui.career.CareerScreen
import app.radianite.ui.career.CareerViewModel
import app.radianite.ui.career.LeaderboardScreen
import app.radianite.ui.career.LeaderboardViewModel
import app.radianite.ui.collection.CollectionScreen
import app.radianite.ui.collection.CollectionViewModel
import app.radianite.ui.common.LoadingPane
import app.radianite.ui.common.LocalGameContent
import app.radianite.ui.matches.MatchDetailScreen
import app.radianite.ui.matches.MatchesScreen
import app.radianite.ui.matches.MatchesViewModel
import app.radianite.ui.skin.SkinDetailScreen
import app.radianite.ui.skin.SkinDetailViewModel
import app.radianite.ui.store.StoreScreen
import app.radianite.ui.store.StoreViewModel
import app.radianite.ui.theme.InkRaised
import app.radianite.ui.theme.Muted
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

private enum class Tab(val label: String, @DrawableRes val icon: Int) {
    STORE("Store", R.drawable.ic_tab_store),
    MATCHES("Matches", R.drawable.ic_tab_matches),
    CAREER("Career", R.drawable.ic_tab_career),
    COLLECTION("Collection", R.drawable.ic_tab_collection),
}

/** Overlays that cover the tabs; each has its own back handling. */
private sealed interface Overlay {
    data class Skin(val skin: StoreSkin) : Overlay
    data class Match(val matchId: String) : Overlay
    data object Leaderboard : Overlay
}

/**
 * The signed-in app. Tab view models are created the first time a tab opens and keyed by account,
 * so switching tabs keeps their data and a new sign-in starts fresh.
 */
@Composable
fun HomeScreen(
    container: AppContainer,
    state: AppUiState.SignedIn,
    onSignOut: () -> Unit,
) {
    val content by container.content.game.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) {
        // Screens show placeholders until names and art arrive; keep trying while offline.
        while (orNull { container.content.gameContent() } == null) delay(CONTENT_RETRY_MILLIS)
    }
    CompositionLocalProvider(LocalGameContent provides content) {
        SignedInTabs(container, state, onSignOut)
    }
}

@Composable
private fun SignedInTabs(container: AppContainer, state: AppUiState.SignedIn, onSignOut: () -> Unit) {
    var tab by rememberSaveable { mutableStateOf(Tab.STORE) }
    var overlay by remember { mutableStateOf<Overlay?>(null) }
    val account = state.puuid

    when (val o = overlay) {
        is Overlay.Skin -> {
            // Only skins with a known UUID are openable, so this is always set.
            val skinUuid = checkNotNull(o.skin.skinUuid)
            SkinDetailScreen(
                skin = o.skin,
                viewModel = viewModel(key = "skin-$skinUuid") { SkinDetailViewModel(container.content, skinUuid) },
                newPlayer = container::newVideoPlayer,
                onBack = { overlay = null },
            )
            return
        }
        is Overlay.Match -> {
            val match by produceState<MatchLookup>(MatchLookup.Loading, o.matchId) {
                value = orNull { container.matches.summary(o.matchId) }?.let(MatchLookup::Found) ?: MatchLookup.Missing
            }
            when (val m = match) {
                MatchLookup.Loading -> LoadingPane()
                is MatchLookup.Found -> MatchDetailScreen(m.summary, onBack = { overlay = null })
                // Sign-out or a cleared cache dropped the match; fall back to the tabs.
                MatchLookup.Missing -> LaunchedEffect(o) { overlay = null }
            }
            if (match != MatchLookup.Missing) return
        }
        Overlay.Leaderboard -> {
            LeaderboardScreen(
                viewModel = viewModel(key = "leaderboard-$account") { LeaderboardViewModel(container.players) },
                onBack = { overlay = null },
            )
            return
        }
        null -> Unit
    }

    Scaffold(
        bottomBar = {
            NavigationBar(containerColor = InkRaised) {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = { Icon(painterResource(t.icon), contentDescription = null) },
                        label = { Text(t.label) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                            unselectedIconColor = Muted,
                            unselectedTextColor = Muted,
                        ),
                    )
                }
            }
        },
    ) { padding ->
        // Each tab brings its own top bar (which handles the status bar), so only the bottom bar's space is taken here.
        val bottom = PaddingValues(bottom = padding.calculateBottomPadding())
        Box(
            Modifier
                .padding(bottom)
                .consumeWindowInsets(bottom)
                .fillMaxSize(),
        ) {
            when (tab) {
                Tab.STORE -> StoreScreen(
                    riotId = state.riotId,
                    viewModel = viewModel(key = "store-$account") { StoreViewModel(container.store) },
                    onSignOut = onSignOut,
                    onOpenSkin = { overlay = Overlay.Skin(it) },
                )
                Tab.MATCHES -> MatchesScreen(
                    riotId = state.riotId,
                    viewModel = viewModel(key = "matches-$account") { MatchesViewModel(container.matches) },
                    onOpenMatch = { overlay = Overlay.Match(it.record.matchId) },
                )
                Tab.CAREER -> CareerScreen(
                    viewModel = viewModel(key = "career-$account") { CareerViewModel(container.players, container.matches) },
                    onOpenLeaderboard = { overlay = Overlay.Leaderboard },
                    onSignOut = onSignOut,
                )
                Tab.COLLECTION -> CollectionScreen(
                    viewModel = viewModel(key = "collection-$account") { CollectionViewModel(container.players) },
                    onOpenSkin = { overlay = Overlay.Skin(it) },
                )
            }
        }
    }
}

private const val CONTENT_RETRY_MILLIS = 30_000L

private sealed interface MatchLookup {
    data object Loading : MatchLookup
    data class Found(val summary: MatchSummary) : MatchLookup
    data object Missing : MatchLookup
}

/** For loads a screen can do without: any failure but cancellation reads as null. */
private suspend fun <T> orNull(block: suspend () -> T): T? = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    null
}
