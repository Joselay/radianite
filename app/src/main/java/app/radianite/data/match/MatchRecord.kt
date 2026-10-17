package app.radianite.data.match

import app.radianite.data.riot.MatchDetailsResponse
import kotlinx.serialization.Serializable

/**
 * The parts of a finished match the app shows, reduced from the full match-details payload (which carries
 * every kill's player positions and runs to hundreds of KB). Account-agnostic: [summaryFor] picks a viewpoint.
 */
@Serializable
data class MatchRecord(
    val matchId: String,
    val startMillis: Long,
    val lengthMillis: Long?,
    val mapId: String,
    val queueId: String,
    val seasonId: String,
    val isRanked: Boolean,
    val completionState: String,
    val teams: List<TeamRecord>,
    val players: List<PlayerRecord>,
    val rounds: List<RoundRecord>,
) {
    /** Deathmatch-style modes put every player on their own team. */
    val isTeamMode: Boolean get() = teams.size == 2

    /** Rounds before sides swap. */
    val halfRounds: Int
        get() = when (queueId) {
            "swiftplay" -> 4
            "spikerush" -> 3
            else -> 12
        }

    fun player(puuid: String): PlayerRecord? = players.find { it.puuid == puuid }
}

@Serializable
data class TeamRecord(val teamId: String, val won: Boolean, val roundsWon: Int, val roundsPlayed: Int, val points: Int)

@Serializable
data class PlayerRecord(
    val puuid: String,
    val gameName: String,
    val tagLine: String,
    val teamId: String,
    val partyId: String,
    val agentId: String,
    val competitiveTier: Int,
    val cardId: String,
    val accountLevel: Int,
    val score: Int,
    val roundsPlayed: Int,
    val kills: Int,
    val deaths: Int,
    val assists: Int,
    /** Damage dealt to enemies across all rounds. */
    val damage: Int,
    val headshots: Int,
    val bodyshots: Int,
    val legshots: Int,
    val firstKills: Int,
    val firstDeaths: Int,
) {
    val acs: Int get() = if (roundsPlayed > 0) score / roundsPlayed else 0
    val adr: Int get() = if (roundsPlayed > 0) damage / roundsPlayed else 0
    val kd: Double get() = kills.toDouble() / deaths.coerceAtLeast(1)

    /** Percent of landed shots that were headshots, or null when no shots were recorded. */
    val headshotPercent: Int?
        get() {
            val shots = headshots + bodyshots + legshots
            return if (shots > 0) headshots * 100 / shots else null
        }

    /** Riot leaves names out of match details now. */
    val hasName: Boolean get() = gameName.isNotBlank()

    /** Nameless rows go by agent, as the game's own history does. */
    fun displayName(isMe: Boolean, agentName: String?): String = when {
        hasName -> gameName
        isMe -> "You"
        else -> agentName ?: "Player"
    }
}

/** [resultCode] is Elimination, Detonate, Defuse, Surrendered, or "" when the timer ran out. */
@Serializable
data class RoundRecord(val number: Int, val winningTeam: String, val resultCode: String, val ceremony: String)

fun MatchDetailsResponse.toRecord(): MatchRecord {
    val active = players.filterNot { it.isObserver }
    val teamOf = active.associate { it.subject to it.teamId }
    val rounds = roundResults.orEmpty()

    data class Shots(var damage: Int = 0, var head: Int = 0, var body: Int = 0, var leg: Int = 0)
    val shots = HashMap<String, Shots>()
    for (round in rounds) {
        for (stats in round.playerStats) {
            val team = teamOf[stats.subject]
            val tally = shots.getOrPut(stats.subject) { Shots() }
            for (hit in stats.damage) {
                // Self- and team damage don't count toward ADR.
                if (hit.receiver == stats.subject || (team != null && teamOf[hit.receiver] == team)) continue
                tally.damage += hit.damage
                tally.head += hit.headshots
                tally.body += hit.bodyshots
                tally.leg += hit.legshots
            }
        }
    }

    val firstKills = HashMap<String, Int>()
    val firstDeaths = HashMap<String, Int>()
    kills.orEmpty().groupBy { it.round }.values.forEach { roundKills ->
        val first = roundKills.minBy { it.roundTime }
        firstKills.merge(first.killer, 1, Int::plus)
        firstDeaths.merge(first.victim, 1, Int::plus)
    }

    return MatchRecord(
        matchId = matchInfo.matchId,
        startMillis = matchInfo.gameStartMillis,
        lengthMillis = matchInfo.gameLengthMillis,
        mapId = matchInfo.mapId,
        queueId = matchInfo.queueID,
        seasonId = matchInfo.seasonId.lowercase(),
        isRanked = matchInfo.isRanked,
        completionState = matchInfo.completionState,
        teams = teams.orEmpty().map { TeamRecord(it.teamId, it.won, it.roundsWon, it.roundsPlayed, it.numPoints) },
        players = active.map { p ->
            val tally = shots[p.subject] ?: Shots()
            val stats = p.stats
            PlayerRecord(
                puuid = p.subject,
                gameName = p.gameName,
                tagLine = p.tagLine,
                teamId = p.teamId,
                partyId = p.partyId,
                agentId = p.characterId.lowercase(),
                competitiveTier = p.competitiveTier,
                cardId = p.playerCard,
                accountLevel = p.accountLevel,
                score = stats?.score ?: 0,
                roundsPlayed = stats?.roundsPlayed ?: 0,
                kills = stats?.kills ?: 0,
                deaths = stats?.deaths ?: 0,
                assists = stats?.assists ?: 0,
                damage = tally.damage,
                headshots = tally.head,
                bodyshots = tally.body,
                legshots = tally.leg,
                firstKills = firstKills[p.subject] ?: 0,
                firstDeaths = firstDeaths[p.subject] ?: 0,
            )
        },
        rounds = rounds.sortedBy { it.roundNum }.map { RoundRecord(it.roundNum, it.winningTeam, it.roundResultCode, it.roundCeremony) },
    )
}

