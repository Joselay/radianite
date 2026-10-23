package app.radianite.data.player

import app.radianite.data.content.Catalog
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
import app.radianite.data.store.toWallet
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

/** An owned skin with the weapon it's for and its rarity. [radianiteToMax] is what its locked upgrades cost: 0 when there are none, null when unknown. */
data class OwnedSkin(val skin: StoreSkin, val weaponId: String?, val tierRank: Int?, val radianiteToMax: Int? = null)

data class PlayerCollection(
    /** Every weapon in loadout order, with the skin equipped on it. */
    val loadout: List<EquippedWeapon>,
    /** One entry per owned skin (not per level), sorted by name. */
    val skins: List<OwnedSkin>,
    /** What unlocking every owned skin's levels and chromas would cost; null when Riot didn't say. */
    val radianiteToMax: Int? = null,
    /** The wallet's Radianite Points, when it loaded. */
    val radianite: Int? = null,
) {
    private val byWeapon: Map<String?, List<OwnedSkin>> by lazy {
        skins.groupBy { it.weaponId }.mapValues { (_, owned) -> owned.sortedWith(compareByDescending<OwnedSkin> { it.tierRank ?: -1 }.thenBy { it.skin.name }) }
    }

    /** [weaponId]'s owned skins, rarest first. */
    fun skinsFor(weaponId: String): List<OwnedSkin> = byWeapon[weaponId].orEmpty()

    /** Owned skins with something left to unlock with Radianite, most to spend first. */
    val toUpgrade: List<OwnedSkin> by lazy {
        skins.filter { (it.radianiteToMax ?: 0) > 0 }.sortedWith(compareByDescending<OwnedSkin> { it.radianiteToMax }.thenBy { it.skin.name })
    }

    /** Owned skins whose name contains [query], by name; none for a blank query. */
    fun search(query: String): List<OwnedSkin> {
        val q = query.trim()
        return if (q.isEmpty()) emptyList() else skins.filter { it.skin.name.contains(q, ignoreCase = true) }
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
    private val upgradeMutex = Mutex()

    /** Riot's Radianite price list with the catalog version it was indexed against. */
    @Volatile
    private var upgradeTable: Pair<String, Map<String, List<RadianiteUpgrade>>>? = null

    /** The skin levels and chromas the player owns, as of the last collection load. */
    @Volatile
    private var unlockedVariants: Set<String>? = null

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
        val unlocked = async { optional("Owned chromas") { riot.ownedItems(ItemTypes.SKIN_CHROMAS) } }
        val wallet = async { optional("Wallet") { riot.wallet().toWallet() } }

        val c = game.await()
        val cat = catalog.await()
        val ownedLevels = skins.await()?.itemIds
        val variants = ownedLevels?.let { levels -> unlocked.await()?.itemIds?.let { levels + it } }?.also { unlockedVariants = it }
        val upgrades = variants?.let { optional("Radianite upgrades") { radianiteUpgrades(cat) } }
        val owned = ownedLevels.orEmpty()
            .mapNotNull { level ->
                val entry = cat.skinsByLevel[level.lowercase()] ?: return@mapNotNull null
                val skin = cat.storeSkin(level) ?: return@mapNotNull null
                val toMax = upgrades?.let { it[entry.skinUuid.lowercase()]?.remainingRp(variants) ?: 0 }
                OwnedSkin(skin, entry.weaponUuid.ifEmpty { null }, entry.tierUuid?.let { cat.tiers[it.lowercase()]?.rank }, toMax)
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
            radianiteToMax = upgrades?.let { owned.sumOf { it.radianiteToMax ?: 0 } },
            radianite = wallet.await()?.radianite,
        )
    }

    /** [skinUuid]'s Radianite upgrades and which of them are unlocked, or null when it has none to buy. */
    suspend fun skinUpgrades(skinUuid: String): SkinUpgrades? = coroutineScope {
        val cat = content.catalog()
        val upgrades = radianiteUpgrades(cat)[skinUuid.lowercase()] ?: return@coroutineScope null
        val unlocked = unlockedVariants ?: run {
            val levels = async { riot.ownedItems(ItemTypes.SKIN_LEVELS).itemIds }
            val chromas = async { riot.ownedItems(ItemTypes.SKIN_CHROMAS).itemIds }
            (levels.await() + chromas.await()).also { unlockedVariants = it }
        }
        val ownsSkin = cat.skinsByLevel.any { (level, skin) -> level in unlocked && skin.skinUuid.equals(skinUuid, ignoreCase = true) }
        SkinUpgrades(upgrades.associate { it.variantUuid to it.rp }, unlocked.filterTo(HashSet()) { variant -> upgrades.any { it.variantUuid == variant } }, ownsSkin)
    }

    /** Riot's Radianite price list, fetched once per catalog version: it's large and only changes with a patch. */
    private suspend fun radianiteUpgrades(catalog: Catalog): Map<String, List<RadianiteUpgrade>> {
        upgradeTable?.takeIf { it.first == catalog.version }?.let { return it.second }
        return upgradeMutex.withLock {
            upgradeTable?.takeIf { it.first == catalog.version }?.second
                ?: riot.itemUpgrades().radianiteUpgrades(catalog).also { upgradeTable = catalog.version to it }
        }
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
