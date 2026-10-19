package app.radianite.data.player

import app.radianite.data.content.Content
import app.radianite.data.content.WeaponInfo
import app.radianite.data.match.MatchHistory
import app.radianite.data.match.RankedRatingChange
import app.radianite.data.match.rrTrend
import app.radianite.data.optional
import app.radianite.data.riot.ItemTypes
import app.radianite.data.riot.LeaderboardPlayer
import app.radianite.data.riot.RiotPlayerData
import app.radianite.data.store.StoreSkin
import app.radianite.data.store.VALORANT_POINTS_UUID
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

/** An owned skin with what the collection filters and sorts by. Its [StoreSkin.priceVp] is its store price, when Riot sells it. */
data class OwnedSkin(val skin: StoreSkin, val weaponId: String?, val tierRank: Int?)

data class WeaponFilter(val weaponId: String, val name: String)

enum class SkinSort { NAME, RARITY, PRICE }

data class PlayerCollection(
    val loadout: List<EquippedWeapon>,
    /** One entry per owned skin (not per level), sorted by name. */
    val skins: List<OwnedSkin>,
    val ownedAgents: Int?,
    val totalAgents: Int,
    /** What the owned skins Riot sells would cost in the store; null when prices couldn't be loaded. */
    val valueVp: Int?,
    /** Weapons with at least one owned skin, in loadout order. */
    val weapons: List<WeaponFilter>,
) {
    /** Owned skins whose name contains [query], for [weaponId] when it's set, in [sort] order. */
    fun search(query: String, weaponId: String? = null, sort: SkinSort = SkinSort.NAME): List<StoreSkin> {
        val q = query.trim()
        val matching = skins.filter { (weaponId == null || it.weaponId == weaponId) && (q.isEmpty() || it.skin.name.contains(q, ignoreCase = true)) }
        val sorted = when (sort) {
            SkinSort.NAME -> matching
            SkinSort.RARITY -> matching.sortedWith(compareByDescending<OwnedSkin> { it.tierRank ?: -1 }.thenBy { it.skin.name })
            SkinSort.PRICE -> matching.sortedWith(compareByDescending<OwnedSkin> { it.skin.priceVp ?: -1 }.thenBy { it.skin.name })
        }
        return sorted.map { it.skin }
    }
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
        val offers = async { optional("Store prices") { riot.offers() } }

        val c = game.await()
        val cat = catalog.await()
        val ownedAgentIds = agents.await()?.itemIds
        // A skin's store price is its first level's offer; higher levels are bought with Radianite.
        val prices = offers.await()?.offers?.mapNotNull { offer ->
            val level = offer.rewards.firstOrNull()?.itemId ?: return@mapNotNull null
            val skin = cat.skinsByLevel[level.lowercase()] ?: return@mapNotNull null
            offer.cost[VALORANT_POINTS_UUID]?.let { skin.skinUuid to it }
        }?.toMap()
        val owned = skins.await()?.itemIds.orEmpty()
            .mapNotNull { level ->
                val entry = cat.skinsByLevel[level.lowercase()] ?: return@mapNotNull null
                val skin = cat.storeSkin(level, priceVp = prices?.get(entry.skinUuid)) ?: return@mapNotNull null
                OwnedSkin(skin, entry.weaponUuid.ifEmpty { null }, entry.tierUuid?.let { cat.tiers[it.lowercase()]?.rank })
            }
            .distinctBy { it.skin.skinUuid }
            .sortedBy { it.skin.name }
        PlayerCollection(
            loadout = loadout.await().guns.map { gun ->
                val weapon = c.weapons[gun.weaponId.lowercase()]
                EquippedWeapon(
                    weaponId = gun.weaponId.lowercase(),
                    weaponName = weapon?.name ?: "Weapon",
                    category = weapon?.category ?: "",
                    skin = cat.storeSkin(gun.skinLevelId),
                )
            }.sortedWith(compareBy<EquippedWeapon> { categoryRank(it.category) }.thenBy { it.weaponName }),
            skins = owned,
            ownedAgents = ownedAgentIds?.let { owned -> c.agents.count { (uuid, agent) -> agent.isBaseContent || uuid in owned } },
            totalAgents = c.agents.size,
            valueVp = prices?.let { owned.sumOf { it.skin.priceVp ?: 0 } },
            weapons = owned.mapNotNull { it.weaponId }.distinct()
                .map { id -> c.weapons[id] to id }
                .sortedWith(compareBy<Pair<WeaponInfo?, String>> { (weapon, _) -> categoryRank(weapon?.category) }.thenBy { (weapon, _) -> weapon?.name })
                .map { (weapon, id) -> WeaponFilter(id, weapon?.name ?: "Weapon") },
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

        fun categoryRank(category: String?) = CATEGORY_ORDER.indexOf(category).let { if (it < 0) Int.MAX_VALUE else it }
    }
}
