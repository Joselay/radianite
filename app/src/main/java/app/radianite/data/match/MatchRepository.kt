package app.radianite.data.match

import app.radianite.data.AppFiles
import app.radianite.data.content.GameContent
import app.radianite.data.content.GameContentRepository
import app.radianite.data.riot.CompetitiveUpdate
import app.radianite.data.riot.CoreGameMatchResponse
import app.radianite.data.riot.LivePlayerIdentity
import app.radianite.data.riot.MatchHistoryEntry
import app.radianite.data.riot.PregameMatchResponse
import app.radianite.data.riot.RiotApi
import app.radianite.data.riot.RiotSession
import app.radianite.data.riot.SessionManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
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

class MatchRepository(
    private val riot: RiotApi,
    private val sessions: SessionManager,
    private val contentRepository: GameContentRepository,
    private val files: AppFiles,
) {
    private val memory = ConcurrentHashMap<String, MatchRecord>()

    suspend fun content(): GameContent = contentRepository.content(sessions.clientVersion())

    suspend fun puuid(): String? = sessions.savedSession()?.puuid

    /** One page of match IDs, newest first. Riot serves at most 20 per request. */
    suspend fun history(startIndex: Int, count: Int): List<MatchHistoryEntry> =
        sessions.call { session, version -> riot.matchHistory(session, version, startIndex, startIndex + count) }.history

    /** Records for [entries], from disk where cached; matches that fail to load are left out. */
    suspend fun records(entries: List<MatchHistoryEntry>): List<MatchRecord> = coroutineScope {
        // Each match-details response is large; a few at a time keeps memory and rate limits in check.
        val permits = Semaphore(4)
        entries.map { entry ->
            async {
                permits.withPermit {
                    try {
                        record(entry.matchId)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        null
                    }
                }
            }
        }.awaitAll().filterNotNull()
    }.also { pruneCache() }

    suspend fun record(matchId: String): MatchRecord {
        memory[matchId]?.let { return it }
        val file = cacheFile(matchId)
        files.read(file, MatchRecord.serializer())?.let { return it.also { memory[matchId] = it } }
        val details = sessions.call { session, version -> riot.matchDetails(session, version, matchId) }
        val record = withContext(Dispatchers.Default) { details.toRecord() }
        // Matches still being processed come back incomplete; only cache the finished ones.
        if (record.players.isNotEmpty() && record.teams.isNotEmpty()) files.write(file, MatchRecord.serializer(), record)
        return record.also { memory[matchId] = it }
    }

    /** Ranked rating changes from recent competitive games, keyed by match ID. */
    suspend fun rankedRatingChanges(count: Int = 20): Map<String, CompetitiveUpdate> =
        sessions.call { session, version -> riot.competitiveUpdates(session, version, count) }
            .matches.associateBy { it.matchId }

    /** The agent select or game the player is in, or null when they aren't in one. */
    suspend fun liveMatch(): LiveMatch? = sessions.call { session, version ->
        riot.pregamePlayer(session, version)?.let { pointer ->
            return@call riot.pregameMatch(session, version, pointer.matchId).toLive(session, version)
        }
        riot.coreGamePlayer(session, version)?.let { pointer ->
            riot.coreGameMatch(session, version, pointer.matchId).toLive(session, version)
        }
    }

    private suspend fun PregameMatchResponse.toLive(session: RiotSession, version: String): LiveMatch {
        val players = allyTeam?.players.orEmpty()
        val names = visibleNames(session, version, players.map { it.subject to it.identity })
        return LiveMatch(
            phase = LiveMatch.Phase.AGENT_SELECT,
            mapId = mapId,
            queueId = queueId,
            allies = players.map {
                LivePlayer(
                    puuid = it.subject,
                    isSelf = it.subject == session.puuid,
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

    private suspend fun CoreGameMatchResponse.toLive(session: RiotSession, version: String): LiveMatch {
        val playing = players.filterNot { it.isCoach }
        val myTeam = playing.find { it.subject == session.puuid }?.teamId
        val names = visibleNames(session, version, playing.map { it.subject to it.identity })
        val live = playing.map {
            LivePlayer(
                puuid = it.subject,
                isSelf = it.subject == session.puuid,
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
    private suspend fun visibleNames(
        session: RiotSession,
        version: String,
        players: List<Pair<String, LivePlayerIdentity?>>,
    ): Map<String, String> {
        val visible = players.filter { (puuid, identity) -> puuid == session.puuid || identity?.incognito == false }.map { it.first }
        if (visible.isEmpty()) return emptyMap()
        return runCatching { riot.names(session, version, visible) }.getOrDefault(emptyList())
            .filter { it.gameName.isNotBlank() }
            .associate { it.subject to "${it.gameName}#${it.tagLine}" }
    }

    private fun LivePlayerIdentity.visibleLevel(): Int? = accountLevel.takeUnless { hideAccountLevel || it <= 0 }

    private fun cacheFile(matchId: String) = File(files.matches, "$matchId.json")

    private suspend fun pruneCache() = withContext(Dispatchers.IO) {
        val cached = files.matches.listFiles()?.takeIf { it.size > MAX_CACHED_MATCHES } ?: return@withContext
        cached.sortedByDescending { it.lastModified() }.drop(MAX_CACHED_MATCHES).forEach { it.delete() }
    }

    private companion object {
        const val MAX_CACHED_MATCHES = 200
    }
}
