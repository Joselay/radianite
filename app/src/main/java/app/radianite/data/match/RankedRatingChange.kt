package app.radianite.data.match

import app.radianite.data.riot.CompetitiveUpdate

/** How one competitive game moved the player's rank. */
data class RankedRatingChange(
    val matchId: String,
    val startMillis: Long,
    val tierAfter: Int,
    val rrEarned: Int,
)

fun CompetitiveUpdate.toChange() = RankedRatingChange(matchId, matchStartTime, tierAfter, rrEarned)

/** Oldest first, so it reads left to right as a trend. */
fun rrTrend(changes: Collection<RankedRatingChange>): List<RankedRatingChange> =
    changes.filter { it.startMillis > 0 }.sortedBy { it.startMillis }
