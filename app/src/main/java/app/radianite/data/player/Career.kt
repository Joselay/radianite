package app.radianite.data.player

import app.radianite.data.content.GameContent
import app.radianite.data.riot.CompetitiveUpdate
import app.radianite.data.riot.ContractsResponse
import app.radianite.data.riot.PlayerMmrResponse

const val COMPETITIVE_QUEUE = "competitive"

/** Account levels always take 5,000 XP. */
const val XP_PER_ACCOUNT_LEVEL = 5_000

data class RankStatus(
    val tier: Int,
    val rankedRating: Int,
    /** This act, if valorant-api.com knows the current act yet. */
    val actName: String?,
    val wins: Int,
    val games: Int,
    /** Highest tier won at this act; 0 before the first win. */
    val peakTier: Int,
    /** 0 when the player isn't on the leaderboard. */
    val leaderboardRank: Int,
    val placementsRemaining: Int,
) {
    val winPercent: Int get() = if (games > 0) wins * 100 / games else 0
}

/** A past act's badge: the highest tier won during it. */
data class ActRank(val actUuid: String, val actName: String, val peakTier: Int, val wins: Int, val games: Int)

data class BattlePassProgress(val name: String, val level: Int, val totalLevels: Int, val xpIntoLevel: Int, val xpForLevel: Int)

fun rankStatus(mmr: PlayerMmrResponse, content: GameContent, nowMillis: Long): RankStatus? {
    val competitive = mmr.queueSkills[COMPETITIVE_QUEUE]
    val act = content.currentAct(nowMillis)
    val actInfo = competitive?.seasonalInfo.orEmpty().entries.find { it.key.equals(act?.uuid, ignoreCase = true) }?.value
    val latest = mmr.latestCompetitiveUpdate?.takeIf { it.matchId.isNotBlank() }
    if (competitive == null && latest == null) return null
    // Rank carries over between acts, so the latest ranked game is the current rank even early in a new act.
    return RankStatus(
        tier = latest?.tierAfter ?: actInfo?.competitiveTier ?: 0,
        rankedRating = latest?.rrAfter ?: actInfo?.rankedRating ?: 0,
        actName = act?.name,
        wins = actInfo?.wins ?: 0,
        games = actInfo?.games ?: 0,
        peakTier = actInfo?.winsByTier.peakTier(),
        leaderboardRank = actInfo?.leaderboardRank ?: 0,
        placementsRemaining = competitive?.currentSeasonGamesNeededForRating ?: 0,
    )
}

/** Past and current acts the player played ranked in, newest first. */
fun actHistory(mmr: PlayerMmrResponse, content: GameContent): List<ActRank> =
    mmr.queueSkills[COMPETITIVE_QUEUE]?.seasonalInfo.orEmpty().values
        .filter { it.games > 0 }
        .mapNotNull { info ->
            val act = content.act(info.seasonId) ?: return@mapNotNull null
            act to ActRank(act.uuid, act.name, info.winsByTier.peakTier(), info.wins, info.games)
        }
        .sortedByDescending { (act, _) -> act.startMillis }
        .map { it.second }

/** Oldest first, so it reads left to right as a trend. */
fun rrTrend(updates: Collection<CompetitiveUpdate>): List<CompetitiveUpdate> =
    updates.filter { it.matchStartTime > 0 }.sortedBy { it.matchStartTime }

/** Progress in this act's battle pass, falling back to the newest one the player has touched. */
fun battlePass(contracts: ContractsResponse, content: GameContent, nowMillis: Long): BattlePassProgress? {
    val currentAct = content.currentAct(nowMillis)?.uuid
    val passes = contracts.contracts.mapNotNull { progress ->
        content.battlePasses[progress.contractDefinitionId.lowercase()]?.let { it to progress }
    }
    val (pass, progress) = passes.find { (pass, _) -> pass.actUuid == currentAct }
        ?: passes.maxByOrNull { (pass, _) -> content.act(pass.actUuid)?.startMillis ?: 0 }
        ?: return null
    return BattlePassProgress(
        name = pass.name,
        level = progress.levelReached,
        totalLevels = pass.levelXp.size,
        xpIntoLevel = progress.progressTowardsNextLevel,
        // levelXp[i] is what level i+1 needs, so the next level's cost sits at index levelReached.
        xpForLevel = pass.levelXp.getOrNull(progress.levelReached) ?: 0,
    )
}

private fun Map<String, Int>?.peakTier(): Int = this?.keys?.mapNotNull { it.toIntOrNull() }?.maxOrNull() ?: 0
