package app.radianite.data.match

import app.radianite.data.riot.MatchDetailsResponse
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MatchRecordTest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    /** Two rounds of a 1v1-per-team match: "me" and "ally" on Blue, "enemy" on Red. */
    private val teamMatch = """
        {
          "matchInfo": {"matchId": "m1", "mapId": "/Game/Maps/Ascent/Ascent", "gameLengthMillis": 1800000,
                        "gameStartMillis": 1000, "queueID": "competitive", "isRanked": true,
                        "seasonId": "ACT-1", "completionState": "Completed", "unknownField": 1},
          "players": [
            {"subject": "me", "gameName": "Me", "tagLine": "1", "teamId": "Blue", "characterId": "AGENT-JETT",
             "stats": {"score": 500, "roundsPlayed": 2, "kills": 3, "deaths": 1, "assists": 1}, "competitiveTier": 18},
            {"subject": "ally", "gameName": "Ally", "tagLine": "2", "teamId": "Blue", "characterId": "agent-sage",
             "stats": {"score": 200, "roundsPlayed": 2, "kills": 0, "deaths": 1, "assists": 2}},
            {"subject": "enemy", "gameName": "Enemy", "tagLine": "3", "teamId": "Red", "characterId": "agent-sova",
             "stats": {"score": 300, "roundsPlayed": 2, "kills": 2, "deaths": 3, "assists": 0}},
            {"subject": "caster", "teamId": "Neutral", "isObserver": true}
          ],
          "teams": [
            {"teamId": "Blue", "won": true, "roundsPlayed": 2, "roundsWon": 2, "numPoints": 2},
            {"teamId": "Red", "won": false, "roundsPlayed": 2, "roundsWon": 0, "numPoints": 0}
          ],
          "roundResults": [
            {"roundNum": 1, "winningTeam": "Blue", "roundResultCode": "Defuse", "playerStats": [
              {"subject": "me", "damage": [
                {"receiver": "enemy", "damage": 150, "headshots": 1, "bodyshots": 2, "legshots": 1},
                {"receiver": "ally", "damage": 40, "headshots": 0, "bodyshots": 1, "legshots": 0},
                {"receiver": "me", "damage": 30, "headshots": 0, "bodyshots": 0, "legshots": 0}
              ]}
            ]},
            {"roundNum": 0, "winningTeam": "Blue", "roundResultCode": "Elimination", "playerStats": [
              {"subject": "me", "damage": [{"receiver": "enemy", "damage": 50, "headshots": 1, "bodyshots": 0, "legshots": 0}]}
            ]}
          ],
          "kills": [
            {"round": 0, "roundTime": 9000, "killer": "enemy", "victim": "ally"},
            {"round": 0, "roundTime": 4000, "killer": "me", "victim": "enemy"},
            {"round": 1, "roundTime": 2000, "killer": "enemy", "victim": "me"}
          ]
        }
    """.trimIndent()

    private fun record(body: String = teamMatch) = json.decodeFromString<MatchDetailsResponse>(body).toRecord()

    @Test
    fun `reduces match details to per-player stats`() {
        val record = record()
        assertEquals("m1", record.matchId)
        assertEquals("act-1", record.seasonId)
        assertEquals(3, record.players.size)
        assertTrue("observers are dropped", record.player("caster") == null)
        assertEquals(listOf(0, 1), record.rounds.map { it.number })

        val me = record.player("me")!!
        assertEquals("agent-jett", me.agentId)
        assertEquals(250, me.acs)
        // Team and self damage don't count: 150 + 50 over 2 rounds.
        assertEquals(200, me.damage)
        assertEquals(100, me.adr)
        // 2 headshots out of 2 + 2 + 1 enemy hits.
        assertEquals(40, me.headshotPercent)
        assertEquals(1, me.firstKills)
        assertEquals(1, me.firstDeaths)

        assertNull("no recorded shots", record.player("ally")!!.headshotPercent)
        assertEquals(1, record.player("enemy")!!.firstKills)
    }

    @Test
    fun `summarizes a team match from the player's side`() {
        val record = record()
        val mine = record.summaryFor("me")!!
        assertEquals(Outcome.WIN, mine.outcome)
        assertEquals("2–0", mine.scoreLine)

        val theirs = record.summaryFor("enemy")!!
        assertEquals(Outcome.LOSS, theirs.outcome)
        assertEquals("0–2", theirs.scoreLine)

        assertNull(record.summaryFor("stranger"))
    }

    @Test
    fun `a match nobody won is a draw`() {
        val drawn = record(teamMatch.replace("\"won\": true", "\"won\": false"))
        assertEquals(Outcome.DRAW, drawn.summaryFor("me")!!.outcome)
    }

    @Test
    fun `free-for-all shows placement by points`() {
        val ffa = """
            {
              "matchInfo": {"matchId": "dm", "queueID": "deathmatch"},
              "players": [
                {"subject": "a", "teamId": "a", "stats": {"score": 10, "roundsPlayed": 1, "kills": 30}},
                {"subject": "b", "teamId": "b", "stats": {"score": 10, "roundsPlayed": 1, "kills": 40}},
                {"subject": "c", "teamId": "c", "stats": {"score": 10, "roundsPlayed": 1, "kills": 10}}
              ],
              "teams": [
                {"teamId": "a", "won": false, "numPoints": 30},
                {"teamId": "b", "won": true, "numPoints": 40},
                {"teamId": "c", "won": false, "numPoints": 10}
              ]
            }
        """.trimIndent()
        val record = record(ffa)
        assertEquals(false, record.isTeamMode)
        assertEquals("#2", record.summaryFor("a")!!.scoreLine)
        assertEquals(Outcome.LOSS, record.summaryFor("a")!!.outcome)
        assertEquals("#1", record.summaryFor("b")!!.scoreLine)
    }

    @Test
    fun `career stats aggregate team matches and skip free-for-all`() {
        val win = record().summaryFor("me")!!
        val loss = record(teamMatch.replace("\"matchId\": \"m1\"", "\"matchId\": \"m2\"").replace("/Game/Maps/Ascent/Ascent", "/Game/Maps/Bonsai/Bonsai"))
            .let { it.copy(teams = it.teams.map { t -> t.copy(won = t.teamId == "Red") }) }
            .summaryFor("me")!!
        val ffa = win.copy(record = win.record.copy(teams = win.record.teams + TeamRecord("x", false, 0, 0, 0)))

        val stats = careerStats(listOf(win, loss, ffa))
        assertEquals(2, stats.matches)
        assertEquals(1, stats.wins)
        assertEquals(50, stats.winPercent)
        assertEquals(6, stats.kills)
        assertEquals(3.0, stats.kd, 0.001)
        assertEquals(250, stats.acs)
        assertEquals(100, stats.adr)
        assertEquals(40, stats.headshotPercent)

        val jett = stats.agents.single()
        assertEquals("agent-jett", jett.agentId)
        assertEquals(2, jett.matches)
        assertEquals(50, jett.winPercent)

        assertEquals(setOf("/game/maps/ascent/ascent", "/game/maps/bonsai/bonsai"), stats.maps.map { it.mapId }.toSet())
    }

    @Test
    fun `the scoreboard lists your team first, each side by score`() {
        val boards = record().summaryFor("enemy")!!.scoreboard()

        assertEquals(listOf("Red", "Blue"), boards.map { it.teamId })
        assertEquals(listOf(true, false), boards.map { it.isMine })
        assertEquals(listOf("me", "ally"), boards[1].players.map { it.puuid })
        assertEquals(2, boards[1].team?.roundsWon)
    }

    @Test
    fun `sides swap at half time, sooner in short modes`() {
        assertEquals(12, record().halfRounds)
        assertEquals(4, record().copy(queueId = "swiftplay").halfRounds)
        assertEquals(3, record().copy(queueId = "spikerush").halfRounds)
    }

    @Test
    fun `players Riot left unnamed go by their agent`() {
        val me = record().player("me")!!
        val nameless = me.copy(gameName = "")

        assertEquals("Me", me.displayName(isMe = true, agentName = "Jett"))
        assertEquals("You", nameless.displayName(isMe = true, agentName = "Jett"))
        assertEquals("Jett", nameless.displayName(isMe = false, agentName = "Jett"))
        assertEquals("Player", nameless.displayName(isMe = false, agentName = null))
    }

    @Test
    fun `the RR trend reads oldest first and skips games without a time`() {
        val trend = rrTrend(listOf(RankedRatingChange("b", 20, 16, 18), RankedRatingChange("a", 10, 15, -12), RankedRatingChange("x", 0, 15, 5)))

        assertEquals(listOf("a", "b"), trend.map { it.matchId })
    }
}
