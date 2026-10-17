package app.radianite.data.player

import app.radianite.data.content.Content
import app.radianite.data.match.MatchHistory
import app.radianite.data.match.RankedRatingChange
import app.radianite.data.match.rrTrend
import app.radianite.data.optional
import app.radianite.data.riot.ItemTypes
import app.radianite.data.riot.LeaderboardPlayer
import app.radianite.data.riot.RiotPlayerData
import app.radianite.data.store.StoreSkin
import app.radianite.data.store.storeSkin
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.time.Instant

data class Career(
    val riotId: String,
    val cardId: String?,
    val title: String?,
    val level: Int?,
    val xpIntoLevel: Int,
    /** Null when unknown; in the past when the first-win bonus is ready. */
    val nextFirstWinMillis: Long?,
    val rank: RankStatus?,
    val actHistory: List<ActRank>,
    val rrTrend: List<RankedRatingChange>,
    val battlePass: BattlePassProgress?,
) {
    val levelProgress: Float get() = xpIntoLevel / XP_PER_ACCOUNT_LEVEL.toFloat()
}

data class EquippedWeapon(val weaponId: String, val weaponName: String, val category: String, val skin: StoreSkin?)

data class PlayerCollection(
    val loadout: List<EquippedWeapon>,
    /** One entry per owned skin (not per level), sorted by name. */
    val skins: List<StoreSkin>,
    val ownedAgents: Int?,
    val totalAgents: Int,
) {
    fun search(query: String): List<StoreSkin> = query.trim().let { q -> if (q.isEmpty()) skins else skins.filter { it.name.contains(q, ignoreCase = true) } }
}

data class Leaderboard(val actName: String?, val totalPlayers: Int, val players: List<LeaderboardEntry>)

data class LeaderboardEntry(val rank: Int, val name: String, val cardId: String, val rankedRating: Int, val wins: Int, val tier: Int)

fun LeaderboardPlayer.toEntry() = LeaderboardEntry(
    rank = leaderboardRank,
    // Players can hide their name from the leaderboard.
    name = if (isAnonymized || gameName.isBlank()) "Secret Agent" else "$gameName#$tagLine",
    cardId = playerCardId,
    rankedRating = rankedRating,
    wins = numberOfWins,
    tier = competitiveTier,
)

/** The signed-in player's career, collection and the leaderboard they compete on. */
class PlayerRepository(
    private val riot: RiotPlayerData,
    private val content: Content,
    private val matches: MatchHistory,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /**
     * Each section loads independently: an endpoint Riot has changed or rate-limited hides its section
     * instead of failing the whole screen.
     */
    suspend fun career(): Career = coroutineScope {
        val game = async { content.gameContent() }
        val mmr = async { optional("Rank") { riot.playerMmr() } }
        val xp = async { optional("Account XP") { riot.accountXp() } }
        val loadout = async { optional("Loadout") { riot.playerLoadout() } }
        val contracts = async { optional("Contracts") { riot.contracts() } }
        val rr = async { optional("RR trend") { matches.rankedRatingChanges() } }

        val c = game.await()
        val now = clock()
        val identity = loadout.await()?.identity
        val progress = xp.await()
        val riotId = riot.savedSession()?.riotId.orEmpty()
        if (listOf(mmr, xp, loadout, contracts, rr).all { it.await() == null }) {
            throw IllegalStateException("Couldn't load your career from Riot.")
        }
        Career(
            riotId = riotId,
            cardId = identity?.playerCardId,
            title = identity?.playerTitleId?.let { c.titles[it.lowercase()] },
            level = progress?.progress?.level ?: identity?.accountLevel,
            xpIntoLevel = progress?.progress?.xp ?: 0,
            nextFirstWinMillis = progress?.nextTimeFirstWinAvailable?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() },
            rank = mmr.await()?.let { rankStatus(it, c, now) },
            actHistory = mmr.await()?.let { actHistory(it, c) }.orEmpty(),
            rrTrend = rrTrend(rr.await().orEmpty()),
            battlePass = contracts.await()?.let { battlePass(it, c, now) },
        )
    }

    suspend fun collection(): PlayerCollection = coroutineScope {
        val game = async { content.gameContent() }
        val catalog = async { content.catalog() }
        val loadout = async { riot.playerLoadout() }
        val skins = async { optional("Owned skins") { riot.ownedItems(ItemTypes.SKIN_LEVELS) } }
        val agents = async { optional("Owned agents") { riot.ownedItems(ItemTypes.AGENTS) } }

        val c = game.await()
        val cat = catalog.await()
        val ownedAgentIds = agents.await()?.itemIds
        PlayerCollection(
            loadout = loadout.await().guns.map { gun ->
                val weapon = c.weapons[gun.weaponId.lowercase()]
                EquippedWeapon(
                    weaponId = gun.weaponId.lowercase(),
                    weaponName = weapon?.name ?: "Weapon",
                    category = weapon?.category ?: "",
                    skin = cat.storeSkin(gun.skinLevelId),
                )
            }.sortedWith(compareBy<EquippedWeapon> { CATEGORY_ORDER.indexOf(it.category).let { i -> if (i < 0) Int.MAX_VALUE else i } }.thenBy { it.weaponName }),
            skins = skins.await()?.itemIds.orEmpty()
                .mapNotNull { cat.storeSkin(it) }
                .distinctBy { it.skinUuid }
                .sortedBy { it.name },
            ownedAgents = ownedAgentIds?.let { owned -> c.agents.count { (uuid, agent) -> agent.isBaseContent || uuid in owned } },
            totalAgents = c.agents.size,
        )
    }

    suspend fun leaderboard(query: String?): Leaderboard {
        val act = content.gameContent().currentAct(clock()) ?: throw IllegalStateException("The current act isn't known yet.")
        val response = riot.leaderboard(act.uuid, startIndex = 0, size = 200, query = query)
        return Leaderboard(act.name, response.totalPlayers, response.players.map { it.toEntry() })
    }

    private companion object {
        /** The in-game loadout order. */
        val CATEGORY_ORDER = listOf("Sidearm", "SMG", "Shotgun", "Rifle", "Sniper", "Heavy", "Melee")
    }
}
