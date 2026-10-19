package app.radianite.data.player

import app.radianite.data.Fixture
import app.radianite.data.Reply
import app.radianite.data.match.MatchHistory
import app.radianite.data.riot.ItemTypes
import app.radianite.data.store.VALORANT_POINTS_UUID
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CollectionTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun Fixture.collection(): PlayerRepository {
        servers.json(
            "valorant-api.com/v1/weapons",
            """
            {"data":[
              {"uuid":"VANDAL","displayName":"Vandal","category":"EEquippableCategory::Rifle","skins":[
                {"uuid":"reaver","displayName":"Reaver Vandal","contentTierUuid":"premium","levels":[{"uuid":"reaver-1"},{"uuid":"reaver-2"}]},
                {"uuid":"pass","displayName":"Battle Pass Vandal","levels":[{"uuid":"pass-1"}]}]},
              {"uuid":"CLASSIC","displayName":"Classic","category":"EEquippableCategory::Sidearm","skins":[
                {"uuid":"prime","displayName":"Prime Classic","contentTierUuid":"premium","levels":[{"uuid":"prime-1"}]}]}
            ]}
            """.trimIndent(),
        )
        servers.json("valorant-api.com/v1/contenttiers", """{"data":[{"uuid":"premium","devName":"Premium","highlightColor":"d1548dff","rank":2}]}""")
        servers.json("/personalization/v3/players/", """{"Guns":[]}""")
        servers.json(
            "/store/v1/entitlements/puuid-1/${ItemTypes.SKIN_LEVELS}",
            """{"Entitlements":[{"ItemID":"reaver-1"},{"ItemID":"reaver-2"},{"ItemID":"pass-1"},{"ItemID":"prime-1"}]}""",
        )
        servers.json(
            "/store/v1/offers/",
            """
            {"Offers":[
              {"OfferID":"o1","Cost":{"$VALORANT_POINTS_UUID":1775},"Rewards":[{"ItemTypeID":"x","ItemID":"reaver-1"}]},
              {"OfferID":"o2","Cost":{"$VALORANT_POINTS_UUID":1275},"Rewards":[{"ItemTypeID":"x","ItemID":"prime-1"}]}
            ]}
            """.trimIndent(),
        )
        return PlayerRepository(riot, content, matches = MatchHistory(riot, local), clock = { now })
    }

    @Test
    fun `owned skins carry their store price, weapon and rarity, and add up to the collection's value`() = runTest {
        val app = Fixture(tmp.root).apply { signIn(accessToken = "new") }

        val collection = app.collection().collection()

        assertEquals(listOf("Battle Pass Vandal", "Prime Classic", "Reaver Vandal"), collection.skins.map { it.skin.name })
        assertEquals(1775, collection.skins.single { it.skin.skinUuid == "reaver" }.skin.priceVp)
        assertNull(collection.skins.single { it.skin.skinUuid == "pass" }.skin.priceVp)
        assertEquals(1775 + 1275, collection.valueVp)
        assertEquals(listOf(WeaponFilter("classic", "Classic"), WeaponFilter("vandal", "Vandal")), collection.weapons)
        assertEquals(2, collection.skins.single { it.skin.skinUuid == "prime" }.tierRank)
    }

    @Test
    fun `without store prices the collection still shows, with no value`() = runTest {
        val app = Fixture(tmp.root).apply { signIn(accessToken = "new") }
        val players = app.collection()
        app.servers.on("/store/v1/offers/") { Reply(500) }

        val collection = players.collection()

        assertEquals(3, collection.skins.size)
        assertNull(collection.valueVp)
    }
}
