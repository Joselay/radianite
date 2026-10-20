package app.radianite.ui.home

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.IntOffset
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.radianite.AppContainer
import app.radianite.R
import app.radianite.data.account.SavedAccount
import app.radianite.data.match.MatchSummary
import app.radianite.data.store.StoreSkin
import app.radianite.ui.AppUiState
import app.radianite.ui.account.AccountsSheet
import app.radianite.ui.career.CareerScreen
import app.radianite.ui.career.CareerViewModel
import app.radianite.ui.career.LeaderboardScreen
import app.radianite.ui.career.LeaderboardViewModel
import app.radianite.ui.collection.CollectionScreen
import app.radianite.ui.collection.CollectionViewModel
import app.radianite.ui.common.LoadingPane
import app.radianite.ui.common.LocalAccountCard
import app.radianite.ui.common.LocalGameContent
import app.radianite.ui.common.rememberMotionEnabled
import app.radianite.ui.matches.MatchDetailScreen
import app.radianite.ui.matches.MatchesScreen
import app.radianite.ui.matches.MatchesViewModel
import app.radianite.ui.skin.SkinDetailScreen
import app.radianite.ui.skin.SkinDetailViewModel
import app.radianite.ui.store.StoreScreen
import app.radianite.ui.store.StoreViewModel
import app.radianite.ui.theme.InkRaised
import app.radianite.ui.theme.Muted
import app.radianite.ui.wishlist.WishlistScreen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class Tab(val label: String, @DrawableRes val icon: Int) {
    STORE("Store", R.drawable.ic_tab_store),
    MATCHES("Matches", R.drawable.ic_tab_matches),
    CAREER("Career", R.drawable.ic_tab_career),
    COLLECTION("Collection", R.drawable.ic_tab_collection),
}

/** Screens that open over the tabs, stacked; each has its own back handling. */
private sealed interface Overlay {
    /** Names the overlay's saved state, so a screen under another keeps its scroll position. */
    val key: String

    data class Skin(val skin: StoreSkin) : Overlay {
        override val key get() = "skin-${skin.skinUuid}"
    }

    data class Match(val matchId: String) : Overlay {
        override val key get() = "match-$matchId"
    }

    data object Leaderboard : Overlay {
        override val key get() = "leaderboard"
    }

    data object Wishlist : Overlay {
        override val key get() = "wishlist"
    }
}

/**
 * The signed-in app. Tab view models are created the first time a tab opens and keyed by account,
 * so switching tabs keeps their data and another account starts fresh.
 */
@Composable
fun HomeScreen(
    container: AppContainer,
    state: AppUiState.SignedIn,
    accounts: List<SavedAccount>,
    onSwitchAccount: (puuid: String) -> Unit,
    onAddAccount: () -> Unit,
    onSignOut: () -> Unit,
) {
    val content by container.content.game.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        // Screens show placeholders until names and art arrive; keep trying while offline, but only while
        // the app is in front, so being offline in the background costs nothing.
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (orNull { container.content.gameContent() } == null) delay(CONTENT_RETRY_MILLIS)
        }
    }
    var showAccounts by rememberSaveable { mutableStateOf(false) }
    val card = accounts.find { it.puuid == state.puuid }?.cardId
    CompositionLocalProvider(LocalGameContent provides content, LocalAccountCard provides card) {
        SignedInTabs(container, state, onOpenAccounts = { showAccounts = true })
    }
    if (showAccounts) {
        AccountsSheet(
            accounts = accounts,
            shownPuuid = state.puuid,
            onSwitch = onSwitchAccount,
            onAddAccount = onAddAccount,
            onSignOut = onSignOut,
            onDismiss = { showAccounts = false },
        )
    }
}

