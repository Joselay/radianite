package app.radianite.data.store

import app.radianite.data.content.ApiContentTier
import app.radianite.data.content.ApiSkin
import app.radianite.data.content.ApiSkinLevel
import app.radianite.data.content.buildCatalog
import app.radianite.data.riot.StorefrontResponse
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DailyStoreTest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    private val catalog = buildCatalog(
        version = "release-13.08",
        skins = listOf(
            ApiSkin(
                uuid = "reaver",
                displayName = "Reaver Vandal",
                displayIcon = "skin.png",
                contentTierUuid = "60BCA009-4182-7998-DEE7-B8A2558DC369",
                levels = listOf(ApiSkinLevel("AAAA-1", "level1.png"), ApiSkinLevel("aaaa-2", null)),
            ),
        ),
        tiers = listOf(ApiContentTier("60bca009-4182-7998-dee7-b8a2558dc369", "Premium", "d1548d33", "premium.png")),
    )

    @Test
    fun `maps storefront offers to display skins via the catalog`() {
        val body = """
            {
              "FeaturedBundle": {"Bundles": []},
              "SkinsPanelLayout": {
                "SingleItemOffers": ["aaaa-1", "bbbb-1"],
                "SingleItemStoreOffers": [
                  {"OfferID": "aaaa-1", "IsDirectPurchase": true, "StartDate": "2026-10-16T00:00:00Z",
                   "Cost": {"$VALORANT_POINTS_UUID": 1775},
                   "Rewards": [{"ItemTypeID": "e7c63390", "ItemID": "aaaa-1", "Quantity": 1}]},
                  {"OfferID": "bbbb-1", "Cost": {}, "Rewards": [{"ItemTypeID": "e7c63390", "ItemID": "bbbb-1", "Quantity": 1}]}
                ],
                "SingleItemOffersRemainingDurationInSeconds": 3600
              }
            }
        """.trimIndent()

        val store = json.decodeFromString<StorefrontResponse>(body).toDailyStore("puuid-1", catalog, nowMillis = 1_000)

        assertEquals("puuid-1", store.puuid)
        assertEquals(1_000 + 3_600_000L, store.expiresAtMillis)
        assertEquals(2, store.skins.size)

        val reaver = store.skins[0]
        assertEquals("Reaver Vandal", reaver.name)
        assertEquals("reaver", reaver.skinUuid)
        assertEquals("level1.png", reaver.iconUrl)
        assertEquals(1775, reaver.priceVp)
        assertEquals("Premium", reaver.tierName)
        assertEquals("d1548d33", reaver.tierColor)

        val unknown = store.skins[1]
        assertEquals("Unknown skin", unknown.name)
        assertNull(unknown.priceVp)
        assertNull(unknown.tierName)
    }

    @Test
    fun `missing skins panel yields an empty store`() {
        val store = StorefrontResponse(null).toDailyStore("p", catalog, nowMillis = 5)
        assertEquals(emptyList<StoreSkin>(), store.skins)
        assertEquals(5L, store.expiresAtMillis)
    }
}
