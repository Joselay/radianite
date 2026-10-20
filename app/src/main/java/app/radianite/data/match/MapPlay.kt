package app.radianite.data.match

import app.radianite.data.player.COMPETITIVE_QUEUE

/** How a map has gone in the player's competitive games: their record, and the agents they played there. */
data class MapPlay(
    /** Lowercase map path, as matches report it. */
    val mapId: String,
    val matches: Int,
    val wins: Int,
    /** The player's own agents here, most played first. */
    val mine: List<AgentResult>,
) {
    val winPercent: Int get() = percent(wins, matches)
}

data class AgentResult(val agentId: String, val picks: Int, val wins: Int) {
    val winPercent: Int get() = percent(wins, picks)
}

/** Rounded to the nearest percent: 2 of 3 is 67%. */
private fun percent(part: Int, whole: Int) = if (whole > 0) (part * 200 + whole) / (whole * 2) else 0

/** Each map's play across [summaries], keyed by lowercase map path; only competitive games count, as the best comps do. */
fun mapPlay(summaries: List<MatchSummary>): Map<String, MapPlay> =
    summaries
        .filter { it.record.queueId == COMPETITIVE_QUEUE }
        .groupBy { it.record.mapId.lowercase() }
        .mapValues { (mapId, games) ->
            MapPlay(
                mapId = mapId,
                matches = games.size,
                wins = games.count { it.outcome == Outcome.WIN },
                mine = games.map { it.me.agentId to (it.outcome == Outcome.WIN) }.results(),
            )
        }

private fun List<Pair<String, Boolean>>.results(): List<AgentResult> =
    groupBy({ it.first }, { it.second })
        .map { (agentId, won) -> AgentResult(agentId, won.size, won.count { it }) }
        .sortedWith(compareByDescending<AgentResult> { it.picks }.thenByDescending { it.wins })
