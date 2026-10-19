package app.radianite.data.store

import app.radianite.data.LocalStore
import app.radianite.data.Slots
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.roundToLong

/**
 * Skins the user is waiting for. Kept for the device, whichever account is signed in, so a skin turning
 * up in any signed-in account's store counts.
 */
class Wishlist(private val device: LocalStore) {
    private val mutex = Mutex()
    private var loaded = false
    private val _skins = MutableStateFlow<List<StoreSkin>>(emptyList())

    /** Wishlisted skins, newest first. Empty until [load] or the first change. */
    val skins: StateFlow<List<StoreSkin>> = _skins.asStateFlow()

    suspend fun load(): List<StoreSkin> = mutex.withLock { current() }

    /** Adds [skin] or takes it off; true when it's now on the wishlist. */
    suspend fun toggle(skin: StoreSkin): Boolean = mutex.withLock {
        val uuid = skin.skinUuid ?: return@withLock false
        val skins = current()
        val adding = skins.none { it.skinUuid == uuid }
        // Kept without a price: what it costs depends on the store it turns up in.
        save(if (adding) listOf(skin.copy(priceVp = null)) + skins else skins.filterNot { it.skinUuid == uuid })
        adding
    }

    suspend fun remove(skinUuid: String) = mutex.withLock {
        save(current().filterNot { it.skinUuid == skinUuid })
    }

    /**
     * Wishlisted skins on offer in [store] that haven't been announced for this store yet, now marked as
     * announced. A Night Market offer is announced once for the whole Night Market.
     */
    suspend fun unannounced(store: DailyStore): List<StoreSkin> = mutex.withLock {
        val offers = inStore(store, current())
        if (offers.isEmpty()) return@withLock emptyList()
        val announced = device.read(Slots.storeAlerts).orEmpty()
        val fresh = offers.filter { (skin, endsAt) -> alertKey(store.puuid, skin, endsAt) !in announced }
        if (fresh.isNotEmpty()) {
            val keys = fresh.map { (skin, endsAt) -> alertKey(store.puuid, skin, endsAt) }
            device.write(Slots.storeAlerts, (announced + keys).takeLast(MAX_ANNOUNCED))
        }
        fresh.map { it.first }
    }

    private suspend fun current(): List<StoreSkin> {
        if (!loaded) {
            _skins.value = device.read(Slots.wishlist).orEmpty()
            loaded = true
        }
        return _skins.value
    }

    private suspend fun save(skins: List<StoreSkin>) {
        device.write(Slots.wishlist, skins)
        _skins.value = skins
    }

    private companion object {
        const val MAX_ANNOUNCED = 200
        const val HOUR = 60 * 60 * 1000L

        // Expiry times are worked out from Riot's "seconds remaining", so they wobble by a second or two
        // between fetches; to the hour they name the same rotation.
        fun alertKey(puuid: String, skin: StoreSkin, endsAtMillis: Long) =
            "$puuid:${skin.skinUuid}:${(endsAtMillis.toDouble() / HOUR).roundToLong()}"
    }
}

/** Wishlisted skins in [store]'s daily offers and Night Market, each with when that offer ends. */
fun inStore(store: DailyStore, wishlist: List<StoreSkin>): List<Pair<StoreSkin, Long>> {
    val wanted = wishlist.mapNotNull { it.skinUuid }.toSet()
    val daily = store.skins.filter { it.skinUuid in wanted }.map { it to store.expiresAtMillis }
    val night = store.nightMarketExpiresAtMillis?.let { endsAt ->
        store.nightMarket.filter { it.skin.skinUuid in wanted }.map { it.skin to endsAt }
    }.orEmpty()
    return daily + night
}