@Composable
private fun SignedInTabs(container: AppContainer, state: AppUiState.SignedIn, onOpenAccounts: () -> Unit) {
    val account = state.puuid
    val data = container.accountData(account)
    var tab by rememberSaveable { mutableStateOf(Tab.STORE) }
    var overlays by remember(account) { mutableStateOf(emptyList<Overlay>()) }
    // Keeps each tab's and overlay's remembered state (scroll position, search text) while it's off screen.
    val saved = rememberSaveableStateHolder()
    val scope = rememberCoroutineScope()
    val wishlist by container.wishlist.skins.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { container.wishlist.load() }

    fun open(overlay: Overlay) {
        overlays = overlays + overlay
    }

    fun back() {
        overlays = overlays.dropLast(1)
    }

    // Back from another tab goes to the store first, as in most tabbed apps, rather than closing the app.
    BackHandler(enabled = overlays.isEmpty() && tab != Tab.STORE) { tab = Tab.STORE }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            // Hidden from screen readers while an overlay covers it.
            modifier = if (overlays.isEmpty()) Modifier else Modifier.clearAndSetSemantics {},
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
                saved.SaveableStateProvider("$account-${tab.name}") {
                    when (tab) {
                        Tab.STORE -> StoreScreen(
                            riotId = state.riotId,
                            viewModel = viewModel(key = "store-$account") { StoreViewModel(data.store) },
                            wishlisted = remember(wishlist) { wishlist.mapNotNull { it.skinUuid }.toSet() },
                            onOpenAccounts = onOpenAccounts,
                            onOpenSkin = { open(Overlay.Skin(it)) },
                            onOpenWishlist = { open(Overlay.Wishlist) },
                        )
                        Tab.MATCHES -> MatchesScreen(
                            riotId = state.riotId,
                            viewModel = viewModel(key = "matches-$account") { MatchesViewModel(data.matches) },
                            onOpenAccounts = onOpenAccounts,
                            onOpenMatch = { open(Overlay.Match(it.record.matchId)) },
                        )
                        Tab.CAREER -> CareerScreen(
                            viewModel = viewModel(key = "career-$account") { CareerViewModel(data.players, data.matches) },
                            onOpenLeaderboard = { open(Overlay.Leaderboard) },
                            onOpenAccounts = onOpenAccounts,
                        )
                        Tab.COLLECTION -> CollectionScreen(
                            viewModel = viewModel(key = "collection-$account") { CollectionViewModel(data.players) },
                            onOpenAccounts = onOpenAccounts,
                            onOpenSkin = { open(Overlay.Skin(it)) },
                        )
                    }
                }
            }
        }

        val motion = rememberMotionEnabled()
        AnimatedContent(
            targetState = overlays,
            contentKey = { it.lastOrNull()?.key },
            transitionSpec = { overlayTransition(forward = targetState.size >= initialState.size, motion) },
            label = "overlay",
        ) { stack ->
            val top = stack.lastOrNull() ?: return@AnimatedContent
            saved.SaveableStateProvider("$account-${top.key}") {
                when (top) {
                    is Overlay.Skin -> {
                        // Only skins with a known UUID are openable, so this is always set.
                        val skinUuid = checkNotNull(top.skin.skinUuid)
                        SkinDetailScreen(
                            skin = top.skin,
                            viewModel = viewModel(key = "skin-$skinUuid") { SkinDetailViewModel(container.content, skinUuid) },
                            newPlayer = container::newVideoPlayer,
                            wishlisted = wishlist.any { it.skinUuid == skinUuid },
                            onToggleWishlist = { scope.launch { container.wishlist.toggle(top.skin) } },
                            onBack = ::back,
                        )
                    }
                    is Overlay.Match -> {
                        val match by produceState<MatchLookup>(MatchLookup.Loading, top.matchId) {
                            value = orNull { data.matches.summary(top.matchId) }?.let(MatchLookup::Found) ?: MatchLookup.Missing
                        }
                        when (val m = match) {
                            MatchLookup.Loading -> LoadingPane()
                            is MatchLookup.Found -> MatchDetailScreen(m.summary, onBack = ::back)
                            // Sign-out or a cleared cache dropped the match; fall back to what's underneath.
                            MatchLookup.Missing -> LaunchedEffect(top) { back() }
                        }
                    }
                    Overlay.Leaderboard -> LeaderboardScreen(
                        viewModel = viewModel(key = "leaderboard-$account") { LeaderboardViewModel(data.players) },
                        onBack = ::back,
                    )
                    Overlay.Wishlist -> {
                        val store by viewModel(key = "store-$account") { StoreViewModel(data.store) }.state.collectAsStateWithLifecycle()
                        val inStoreNow = remember(store.store) {
                            store.store?.let { s -> (s.skins + s.nightMarket.map { it.skin }).mapNotNull { it.skinUuid }.toSet() }.orEmpty()
                        }
                        WishlistScreen(
                            skins = wishlist,
                            inStoreNow = inStoreNow,
                            onOpenSkin = { if (it.skinUuid != null) open(Overlay.Skin(it)) },
                            onRemove = { skin -> skin.skinUuid?.let { scope.launch { container.wishlist.remove(it) } } },
                            onBack = ::back,
                        )
                    }
                }
            }
        }
    }
}

/** Overlays slide in from the end edge over what's underneath, which stays put, and slide back out on the way back. */
private fun overlayTransition(forward: Boolean, motion: Boolean): ContentTransform {
    if (!motion) return EnterTransition.None togetherWith ExitTransition.None
    val slide = tween<IntOffset>(OVERLAY_MILLIS)
    return if (forward) {
        // A "fade" to full opacity: the screen underneath stays drawn, unchanged, until the new one covers it.
        slideInHorizontally(slide) { it } togetherWith fadeOut(tween(OVERLAY_MILLIS), targetAlpha = 1f)
    } else {
        // The leaving screen stays on top as it slides away, uncovering the one beneath.
        (EnterTransition.None togetherWith slideOutHorizontally(slide) { it }).apply { targetContentZIndex = -1f }
    }
}

private const val OVERLAY_MILLIS = 260
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
