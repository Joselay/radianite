package app.radianite.data.player

import app.radianite.data.content.ActInfo
import app.radianite.data.content.BattlePassInfo
import app.radianite.data.content.GameContent
import app.radianite.data.riot.ContractsResponse
import app.radianite.data.riot.LeaderboardPlayer
import app.radianite.data.riot.PlayerMmrResponse
import app.radianite.data.store.StoreSkin
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CareerTest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    private val content = GameContent(
        version = "v",
        agents = emptyMap(),
        maps = emptyMap(),
        ranks = emptyMap(),
        acts = listOf(
            ActInfo("act-old", "V25 · ACT VI", startMillis = 0, endMillis = 100),
            ActInfo("act-now", "V26 · ACT I", startMillis = 100, endMillis = 200),
        ),
        queues = emptyMap(),
        weapons = emptyMap(),
        titles = emptyMap(),
        battlePasses = mapOf(
            "pass-old" to BattlePassInfo("Old pass", "act-old", listOf(0, 2000, 2750)),
            "pass-now" to BattlePassInfo("New pass", "act-now", listOf(0, 2000, 2750, 3500)),
        ),
    )

    private val mmr = json.decodeFromString<PlayerMmrResponse>(
        """
        {
          "QueueSkills": {
            "competitive": {
              "CurrentSeasonGamesNeededForRating": 0,
              "SeasonalInfoBySeasonID": {
                "ACT-NOW": {"SeasonID": "ACT-NOW", "NumberOfWins": 6, "NumberOfGames": 10, "CompetitiveTier": 15,
                            "RankedRating": 10, "LeaderboardRank": 0, "WinsByTier": {"14": 2, "16": 1, "15": 3}},
                "act-old": {"SeasonID": "act-old", "NumberOfWins": 20, "NumberOfGames": 35, "CompetitiveTier": 14,
                            "WinsByTier": {"12": 5, "14": 15}},
                "act-unknown": {"SeasonID": "act-unknown", "NumberOfWins": 1, "NumberOfGames": 1}
              }
            },
            "unrated": {"SeasonalInfoBySeasonID": null}
          },
          "LatestCompetitiveUpdate": {"MatchID": "m9", "SeasonID": "act-now", "TierAfterUpdate": 16, "RankedRatingAfterUpdate": 42}
        }
        """.trimIndent(),
    )

    @Test
    fun `rank comes from the latest ranked game, act stats from the current act`() {
        val rank = rankStatus(mmr, content, nowMillis = 150)!!
        assertEquals(16, rank.tier)
        assertEquals(42, rank.rankedRating)
        assertEquals("V26 · ACT I", rank.actName)
        assertEquals(6, rank.wins)
        assertEquals(60, rank.winPercent)
        assertEquals(16, rank.peakTier)
    }

    @Test
    fun `rank falls back to act info before any ranked update`() {
        val fresh = mmr.copy(latestCompetitiveUpdate = mmr.latestCompetitiveUpdate!!.copy(matchId = ""))
        val rank = rankStatus(fresh, content, nowMillis = 150)!!
        assertEquals(15, rank.tier)
        assertEquals(10, rank.rankedRating)
    }

    @Test
    fun `no competitive data means no rank`() {
        assertNull(rankStatus(PlayerMmrResponse(), content, nowMillis = 150))
    }

    @Test
    fun `act history lists known acts newest first with their peak`() {
        val history = actHistory(mmr, content)
        assertEquals(listOf("act-now", "act-old"), history.map { it.actUuid })
        assertEquals(14, history[1].peakTier)
        assertEquals(35, history[1].games)
    }

    @Test
    fun `battle pass progress uses the current act's pass`() {
        val contracts = json.decodeFromString<ContractsResponse>(
            """
            {"Contracts": [
              {"ContractDefinitionID": "PASS-OLD", "ProgressionLevelReached": 3, "ProgressionTowardsNextLevel": 0},
              {"ContractDefinitionID": "pass-now", "ProgressionLevelReached": 2, "ProgressionTowardsNextLevel": 1200},
              {"ContractDefinitionID": "agent-contract", "ProgressionLevelReached": 5}
            ]}
            """.trimIndent(),
        )
        val pass = battlePass(contracts, content, nowMillis = 150)!!
        assertEquals("New pass", pass.name)
        assertEquals(2, pass.level)
        assertEquals(4, pass.totalLevels)
        assertEquals(1200, pass.xpIntoLevel)
        assertEquals(2750, pass.xpForLevel)

        // Before the current act's pass is touched, show the newest one the player has.
        val oldOnly = ContractsResponse(contracts.contracts.take(1))
        assertEquals("Old pass", battlePass(oldOnly, content, nowMillis = 150)!!.name)
        assertNull(battlePass(ContractsResponse(), content, nowMillis = 150))
    }

    @Test
    fun `ranked rating is capped below Immortal`() {
        val diamond = rankStatus(mmr, content, nowMillis = 150)!!
        assertTrue(diamond.rrCapped)
        assertFalse(diamond.copy(tier = IMMORTAL_1).rrCapped)
    }

    @Test
    fun `hidden leaderboard players show as Secret Agent`() {
        val named = LeaderboardPlayer(gameName = "TenZ", tagLine = "0505", leaderboardRank = 1, competitiveTier = 27)
        assertEquals("TenZ#0505", named.toEntry().name)
        assertEquals("Secret Agent", named.copy(isAnonymized = true).toEntry().name)
        assertEquals(27, named.toEntry().tier)
    }

    @Test
    fun `collection search matches skin names loosely`() {
        val collection = collectionOf(owned("Reaver Vandal"), owned("Prime Vandal"), owned("Glitchpop Phantom"))

        assertEquals(listOf("Prime Vandal", "Reaver Vandal"), collection.search(" vandal ").map { it.name })
        assertEquals(3, collection.search("").size)
    }

    @Test
    fun `collection filters by weapon and sorts by rarity or price`() {
        val collection = collectionOf(
            owned("Reaver Vandal", weapon = "vandal", rank = 2, price = 1775),
            owned("Elderflame Vandal", weapon = "vandal", rank = 4, price = 2475),
            owned("Prime Phantom", weapon = "phantom", rank = 2, price = 1775),
            owned("Battle Pass Vandal", weapon = "vandal", rank = null, price = null),
        )

        assertEquals(
            listOf("Battle Pass Vandal", "Elderflame Vandal", "Reaver Vandal"),
            collection.search("", weaponId = "vandal").map { it.name },
        )
        assertEquals(
            listOf("Elderflame Vandal", "Prime Phantom", "Reaver Vandal", "Battle Pass Vandal"),
            collection.search("", sort = SkinSort.RARITY).map { it.name },
        )
        assertEquals(
            listOf("Elderflame Vandal", "Prime Phantom", "Reaver Vandal", "Battle Pass Vandal"),
            collection.search("", sort = SkinSort.PRICE).map { it.name },
        )
    }

    private fun owned(name: String, weapon: String? = null, rank: Int? = null, price: Int? = null) = OwnedSkin(
        StoreSkin(name.lowercase(), name = name, iconUrl = null, priceVp = price, tierName = null, tierColor = null, tierIconUrl = null),
        weaponId = weapon,
        tierRank = rank,
    )

    private fun collectionOf(vararg skins: OwnedSkin) =
        PlayerCollection(emptyList(), skins.sortedBy { it.skin.name }, ownedAgents = null, totalAgents = 0, valueVp = null, weapons = emptyList())
}
