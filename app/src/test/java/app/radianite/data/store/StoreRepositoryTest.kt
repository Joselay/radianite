package app.radianite.data.store

import app.radianite.data.Fixture
import app.radianite.data.Reply
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
            "valorant-api.com/v1/weapons/skins",
            """{"data":[{"uuid":"reaver","displayName":"Reaver Vandal","levels":[{"uuid":"level-1"}]}]}""",
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
        return StoreRepository(riot, content, local, clock = { now })
    }

    @Test
    fun `a refresh names the offers, attaches the wallet and is cached until the store rotates`() = runTest {
        val app = Fixture(tmp.root).apply { signIn() }
        val store = app.store()

        val fresh = store.refresh()

        assertEquals("Reaver Vandal", fresh.store.skins.single().name)
        assertEquals(1000, fresh.wallet?.valorantPoints)
        assertEquals(fresh.store, store.cached()?.store)
        assertEquals(listOf(FeaturedBundle("Reaver 2.0", "reaver.png")), store.featuredBundles())

        app.now += 3600 * 1000
        assertNull("rotated", store.cached())
    }

    @Test
    fun `another account never sees the cached store`() = runTest {
        val app = Fixture(tmp.root).apply { signIn() }
        app.store().refresh()

        app.signIn(puuid = "someone-else")
        val other = Fixture(tmp.root).store()

        assertNull(other.cached())
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
