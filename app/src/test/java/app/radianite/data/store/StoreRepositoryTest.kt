package app.radianite.data.store

import app.radianite.data.Fixture
import app.radianite.data.Reply
import app.radianite.data.content.CosmeticKind
import app.radianite.data.riot.ItemTypes
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class StoreRepositoryTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun Fixture.store(): StoreRepository {
        servers.json(
            "valorant-api.com/v1/weapons",
            """{"data":[{"uuid":"vandal","skins":[{"uuid":"reaver","displayName":"Reaver Vandal","levels":[{"uuid":"level-1"}]}]}]}""",
        )
        servers.json(
            "valorant-api.com/v1/bundles",
            """{"data":[{"uuid":"bundle-1","displayName":"Reaver 2.0","verticalPromoImage":"reaver.png"}]}""",
        )
        servers.json(
            "/store/v3/storefront/",
            """
            {"SkinsPanelLayout": {"SingleItemOffersRemainingDurationInSeconds": 3600,
              "SingleItemStoreOffers": [{"OfferID": "level-1", "Cost": {"$VALORANT_POINTS_UUID": 1775},
                                         "Rewards": [{"ItemTypeID": "x", "ItemID": "level-1"}]}]},
             "FeaturedBundle": {"Bundles": [{"DataAssetID": "bundle-1", "DurationRemainingInSeconds": 7200}]}}
            """.trimIndent(),
        )
        servers.json("/store/v1/wallet/", """{"Balances":{"$VALORANT_POINTS_UUID":1000}}""")
        return StoreRepository(riot, content, local, device, clock = { now })
    }

    @Test
    fun `a refresh names the offers, attaches the wallet and is cached until the store rotates`() = runTest {
        val app = Fixture(tmp.root).apply { signIn() }
        val store = app.store()

        val fresh = store.refresh()

        assertEquals("Reaver Vandal", fresh.store.skins.single().name)
        assertEquals(1000, fresh.wallet?.valorantPoints)
        assertEquals(fresh, store.cached())
        assertEquals(listOf(FeaturedBundle("Reaver 2.0", "reaver.png")), app.device.featuredBundles())

        app.now += 3600 * 1000
        assertNull("rotated", store.cached())
    }

    @Test
    fun `the Accessory Store's offers are named, priced in Kingdom Credits and end with its rotation`() = runTest {
        val app = Fixture(tmp.root).apply { signIn() }
        val store = app.store()
        app.servers.json(
            "/store/v3/storefront/",
            """
            {"AccessoryStore": {"AccessoryStoreRemainingDurationInSeconds": 86400,
              "AccessoryStoreOffers": [
                {"Offer": {"OfferID": "o-1", "Cost": {"$KINGDOM_CREDITS_UUID": 4000},
                           "Rewards": [{"ItemTypeID": "D5F120F8-FF8C-4AAC-92EA-F2B5ACBE9475", "ItemID": "SPRAY-1", "Quantity": 1}]}},
                {"Offer": {"OfferID": "o-2", "Cost": {"$KINGDOM_CREDITS_UUID": 2000},
                           "Rewards": [{"ItemTypeID": "unknown-type", "ItemID": "thing"}]}},
                {"Offer": {"OfferID": "o-3", "Cost": {"$KINGDOM_CREDITS_UUID": 3000},
                           "Rewards": [{"ItemTypeID": "${ItemTypes.TITLES}", "ItemID": "title-1"}]}}]}}
            """.trimIndent(),
        )
        app.servers.json("valorant-api.com/v1/sprays/spray-1", """{"data":{"displayName":"Nice Spray","fullTransparentIcon":"spray.png"}}""")
        app.servers.on("valorant-api.com/v1/playertitles/") { Reply(500) }

        val fresh = store.refresh().store

        assertEquals(
            listOf(
                AccessoryOffer(CosmeticKind.SPRAY, "spray-1", "Nice Spray", "spray.png", amount = 1, priceKc = 4000),
                AccessoryOffer(CosmeticKind.TITLE, "title-1", "", null, amount = 1, priceKc = 3000),
            ),
            fresh.accessories,
        )
        assertEquals(app.now + 86400 * 1000, fresh.accessoriesExpiresAtMillis)
    }

    @Test
    fun `featured bundles outlive the account for the welcome screen`() = runTest {
        val app = Fixture(tmp.root).apply { signIn() }
        app.store().refresh()

        app.local.clear()

        assertEquals(1, app.device.featuredBundles().size)
    }

    @Test
    fun `the cached wallet is the last one seen, even when the next refresh can't get one`() = runTest {
        val app = Fixture(tmp.root).apply { signIn() }
        val store = app.store()
        store.refresh()
        app.servers.on("/store/v1/wallet/") { Reply(500) }

        store.refresh()

        assertEquals(1000, store.cached()?.wallet?.valorantPoints)
    }

    @Test
    fun `each refresh is reported`() = runTest {
        val app = Fixture(tmp.root).apply { signIn() }
        app.store()
        val reported = mutableListOf<StoreSnapshot>()
        val store = StoreRepository(app.riot, app.content, app.local, app.device, clock = { app.now }, onRefreshed = { reported += it })

        val fresh = store.refresh()

        assertEquals(listOf(fresh), reported)
    }

    @Test
    fun `the store shows without the wallet when Riot won't return it`() = runTest {
        val app = Fixture(tmp.root).apply { signIn() }
        val store = app.store()
        app.servers.on("/store/v1/wallet/") { Reply(500) }

        val fresh = store.refresh()

        assertEquals(1, fresh.store.skins.size)
        assertNull(fresh.wallet)
    }
}
