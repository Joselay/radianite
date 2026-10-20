package app.radianite.data.comps

import app.radianite.data.FakeServers
import app.radianite.data.LocalStore
import app.radianite.data.Reply
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TeamCompsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val servers = FakeServers()

    private fun guide(updated: String, map: String = "Lotus") = """
        {"updated": "$updated", "rankFilter": "Radiant", "sourceName": "vstats.gg", "sourceUrl": "https://vstats.gg",
         "maps": [
          {"map": "$map", "inPool": true, "period": "Act VI so far, patch 13.08", "thisAct": true,
           "best": {"agents": ["Clove", "Fade", "Neon", "Raze", "Vyse"], "matches": 146, "winPercent": 69.9}}
        ]}
    """.trimIndent()

    private fun comps(bundled: String = guide("2026-10-20")) =
        TeamComps(servers.client, json, LocalStore(tmp.root, json), bundled = { bundled }, url = "https://raw.example/team_comps.json")

    @Test
    fun `a newer guide on GitHub replaces the shipped one, and is kept for offline`() = runBlocking {
        servers.json("team_comps.json", guide("2026-11-02", map = "Bind"))
        assertEquals("Bind", comps().refreshed().maps.single().map)

        servers.on("team_comps.json") { Reply(503) }
        assertEquals("2026-11-02", comps().refreshed().updated)
        assertEquals("2026-11-02", comps().local().updated)
    }

    @Test
    fun `an app update that ships a newer guide wins over an older download`() = runBlocking {
        servers.json("team_comps.json", guide("2026-11-02"))
        comps().refreshed()

        servers.on("team_comps.json") { Reply(503) }
        assertEquals("2026-12-01", comps(bundled = guide("2026-12-01")).refreshed().updated)
    }

    @Test
    fun `offline with nothing downloaded shows the shipped guide`() = runBlocking {
        assertEquals(146, comps().refreshed().maps.single().best.matches)
    }

    @Test
    fun `the guide the app ships parses, with five different agents for every map and the ranked pool in it`() = runBlocking {
        val shipped = File("src/main/assets/team_comps.json").readText()
        val guide = comps(bundled = shipped).local()
        guide.maps.forEach { assertEquals(it.map, 5, it.best.agents.toSet().size) }
        assertEquals(7, guide.maps.count { it.inPool })
    }
}
