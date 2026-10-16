package app.radianite.data.store

import app.radianite.data.AppFiles
import app.radianite.data.content.SkinCatalogRepository
import app.radianite.data.riot.RiotApi
import app.radianite.data.riot.SessionManager
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.builtins.ListSerializer

class StoreRepository(
    private val riot: RiotApi,
    private val sessions: SessionManager,
    private val catalog: SkinCatalogRepository,
    private val files: AppFiles,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** Last fetched store for this account, if it hasn't rotated yet. */
    suspend fun cachedStore(puuid: String): DailyStore? =
        files.read(files.dailyStore, DailyStore.serializer())?.takeIf { it.puuid == puuid && it.expiresAtMillis > clock() }

    /** Featured bundles from the last store fetch, kept across sign-outs for the welcome screen. */
    suspend fun featuredBundles(): List<FeaturedBundle> = files.read(files.featuredBundles, FeaturedBundleList).orEmpty()

    suspend fun fetchDailyStore(): DailyStore = coroutineScope {
        val catalogJob = async { catalog.catalog(sessions.clientVersion()) }
        val (puuid, front) = sessions.call { session, version -> session.puuid to riot.storefront(session, version) }
        val catalog = catalogJob.await()
        front.featuredBundles(catalog).takeIf { it.isNotEmpty() }?.let { files.write(files.featuredBundles, FeaturedBundleList, it) }
        front.toDailyStore(puuid, catalog, clock())
            .also { files.write(files.dailyStore, DailyStore.serializer(), it) }
    }

    suspend fun fetchWallet(): Wallet = sessions.call { session, version -> riot.wallet(session, version) }.toWallet()

    private companion object {
        val FeaturedBundleList = ListSerializer(FeaturedBundle.serializer())
    }
}
