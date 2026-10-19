package app.radianite.data.store

import app.radianite.data.LocalStore
import app.radianite.data.Slots
import app.radianite.data.content.Content
import app.radianite.data.optional
import app.radianite.data.riot.RiotPlayerData
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/** The Daily Store with the wallet beside it; [wallet] is null when Riot didn't return it. */
data class StoreSnapshot(val store: DailyStore, val wallet: Wallet?)

/** One account's Daily Store, Night Market, featured bundles and wallet. */
class StoreRepository(
    private val riot: RiotPlayerData,
    private val content: Content,
    /** The account's own store. */
    private val local: LocalStore,
    /** The app's store, where featured bundles outlive the account for the welcome screen. */
    private val device: LocalStore,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Told about every store fetched, e.g. to redraw a home-screen widget. */
    private val onRefreshed: suspend (StoreSnapshot) -> Unit = {},
) {
    /**
     * The last store fetched for this account, if it hasn't rotated yet, with the last wallet seen.
     * Shown while [refresh] runs.
     */
    suspend fun cached(): StoreSnapshot? {
        val puuid = riot.savedSession()?.puuid ?: return null
        val store = local.read(Slots.dailyStore)?.takeIf { it.puuid == puuid && it.expiresAtMillis > clock() } ?: return null
        return StoreSnapshot(store, wallet = local.read(Slots.wallet))
    }

    /** Fetches and saves the current store. The wallet is best-effort: the store shows without it. */
    suspend fun refresh(): StoreSnapshot = coroutineScope {
        val catalog = async { content.catalog() }
        val wallet = async { optional("Wallet") { riot.wallet().toWallet() } }
        val front = riot.storefront()
        val puuid = riot.puuid()
        val cat = catalog.await()
        front.featuredBundles(cat).takeIf { it.isNotEmpty() }?.let { device.write(Slots.featuredBundles, it) }
        val store = front.toDailyStore(puuid, cat, clock()).also { local.write(Slots.dailyStore, it) }
        val snapshot = StoreSnapshot(store, wallet.await()?.also { local.write(Slots.wallet, it) })
        onRefreshed(snapshot)
        snapshot
    }
}

/** Featured bundles from the last store fetch of any account, kept across sign-outs for the welcome screen. */
suspend fun LocalStore.featuredBundles(): List<FeaturedBundle> = read(Slots.featuredBundles).orEmpty()
