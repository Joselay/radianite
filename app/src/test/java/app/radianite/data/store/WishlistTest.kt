package app.radianite.data.store

import app.radianite.data.LocalStore
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WishlistTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val device get() = LocalStore(tmp.root, Json)

    private fun skin(uuid: String, price: Int? = null) =
        StoreSkin("$uuid-level", skinUuid = uuid, name = uuid, iconUrl = null, priceVp = price, tierName = null, tierColor = null, tierIconUrl = null)

    private val hour = 60 * 60 * 1000L

    private fun store(puuid: String = "me", daily: List<StoreSkin> = emptyList(), night: List<StoreSkin> = emptyList(), expiresAt: Long = 24 * hour) =
        DailyStore(
            puuid = puuid,
            skins = daily,
            expiresAtMillis = expiresAt,
            nightMarket = night.map { NightMarketOffer(it, basePriceVp = 2000, discountPercent = 30) },
            nightMarketExpiresAtMillis = if (night.isEmpty()) null else 10 * 24 * hour,
        )

    @Test
    fun `toggling adds a skin without its price, newest first, and takes it off again`() = runTest {
        val wishlist = Wishlist(device)

        assertTrue(wishlist.toggle(skin("reaver", price = 1775)))
        assertTrue(wishlist.toggle(skin("prime")))

        assertEquals(listOf("prime", "reaver"), Wishlist(device).load().map { it.skinUuid })
        assertEquals(null, wishlist.skins.value.last().priceVp)

        assertFalse(wishlist.toggle(skin("reaver")))
        assertEquals(listOf("prime"), wishlist.skins.value.map { it.skinUuid })
    }

    @Test
    fun `a wishlisted skin in the store is announced once per rotation`() = runTest {
        val wishlist = Wishlist(device).apply { toggle(skin("reaver")) }

        assertEquals(listOf("reaver"), wishlist.unannounced(store(daily = listOf(skin("reaver", 1775), skin("other")))).map { it.skinUuid })
        // Fetched again a little later: Riot's countdown puts the expiry a second off.
        assertEquals(emptyList<StoreSkin>(), wishlist.unannounced(store(daily = listOf(skin("reaver", 1775)), expiresAt = 24 * hour - 1_000)))
        // The next day's store has it again.
        assertEquals(1, wishlist.unannounced(store(daily = listOf(skin("reaver", 1775)), expiresAt = 48 * hour)).size)
    }

    @Test
    fun `each account's store and the Night Market count`() = runTest {
        val wishlist = Wishlist(device).apply { toggle(skin("reaver")); toggle(skin("prime")) }

        assertEquals(listOf("prime"), wishlist.unannounced(store(night = listOf(skin("prime")))).map { it.skinUuid })
        assertEquals(emptyList<StoreSkin>(), wishlist.unannounced(store(night = listOf(skin("prime")), expiresAt = 48 * hour)))
        assertEquals(listOf("prime"), wishlist.unannounced(store(puuid = "alt", night = listOf(skin("prime")))).map { it.skinUuid })
    }
}
