package app.radianite.data.player

import android.util.Log
import app.radianite.data.content.Catalog
import app.radianite.data.content.GameContent
import app.radianite.data.content.GameContentRepository
import app.radianite.data.content.SkinCatalogRepository
import app.radianite.data.riot.CompetitiveUpdate
import app.radianite.data.riot.ItemTypes
import app.radianite.data.riot.LeaderboardPlayer
import app.radianite.data.riot.RiotApi
import app.radianite.data.riot.SessionExpiredException
import app.radianite.data.riot.SessionManager
import app.radianite.data.store.StoreSkin
import kotlinx.coroutines.CancellationException
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
    val rrTrend: List<CompetitiveUpdate>,
    val battlePass: BattlePassProgress?,
)

data class EquippedWeapon(val weaponId: String, val weaponName: String, val category: String, val skin: StoreSkin?)

data class PlayerCollection(
    val loadout: List<EquippedWeapon>,
    /** One entry per owned skin (not per level), sorted by name. */
    val skins: List<StoreSkin>,
    val ownedAgents: Int?,
    val totalAgents: Int,
)

data class Leaderboard(val actName: String?, val totalPlayers: Int, val players: List<LeaderboardPlayer>)

class PlayerRepository(
    private val riot: RiotApi,
    private val sessions: SessionManager,
    private val contentRepository: GameContentRepository,
    private val catalogRepository: SkinCatalogRepository,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun content(): GameContent = contentRepository.content(sessions.clientVersion())

    /**
     * Each section loads independently: an endpoint Riot has changed or rate-limited hides its section
     * instead of failing the whole screen.
     */
    suspend fun career(): Career = coroutineScope {
        val content = async { content() }
        val mmr = async { optional { sessions.call { s, v -> riot.playerMmr(s, v) } } }
        val xp = async { optional { sessions.call { s, v -> riot.accountXp(s, v) } } }
        val loadout = async { optional { sessions.call { s, v -> riot.playerLoadout(s, v) } } }
        val contracts = async { optional { sessions.call { s, v -> riot.contracts(s, v) } } }
        val updates = async { optional { sessions.call { s, v -> riot.competitiveUpdates(s, v, 20) } } }

        val c = content.await()
        val now = clock()
        val identity = loadout.await()?.identity
        val progress = xp.await()
        val riotId = sessions.savedSession()?.riotId.orEmpty()
        if (listOf(mmr, xp, loadout, contracts, updates).all { it.await() == null }) {
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
            rrTrend = rrTrend(updates.await()?.matches.orEmpty()),
            battlePass = contracts.await()?.let { battlePass(it, c, now) },
        )
    }

    suspend fun collection(): PlayerCollection = coroutineScope {
        val content = async { content() }
        val catalog = async { catalogRepository.catalog(sessions.clientVersion()) }
        val loadout = async { sessions.call { s, v -> riot.playerLoadout(s, v) } }
        val skins = async { optional { sessions.call { s, v -> riot.ownedItems(s, v, ItemTypes.SKIN_LEVELS) } } }
        val agents = async { optional { sessions.call { s, v -> riot.ownedItems(s, v, ItemTypes.AGENTS) } } }

        val c = content.await()
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
        val act = content().currentAct(clock()) ?: throw IllegalStateException("The current act isn't known yet.")
        val response = sessions.call { s, v -> riot.leaderboard(s, v, act.uuid, startIndex = 0, size = 200, query = query) }
        return Leaderboard(act.name, response.totalPlayers, response.players)
    }

    private fun Catalog.storeSkin(levelUuid: String): StoreSkin? {
        val key = levelUuid.lowercase()
        val skin = skinsByLevel[key] ?: return null
        val tier = skin.tierUuid?.let { tiers[it.lowercase()] }
        return StoreSkin(
            levelUuid = key,
            skinUuid = skin.skinUuid,
            name = skin.name,
            iconUrl = skin.icon,
            priceVp = null,
            tierName = tier?.name,
            tierColor = tier?.color,
            tierIconUrl = tier?.icon,
        )
    }

    /** Null on failure, except for cancellation and an expired session, which the caller must see. */
    private suspend fun <T> optional(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: SessionExpiredException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Career section unavailable", e)
        null
    }

    private companion object {
        const val TAG = "PlayerRepository"

        /** The in-game loadout order. */
        val CATEGORY_ORDER = listOf("Sidearm", "SMG", "Shotgun", "Rifle", "Sniper", "Heavy", "Melee")
    }
}
