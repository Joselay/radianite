package app.radianite.data.content

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GameContentTest {
    private val content = buildGameContent(
        version = "release-13.08",
        agents = listOf(ApiAgent("JETT-UUID", "Jett", "jett.png", role = ApiAgentRole("Duelist"), isBaseContent = true)),
        maps = listOf(
            ApiMap("ascent-uuid", "Ascent", "/Game/Maps/Ascent/Ascent", "splash.png", "list.png"),
            ApiMap("menu-uuid", "Menu", mapUrl = null),
        ),
        tierTables = listOf(
            ApiTierTable("old", listOf(ApiTier(3, "OLD IRON 1"))),
            ApiTierTable("new", listOf(ApiTier(0, "UNRANKED"), ApiTier(1, "Unused1"), ApiTier(3, "IRON 1", "868986ff", "iron1.png"))),
        ),
        seasons = listOf(
            ApiSeason("EP-UUID", "V26"),
            ApiSeason("ACT-UUID", "ACT VI", "EAresSeasonType::Act", "2026-10-14T00:00:00Z", "2027-01-06T00:00:00Z", "ep-uuid"),
            ApiSeason("undated", "ACT ?", "EAresSeasonType::Act"),
        ),
        queues = listOf(ApiQueue("competitive", "Competitive"), ApiQueue("console_competitive", "Competitive")),
        weapons = listOf(ApiWeapon("VANDAL", "Vandal", "EEquippableCategory::Rifle")),
        titles = listOf(ApiTitle("T1", "Tiger"), ApiTitle("t2", null)),
        contracts = listOf(
            ApiContract("PASS", "Season 2026 // Act VI", ApiContractContent("Season", "ACT-UUID", listOf(ApiContractChapter(listOf(ApiContractLevel(0), ApiContractLevel(2000)))))),
            ApiContract("agent", "Jett Contract", ApiContractContent("Agent", "jett-uuid")),
        ),
    )

    @Test
    fun `indexes content by the IDs the game reports`() {
        assertEquals("Jett", content.agent("jett-uuid")?.name)
        assertEquals(true, content.agent("JETT-UUID")?.isBaseContent)
        assertEquals("Ascent", content.map("/game/maps/ascent/ascent")?.name)
        assertEquals(1, content.maps.size)
        assertEquals("Vandal", content.weapons["vandal"]?.name)
        assertEquals("Rifle", content.weapons["vandal"]?.category)
        assertEquals(mapOf("t1" to "Tiger"), content.titles)
    }

    @Test
    fun `uses the newest rank table and drops unused tiers`() {
        assertEquals("IRON 1", content.rank(3)?.name)
        assertEquals("UNRANKED", content.rank(0)?.name)
        assertNull(content.rank(1))
    }

    @Test
    fun `names acts with their episode and finds the current one`() {
        val act = content.acts.single()
        assertEquals("act-uuid", act.uuid)
        assertEquals("V26 · ACT VI", act.name)
        assertEquals(act, content.currentAct(act.startMillis + 1))
        assertNull(content.currentAct(act.endMillis))
    }

    @Test
    fun `labels queues, falling back for unknown and custom`() {
        assertEquals("Competitive", content.queueName("competitive"))
        assertEquals(1, content.queues.size)
        assertEquals("Newmode", content.queueName("newmode"))
        assertEquals("Custom", content.queueName(""))
    }

    @Test
    fun `keeps only season battle passes`() {
        assertEquals(setOf("pass"), content.battlePasses.keys)
        assertEquals("act-uuid", content.battlePasses["pass"]?.actUuid)
        assertEquals(listOf(0, 2000), content.battlePasses["pass"]?.levelXp)
    }
}
