package app.radianite.data.match

import app.radianite.data.LocalStore
import app.radianite.data.Slots
import app.radianite.data.optional
import app.radianite.data.riot.CoreGameMatchResponse
import app.radianite.data.riot.LivePlayerIdentity
import app.radianite.data.riot.PregameMatchResponse
import app.radianite.data.riot.RiotPlayerData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/** A match the player is in right now. Names are only shown for players who aren't in incognito mode. */
data class LiveMatch(
    val phase: Phase,
    val mapId: String,
    val queueId: String?,
    val allies: List<LivePlayer>,
    val enemies: List<LivePlayer>,
) {
    enum class Phase { AGENT_SELECT, IN_PROGRESS }
}

data class LivePlayer(
    val puuid: String,
    val isSelf: Boolean,
    val name: String?,
    val agentId: String?,
    val locked: Boolean,
    val accountLevel: Int?,
)

/** Every match loaded so far, newest first; [endReached] once Riot has no older ones. */
data class MatchPage(val matches: List<MatchSummary>, val endReached: Boolean)

/**
 * The signed-in account's matches, from its point of view. Pages through Riot's history, keeps finished
 * matches on disk, and attaches each recent competitive game's ranked rating change.
 */
class MatchHistory(
    private val riot: RiotPlayerData,
    private val local: LocalStore,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val records = ConcurrentHashMap<String, MatchRecord>()

    // Paging state, guarded by pageLock: one page loads at a time.
    private val pageLock = Mutex()
    private var pagedFor: String? = null
    private var loaded: List<MatchSummary> = emptyList()
    private var nextIndex = 0
    private var endReached = false

    @Volatile
    private var rrCache: RrCache? = null

    /** The newest page, starting over from the top. */
    suspend fun firstPage(): MatchPage = pageLock.withLock { loadFirstPage(riot.puuid()) }

    /** The next older page added to what's loaded; starts from the top if nothing is loaded for this account. */
    suspend fun nextPage(): MatchPage = pageLock.withLock {
        val puuid = riot.puuid()
        if (pagedFor != puuid) return@withLock loadFirstPage(puuid)
        if (endReached) return@withLock MatchPage(loaded, endReached = true)
        val rr = optional("Ranked rating changes") { rankedRatingChanges() }.orEmpty()
        val entries = riot.matchHistory(nextIndex, PAGE_SIZE).history
        val summaries = summarize(puuid, load(entries.map { it.matchId }), rr)
        // Index by entries, not loaded.size: matches that fail to load are skipped.
        nextIndex += entries.size
        endReached = entries.size < PAGE_SIZE
        loaded = (loaded + summaries).distinctBy { it.record.matchId }.sortedByDescending { it.record.startMillis }
        MatchPage(loaded, endReached)
    }

    /** One match, if it has been loaded. */
    suspend fun summary(matchId: String): MatchSummary? {
        val puuid = riot.puuid()
        val record = records[matchId] ?: local.read(Slots.matches, matchId) ?: return null
        val rr = rrCache?.takeIf { it.puuid == puuid }?.changes.orEmpty()
        return summarize(puuid, listOf(record), rr).firstOrNull()
    }

    /** The newest [count] matches, for career stats. */
    suspend fun recent(count: Int): List<MatchSummary> {
        val puuid = riot.puuid()
        val entries = riot.matchHistory(0, count).history
        val rr = optional("Ranked rating changes") { rankedRatingChanges() }.orEmpty()
        return summarize(puuid, load(entries.map { it.matchId }), rr)
    }

    /** Recent competitive games' rank changes, newest first. Shared briefly so screens loading together fetch once. */
    suspend fun rankedRatingChanges(maxAgeMillis: Long = RR_MAX_AGE_MILLIS): List<RankedRatingChange> {
        val puuid = riot.puuid()
        rrCache?.takeIf { it.puuid == puuid && clock() - it.fetchedAtMillis < maxAgeMillis }?.let { return it.changes }
        val changes = riot.competitiveUpdates(RR_COUNT).matches.map { it.toChange() }
        rrCache = RrCache(puuid, clock(), changes)
        return changes
    }

    /** The agent select or game the player is in, or null when they aren't in one. */
    suspend fun liveMatch(): LiveMatch? {
        val self = riot.puuid()
        riot.pregame()?.let { return it.toLive(self) }
        return riot.coreGame()?.toLive(self)
    }

    private suspend fun loadFirstPage(puuid: String): MatchPage = coroutineScope {
        val rr = async { optional("Ranked rating changes") { rankedRatingChanges(maxAgeMillis = 0) }.orEmpty() }
        val entries = riot.matchHistory(0, PAGE_SIZE).history
        val summaries = summarize(puuid, load(entries.map { it.matchId }), rr.await())
        pagedFor = puuid
        loaded = summaries
        nextIndex = entries.size
        endReached = entries.size < PAGE_SIZE
        MatchPage(loaded, endReached)
    }

    private fun summarize(puuid: String, records: List<MatchRecord>, rr: List<RankedRatingChange>): List<MatchSummary> {
        val rrByMatch = rr.associateBy { it.matchId }
        return records
            .mapNotNull { record -> record.summaryFor(puuid)?.copy(rr = rrByMatch[record.matchId]) }
            .sortedByDescending { it.record.startMillis }
    }

    /** Records for [matchIds], from disk where cached; matches that fail to load are left out. */
    private suspend fun load(matchIds: List<String>): List<MatchRecord> = coroutineScope {
        // Each match-details response is large; a few at a time keeps memory and rate limits in check.
        val permits = Semaphore(4)
        matchIds.map { id -> async { permits.withPermit { optional("Match $id") { record(id) } } } }.awaitAll().filterNotNull()
    }

    private suspend fun record(matchId: String): MatchRecord {
        records[matchId]?.let { return it }
        local.read(Slots.matches, matchId)?.let { return it.also { records[matchId] = it } }
        val details = riot.matchDetails(matchId)
        val record = withContext(Dispatchers.Default) { details.toRecord() }
        // Matches still being processed come back incomplete; only keep the finished ones.
        if (record.players.isNotEmpty() && record.teams.isNotEmpty()) {
            local.write(Slots.matches, matchId, record)
            records[matchId] = record
        }
        return record
    }

    private suspend fun PregameMatchResponse.toLive(self: String): LiveMatch {
        val players = allyTeam?.players.orEmpty()
        val names = visibleNames(self, players.map { it.subject to it.identity })
        return LiveMatch(
            phase = LiveMatch.Phase.AGENT_SELECT,
            mapId = mapId,
            queueId = queueId,
            allies = players.map {
                LivePlayer(
                    puuid = it.subject,
                    isSelf = it.subject == self,
                    name = names[it.subject],
                    agentId = it.characterId.ifBlank { null }?.lowercase(),
                    locked = it.selectionState == "locked",
                    accountLevel = it.identity?.visibleLevel(),
                )
            },
            // Riot doesn't reveal the enemy team until the game loads.
            enemies = emptyList(),
        )
    }

    private suspend fun CoreGameMatchResponse.toLive(self: String): LiveMatch {
        val playing = players.filterNot { it.isCoach }
        val myTeam = playing.find { it.subject == self }?.teamId
        val names = visibleNames(self, playing.map { it.subject to it.identity })
        val live = playing.map {
            LivePlayer(
                puuid = it.subject,
                isSelf = it.subject == self,
                name = names[it.subject],
                agentId = it.characterId.ifBlank { null }?.lowercase(),
                locked = true,
                accountLevel = it.identity?.visibleLevel(),
            )
        }
        val teamOf = playing.associate { it.subject to it.teamId }
        return LiveMatch(
            phase = LiveMatch.Phase.IN_PROGRESS,
            mapId = mapId,
            queueId = null,
            allies = live.filter { teamOf[it.puuid] == myTeam },
            enemies = live.filter { teamOf[it.puuid] != myTeam },
        )
    }

    /** Names for players who chose to show them; incognito players stay hidden, as they are in game. */
    private suspend fun visibleNames(self: String, players: List<Pair<String, LivePlayerIdentity?>>): Map<String, String> {
        val visible = players.filter { (puuid, identity) -> puuid == self || identity?.incognito == false }.map { it.first }
        if (visible.isEmpty()) return emptyMap()
        return optional("Player names") { riot.names(visible) }.orEmpty()
            .filter { it.gameName.isNotBlank() }
            .associate { it.subject to "${it.gameName}#${it.tagLine}" }
    }

    private fun LivePlayerIdentity.visibleLevel(): Int? = accountLevel.takeUnless { hideAccountLevel || it <= 0 }

    private class RrCache(val puuid: String, val fetchedAtMillis: Long, val changes: List<RankedRatingChange>)

    private companion object {
        const val PAGE_SIZE = 10
        const val RR_COUNT = 20
        const val RR_MAX_AGE_MILLIS = 60_000L
    }
}
