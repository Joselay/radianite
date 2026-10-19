package app.radianite.data.match

import app.radianite.data.Fixture
import app.radianite.data.Reply
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MatchHistoryTest {
    @get:Rule
    val tmp = TemporaryFolder()

    /** Match "m0" is the newest; each started a minute before the one above it. */
    private fun Fixture.history(total: Int, incomplete: Set<String> = emptySet()): MatchHistory {
        servers.on("/match-history/v1/history/") { request ->
            val start = request.url.queryParameter("startIndex")!!.toInt()
            val end = request.url.queryParameter("endIndex")!!.toInt().coerceAtMost(total)
            val entries = (start until end).joinToString(",") { """{"MatchID":"m$it"}""" }
            Reply(body = """{"Total":$total,"History":[$entries]}""")
        }
        servers.on("/match-details/v1/matches/") { request ->
            val id = request.url.pathSegments.last()
            Reply(body = matchDetails(id, startMillis = 10_000_000L - id.drop(1).toLong() * 60_000, complete = id !in incomplete))
        }
        servers.json(
            "/competitiveupdates",
            """{"Matches":[{"MatchID":"m1","MatchStartTime":5,"TierAfterUpdate":16,"RankedRatingEarned":-18}]}""",
        )
        return MatchHistory(riot, local, clock = { now })
    }

    private fun matchDetails(id: String, startMillis: Long, complete: Boolean) = """
        {"matchInfo": {"matchId": "$id", "gameStartMillis": $startMillis, "queueID": "competitive"},
         "players": [{"subject": "puuid-1", "teamId": "Blue", "characterId": "jett"}],
         "teams": ${if (complete) """[{"teamId":"Blue","won":true,"roundsWon":13},{"teamId":"Red","roundsWon":7}]""" else "null"}}
    """.trimIndent()

    @Test
    fun `pages through the history newest first until Riot runs out`() = runTest {
        val app = Fixture(tmp.root).apply { signIn(accessToken = "new") }
        val history = app.history(total = 12)

        val first = history.firstPage()
        assertEquals((0 until 10).map { "m$it" }, first.matches.map { it.record.matchId })
        assertFalse(first.endReached)

        val second = history.nextPage()
        assertEquals((0 until 12).map { "m$it" }, second.matches.map { it.record.matchId })
        assertTrue(second.endReached)
        assertEquals("startIndex=10&endIndex=20", app.servers.requests.last { "/match-history/" in it.url.toString() }.url.query)
    }

    @Test
    fun `a queue filter asks Riot for that queue only, and paging stays within it`() = runTest {
        val app = Fixture(tmp.root).apply { signIn(accessToken = "new") }
        val history = app.history(total = 12)
        history.firstPage()

        history.firstPage(queue = "competitive")
        history.nextPage(queue = "competitive")

        val queries = app.servers.requests.filter { "/match-history/" in it.url.toString() }.map { it.url.query }
        assertEquals(
            listOf("startIndex=0&endIndex=10", "startIndex=0&endIndex=10&queue=competitive", "startIndex=10&endIndex=20&queue=competitive"),
            queries,
        )
    }

    @Test
    fun `switching queue starts the next page from the top`() = runTest {
        val app = Fixture(tmp.root).apply { signIn(accessToken = "new") }
        val history = app.history(total = 12)
        history.firstPage()

        history.nextPage(queue = "unrated")

        assertEquals("startIndex=0&endIndex=10&queue=unrated", app.servers.requests.last { "/match-history/" in it.url.toString() }.url.query)
    }

    @Test
    fun `starting over replaces what was paged`() = runTest {
        val history = Fixture(tmp.root).apply { signIn(accessToken = "new") }.history(total = 25)
        history.firstPage()
        history.nextPage()

        assertEquals(10, history.firstPage().matches.size)
        assertEquals(20, history.nextPage().matches.size)
    }

    @Test
    fun `recent competitive games carry their ranked rating change`() = runTest {
        val history = Fixture(tmp.root).apply { signIn(accessToken = "new") }.history(total = 3)

        val matches = history.firstPage().matches

        assertEquals(RankedRatingChange("m1", 5, 16, -18), matches.single { it.record.matchId == "m1" }.rr)
        assertNull(matches.single { it.record.matchId == "m0" }.rr)
        assertEquals(-18, history.summary("m1")?.rr?.rrEarned)
    }

    @Test
    fun `ranked rating changes are fetched once for screens loading together`() = runTest {
        val app = Fixture(tmp.root).apply { signIn(accessToken = "new") }
        val history = app.history(total = 3)

        history.firstPage()
        history.rankedRatingChanges()
        history.recent(3)

        assertEquals(1, app.servers.count("/competitiveupdates"))
    }

    @Test
    fun `finished matches are kept, while ones still being processed are fetched again`() = runTest {
        val app = Fixture(tmp.root).apply { signIn(accessToken = "new") }
        val history = app.history(total = 2, incomplete = setOf("m1"))

        history.firstPage()
        history.firstPage()
        // A restart reads finished matches from disk.
        val restarted = Fixture(tmp.root).history(total = 2, incomplete = setOf("m1"))
        assertNotNull(restarted.summary("m0"))

        assertEquals(1, app.servers.count("/matches/m0"))
        assertEquals(2, app.servers.count("/matches/m1"))
    }

    @Test
    fun `a match that fails to load is left out`() = runTest {
        val app = Fixture(tmp.root).apply { signIn(accessToken = "new") }
        val history = app.history(total = 3)
        app.servers.on("/match-details/v1/matches/m1") { Reply(500) }

        assertEquals(listOf("m0", "m2"), history.firstPage().matches.map { it.record.matchId })
    }
}
