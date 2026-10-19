package app.radianite.data.store

import app.radianite.data.content.ApiContentTier
import app.radianite.data.content.ApiSkin
import app.radianite.data.content.ApiSkinLevel
import app.radianite.data.content.ApiWeaponSkins
import app.radianite.data.content.CatalogBundle
import app.radianite.data.content.buildCatalog
import app.radianite.data.riot.StorefrontResponse
import app.radianite.data.riot.WalletResponse
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DailyStoreTest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    private val catalog = buildCatalog(
        version = "release-13.08",
        weapons = listOf(
            ApiWeaponSkins(
                "vandal",
                listOf(
                    ApiSkin(
                        uuid = "reaver",
                        displayName = "Reaver Vandal",
                        displayIcon = "skin.png",
                        contentTierUuid = "60BCA009-4182-7998-DEE7-B8A2558DC369",
                        levels = listOf(ApiSkinLevel("AAAA-1", "level1.png"), ApiSkinLevel("aaaa-2", null)),
                    ),
                ),
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

    @Test
    fun `maps the night market and featured bundle prices`() {
        val body = """
            {
              "FeaturedBundle": {"Bundles": [
                {"DataAssetID": "BUNDLE-1", "DurationRemainingInSeconds": 7200,
                 "TotalBaseCost": {"$VALORANT_POINTS_UUID": 9000}, "TotalDiscountedCost": {"$VALORANT_POINTS_UUID": 7100}}
              ]},
              "BonusStore": {
                "BonusStoreOffers": [
                  {"BonusOfferID": "b1", "DiscountPercent": 33, "DiscountCosts": {"$VALORANT_POINTS_UUID": 1189},
                   "Offer": {"OfferID": "aaaa-1", "Cost": {"$VALORANT_POINTS_UUID": 1775},
                             "Rewards": [{"ItemTypeID": "e7c63390", "ItemID": "AAAA-1", "Quantity": 1}]}}
                ],
                "BonusStoreRemainingDurationInSeconds": 86400
              }
            }
        """.trimIndent()
        val withBundle = catalog.copy(bundles = mapOf("bundle-1" to CatalogBundle("Reaver 2.0", "art.png")))

        val store = json.decodeFromString<StorefrontResponse>(body).toDailyStore("p", withBundle, nowMillis = 0)

        val bundle = store.bundles.single()
        assertEquals("Reaver 2.0", bundle.name)
        assertEquals(7100, bundle.priceVp)
        assertEquals(9000, bundle.basePriceVp)
        assertEquals(7_200_000L, bundle.expiresAtMillis)

        val offer = store.nightMarket.single()
        assertEquals("Reaver Vandal", offer.skin.name)
        assertEquals(1189, offer.skin.priceVp)
        assertEquals(1775, offer.basePriceVp)
        assertEquals(33, offer.discountPercent)
        assertEquals(86_400_000L, store.nightMarketExpiresAtMillis)
    }

    @Test
    fun `no night market when the bonus store is absent`() {
        val store = StorefrontResponse(null).toDailyStore("p", catalog, nowMillis = 5)
        assertEquals(emptyList<NightMarketOffer>(), store.nightMarket)
        assertNull(store.nightMarketExpiresAtMillis)
    }

    @Test
    fun `reads wallet balances by currency`() {
        val wallet = json.decodeFromString<WalletResponse>(
            """{"Balances": {"$VALORANT_POINTS_UUID": 1250, "$RADIANITE_POINTS_UUID": 40, "$KINGDOM_CREDITS_UUID": 8000}}""",
        ).toWallet()
        assertEquals(Wallet(valorantPoints = 1250, radianite = 40, kingdomCredits = 8000), wallet)
    }
}
