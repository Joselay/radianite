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

/** The signed-in account's Daily Store, Night Market, featured bundles and wallet. */
class StoreRepository(
    private val riot: RiotPlayerData,
    private val content: Content,
    private val local: LocalStore,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** The last store fetched for this account, if it hasn't rotated yet. Shown while [refresh] runs. */
    suspend fun cached(): StoreSnapshot? {
        val puuid = riot.savedSession()?.puuid ?: return null
        val store = local.read(Slots.dailyStore)?.takeIf { it.puuid == puuid && it.expiresAtMillis > clock() } ?: return null
        return StoreSnapshot(store, wallet = null)
    }

    /** Fetches and saves the current store. The wallet is best-effort: the store shows without it. */
    suspend fun refresh(): StoreSnapshot = coroutineScope {
        val catalog = async { content.catalog() }
        val wallet = async { optional("Wallet") { riot.wallet().toWallet() } }
        val front = riot.storefront()
        val puuid = riot.puuid()
        val cat = catalog.await()
        front.featuredBundles(cat).takeIf { it.isNotEmpty() }?.let { local.write(Slots.featuredBundles, it) }
        val store = front.toDailyStore(puuid, cat, clock()).also { local.write(Slots.dailyStore, it) }
        StoreSnapshot(store, wallet.await())
    }

    /** Featured bundles from the last store fetch, kept across sign-outs for the welcome screen. */
    suspend fun featuredBundles(): List<FeaturedBundle> = local.read(Slots.featuredBundles).orEmpty()
}