enum class Outcome { WIN, LOSS, DRAW }

/** One match from one player's point of view. */
data class MatchSummary(
    val record: MatchRecord,
    val me: PlayerRecord,
    val outcome: Outcome,
    /** "13–9" for team modes; "#3" placement for free-for-all. */
    val scoreLine: String,
    /** How the game moved the player's rank, for recent competitive games. */
    val rr: RankedRatingChange? = null,
) {
    val myTeamId: String? get() = record.teams.find { it.teamId == me.teamId }?.teamId

    /** Your team first; free-for-all lists everyone together. Players are ordered by score. */
    fun scoreboard(): List<TeamBoard> =
        if (record.isTeamMode) {
            record.players.groupBy { it.teamId }.entries
                .sortedByDescending { it.key == myTeamId }
                .map { (teamId, players) ->
                    TeamBoard(teamId, record.teams.find { it.teamId == teamId }, isMine = teamId == myTeamId, players.sortedByDescending { it.score })
                }
        } else {
            listOf(TeamBoard("", team = null, isMine = false, record.players.sortedByDescending { it.score }))
        }
}

/** One side of the scoreboard; [team] is null for free-for-all. */
data class TeamBoard(val teamId: String, val team: TeamRecord?, val isMine: Boolean, val players: List<PlayerRecord>)

fun MatchRecord.summaryFor(puuid: String): MatchSummary? {
    val me = player(puuid) ?: return null
    val mine = teams.find { it.teamId == me.teamId }
    val others = teams.filter { it.teamId != me.teamId }
    val outcome = when {
        mine?.won == true -> Outcome.WIN
        others.any { it.won } -> Outcome.LOSS
        // Nobody won: a draw vote, or a match that ended without a result.
        else -> Outcome.DRAW
    }
    val scoreLine = when {
        mine == null -> ""
        isTeamMode -> "${mine.roundsWon}–${others.first().roundsWon}"
        else -> "#" + (teams.sortedByDescending { it.points }.indexOfFirst { it.teamId == me.teamId } + 1)
    }
    return MatchSummary(this, me, outcome, scoreLine)
}

/** Totals across a set of matches, for the career screen. */
data class CareerStats(
    val matches: Int,
    val wins: Int,
    val kills: Int,
    val deaths: Int,
    val assists: Int,
    val headshotPercent: Int?,
    val acs: Int,
    val adr: Int,
    val agents: List<AgentStat>,
    val maps: List<MapStat>,
) {
    val winPercent: Int get() = if (matches > 0) wins * 100 / matches else 0
    val kd: Double get() = kills.toDouble() / deaths.coerceAtLeast(1)

    /** (Kills + assists) per death. */
    val kda: Double get() = (kills + assists).toDouble() / deaths.coerceAtLeast(1)
}

data class AgentStat(val agentId: String, val matches: Int, val wins: Int, val kd: Double, val acs: Int) {
    val winPercent: Int get() = if (matches > 0) wins * 100 / matches else 0
}

data class MapStat(val mapId: String, val matches: Int, val wins: Int) {
    val winPercent: Int get() = if (matches > 0) wins * 100 / matches else 0
}

/** Aggregates team-mode matches only; deathmatch scores and rounds don't compare with the rest. */
fun careerStats(summaries: List<MatchSummary>): CareerStats {
    val team = summaries.filter { it.record.isTeamMode }
    val players = team.map { it.me }
    val rounds = players.sumOf { it.roundsPlayed }
    val shots = players.sumOf { it.headshots + it.bodyshots + it.legshots }
    return CareerStats(
        matches = team.size,
        wins = team.count { it.outcome == Outcome.WIN },
        kills = players.sumOf { it.kills },
        deaths = players.sumOf { it.deaths },
        assists = players.sumOf { it.assists },
        headshotPercent = if (shots > 0) players.sumOf { it.headshots } * 100 / shots else null,
        acs = if (rounds > 0) players.sumOf { it.score } / rounds else 0,
        adr = if (rounds > 0) players.sumOf { it.damage } / rounds else 0,
        agents = team.groupBy { it.me.agentId }.map { (agentId, games) ->
            val agentRounds = games.sumOf { it.me.roundsPlayed }
            AgentStat(
                agentId = agentId,
                matches = games.size,
                wins = games.count { it.outcome == Outcome.WIN },
                kd = games.sumOf { it.me.kills }.toDouble() / games.sumOf { it.me.deaths }.coerceAtLeast(1),
                acs = if (agentRounds > 0) games.sumOf { it.me.score } / agentRounds else 0,
            )
        }.sortedWith(compareByDescending<AgentStat> { it.matches }.thenByDescending { it.wins }),
        maps = team.groupBy { it.record.mapId.lowercase() }.map { (mapId, games) ->
            MapStat(mapId, games.size, games.count { it.outcome == Outcome.WIN })
        }.sortedByDescending { it.matches },
    )
}
