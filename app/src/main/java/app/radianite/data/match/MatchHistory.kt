package app.radianite.data.match

import app.radianite.data.LocalStore
import app.radianite.data.Slots
import app.radianite.data.optional
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

/** Every match loaded so far, newest first; [endReached] once Riot has no older ones. */
data class MatchPage(val matches: List<MatchSummary>, val endReached: Boolean)

/**
 * One account's matches, from its point of view. Pages through Riot's history, keeps finished
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
    private var pagedFor: Pair<String, String?>? = null
    private var loaded: List<MatchSummary> = emptyList()
    private var nextIndex = 0
    private var endReached = false

    @Volatile
    private var rrCache: RrCache? = null

    /** The newest page, starting over from the top; only [queue]'s matches when it's set. */
    suspend fun firstPage(queue: String? = null): MatchPage = pageLock.withLock { loadFirstPage(riot.puuid(), queue) }

    /** The next older page added to what's loaded; starts from the top if nothing is loaded for this account and queue. */
    suspend fun nextPage(queue: String? = null): MatchPage = pageLock.withLock {
        val puuid = riot.puuid()
        if (pagedFor != puuid to queue) return@withLock loadFirstPage(puuid, queue)
        if (endReached) return@withLock MatchPage(loaded, endReached = true)
        val rr = optional("Ranked rating changes") { rankedRatingChanges() }.orEmpty()
        val entries = riot.matchHistory(nextIndex, PAGE_SIZE, queue).history
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

    /** Every match kept on the phone for this account, newest first, without going online. */
    suspend fun stored(): List<MatchSummary> {
        val kept = local.readAll(Slots.matches).onEach { records.putIfAbsent(it.matchId, it) }
        return summarize(riot.puuid(), kept, rr = emptyList())
    }

    /** Recent competitive games' rank changes, newest first. Shared briefly so screens loading together fetch once. */
    suspend fun rankedRatingChanges(maxAgeMillis: Long = RR_MAX_AGE_MILLIS): List<RankedRatingChange> {
        val puuid = riot.puuid()
        rrCache?.takeIf { it.puuid == puuid && clock() - it.fetchedAtMillis < maxAgeMillis }?.let { return it.changes }
        val changes = riot.competitiveUpdates(RR_COUNT).matches.map { it.toChange() }
        rrCache = RrCache(puuid, clock(), changes)
        return changes
    }

    private suspend fun loadFirstPage(puuid: String, queue: String?): MatchPage = coroutineScope {
        val rr = async { optional("Ranked rating changes") { rankedRatingChanges(maxAgeMillis = 0) }.orEmpty() }
        val entries = riot.matchHistory(0, PAGE_SIZE, queue).history
        val summaries = summarize(puuid, load(entries.map { it.matchId }), rr.await())
        pagedFor = puuid to queue
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

    private class RrCache(val puuid: String, val fetchedAtMillis: Long, val changes: List<RankedRatingChange>)

    private companion object {
        const val PAGE_SIZE = 10
        const val RR_COUNT = 20
        const val RR_MAX_AGE_MILLIS = 60_000L
    }
}
