package app.radianite.data.player

import app.radianite.data.Fixture
import app.radianite.data.Reply
import app.radianite.data.match.MatchHistory
import app.radianite.data.riot.ItemTypes
import app.radianite.data.store.RADIANITE_POINTS_UUID
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
        return PlayerRepository(riot, content, matches = MatchHistory(riot, local), clock = { now })
    }

    @Test
    fun `owned skins are grouped by weapon, rarest first, and searchable by name`() = runTest {
        val app = Fixture(tmp.root).apply { signIn(accessToken = "new") }

        val collection = app.collection().collection()

        assertEquals(listOf("Battle Pass Vandal", "Prime Classic", "Reaver Vandal"), collection.skins.map { it.skin.name })
        assertEquals(listOf("Reaver Vandal", "Battle Pass Vandal"), collection.skinsFor("vandal").map { it.skin.name })
        assertEquals(listOf("Prime Classic"), collection.skinsFor("classic").map { it.skin.name })
        assertEquals(emptyList<OwnedSkin>(), collection.skinsFor("odin"))
        assertEquals(listOf("Battle Pass Vandal", "Reaver Vandal"), collection.search(" vandal ").map { it.skin.name })
        assertEquals(emptyList<OwnedSkin>(), collection.search(" "))
    }

    @Test
    fun `each owned skin says what its locked levels and chromas cost in Radianite`() = runTest {
        val app = Fixture(tmp.root).apply { signIn(accessToken = "new") }
        val players = app.collection()
        app.servers.json("/store/v1/entitlements/puuid-1/${ItemTypes.SKIN_CHROMAS}", """{"Entitlements":[{"ItemID":"reaver-red"}]}""")
        app.servers.json("/store/v1/wallet/", """{"Balances":{"$RADIANITE_POINTS_UUID":20}}""")
        app.servers.json(
            "/contract-definitions/v3/item-upgrades",
            """
            {"Definitions":[
              {"RequiredEntitlement":{"ItemTypeID":"${ItemTypes.SKIN_LEVELS}","ItemID":"REAVER-1"},
               "ProgressionSchedule":{"ProgressionCurrencyID":"$RADIANITE_POINTS_UUID","ProgressionDeltaPerLevel":[10,10]},
               "RewardSchedule":{"RewardsPerLevel":[{"EntitlementRewards":[{"ItemID":"reaver-2"}]},{"EntitlementRewards":[{"ItemID":"reaver-3"}]}]},
               "Sidegrades":[{"Options":[
                 {"Cost":{"WalletCosts":[{"CurrencyID":"$RADIANITE_POINTS_UUID","AmountToDeduct":15}]},"Rewards":[{"ItemID":"reaver-red"}]},
                 {"Cost":{"WalletCosts":[{"CurrencyID":"$RADIANITE_POINTS_UUID","AmountToDeduct":15}]},"Rewards":[{"ItemID":"reaver-blue"}]}]}]},
              {"RequiredEntitlement":{"ItemID":"prime-1"},
               "ProgressionSchedule":{"ProgressionCurrencyID":"00000000-0000-0000-0000-000000000000","ProgressionDeltaPerLevel":null},
               "RewardSchedule":{"RewardsPerLevel":null}},
              {"RequiredEntitlement":{"ItemID":"unknown-1"},
               "ProgressionSchedule":{"ProgressionCurrencyID":"$RADIANITE_POINTS_UUID","ProgressionDeltaPerLevel":[10]},
               "RewardSchedule":{"RewardsPerLevel":[{"EntitlementRewards":[{"ItemID":"unknown-2"}]}]}}
            ]}
            """.trimIndent(),
        )

        val collection = players.collection()

        // Level 3 and the blue chroma are still locked; level 2 and the red chroma are owned.
        assertEquals(10 + 15, collection.skins.single { it.skin.skinUuid == "reaver" }.radianiteToMax)
        assertEquals(0, collection.skins.single { it.skin.skinUuid == "prime" }.radianiteToMax)
        assertEquals(25, collection.radianiteToMax)
        assertEquals(20, collection.radianite)
        assertEquals(listOf("Reaver Vandal"), collection.toUpgrade.map { it.skin.name })

        val reaver = players.skinUpgrades("reaver")!!
        assertTrue(reaver.ownsSkin)
        assertTrue(reaver.isUnlocked("REAVER-2"))
        assertFalse(reaver.isUnlocked("reaver-3"))
        assertEquals(15, reaver.rp("reaver-blue"))
        assertEquals(25, reaver.remainingRp)
        assertNull("nothing to buy with Radianite", players.skinUpgrades("prime"))
        assertEquals("fetched once", 1, app.servers.count("/item-upgrades"))
    }

    @Test
    fun `without the Radianite price list the collection still shows, with no upgrade costs`() = runTest {
        val app = Fixture(tmp.root).apply { signIn(accessToken = "new") }
        val players = app.collection()
        app.servers.json("/store/v1/entitlements/puuid-1/${ItemTypes.SKIN_CHROMAS}", """{"Entitlements":[]}""")
        app.servers.on("/contract-definitions/v3/item-upgrades") { Reply(500) }

        val collection = players.collection()

        assertEquals(3, collection.skins.size)
        assertNull(collection.radianiteToMax)
        assertNull(collection.skins.first().radianiteToMax)
    }
}
