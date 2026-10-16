package app.radianite.data.content

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.time.Instant

/** Names and art for the IDs the pd endpoints return, indexed from valorant-api.com. */
@Serializable
data class GameContent(
    val version: String,
    val agents: Map<String, AgentInfo>,
    /** Keyed by lowercase map path, e.g. "/game/maps/ascent/ascent", which is what matches report. */
    val maps: Map<String, MapInfo>,
    val ranks: Map<Int, RankInfo>,
    val acts: List<ActInfo>,
    val queues: Map<String, String>,
    val weapons: Map<String, WeaponInfo>,
    val titles: Map<String, String>,
    /** Battle pass contracts keyed by contract UUID. */
    val battlePasses: Map<String, BattlePassInfo>,
) {
    fun agent(uuid: String?): AgentInfo? = uuid?.let { agents[it.lowercase()] }
    fun map(path: String?): MapInfo? = path?.let { maps[it.lowercase()] }
    fun rank(tier: Int): RankInfo? = ranks[tier]
    fun act(uuid: String?): ActInfo? = uuid?.let { id -> acts.find { it.uuid == id.lowercase() } }
    fun queueName(queueId: String): String = queues[queueId] ?: if (queueId.isEmpty()) "Custom" else queueId.replaceFirstChar { it.uppercase() }

    /** The act running at [nowMillis], if valorant-api.com knows about it yet. */
    fun currentAct(nowMillis: Long): ActInfo? = acts.find { nowMillis in it.startMillis until it.endMillis }
}

@Serializable
data class AgentInfo(
    val name: String,
    val icon: String?,
    val portrait: String?,
    val role: String?,
    /** Free starter agents; every account has them, and they never appear as entitlements. */
    val isBaseContent: Boolean = false,
)

@Serializable
data class MapInfo(val name: String, val splash: String?, val listIcon: String?)

/** [color] is RRGGBBAA hex. */
@Serializable
data class RankInfo(val name: String, val color: String, val icon: String?)

@Serializable
data class ActInfo(val uuid: String, val name: String, val startMillis: Long, val endMillis: Long)

@Serializable
data class WeaponInfo(val name: String, val category: String, val icon: String?)

/** [levelXp] is the XP each level needs, in order, epilogue included. */
@Serializable
data class BattlePassInfo(val name: String, val actUuid: String, val levelXp: List<Int>)

@Serializable
data class ApiAgent(
    val uuid: String,
    val displayName: String,
    val displayIcon: String? = null,
    val fullPortrait: String? = null,
    val role: ApiAgentRole? = null,
    val isBaseContent: Boolean = false,
)

@Serializable
data class ApiAgentRole(val displayName: String)

@Serializable
data class ApiMap(
    val uuid: String,
    val displayName: String,
    val mapUrl: String? = null,
    val splash: String? = null,
    val listViewIcon: String? = null,
)

@Serializable
data class ApiTierTable(val uuid: String, val tiers: List<ApiTier> = emptyList())

@Serializable
data class ApiTier(val tier: Int, val tierName: String, val color: String? = null, val smallIcon: String? = null)

@Serializable
data class ApiSeason(
    val uuid: String,
    val displayName: String,
    val type: String? = null,
    val startTime: String? = null,
    val endTime: String? = null,
    val parentUuid: String? = null,
)

@Serializable
data class ApiQueue(val queueId: String, val displayName: String)

@Serializable
data class ApiWeapon(val uuid: String, val displayName: String, val category: String = "", val displayIcon: String? = null)

@Serializable
data class ApiTitle(val uuid: String, val titleText: String? = null)

@Serializable
data class ApiContract(val uuid: String, val displayName: String, val content: ApiContractContent)

@Serializable
data class ApiContractContent(
    val relationType: String? = null,
    val relationUuid: String? = null,
    val chapters: List<ApiContractChapter> = emptyList(),
)

@Serializable
data class ApiContractChapter(val levels: List<ApiContractLevel> = emptyList())

@Serializable
data class ApiContractLevel(val xp: Int = 0)

fun buildGameContent(
    version: String,
    agents: List<ApiAgent>,
    maps: List<ApiMap>,
    tierTables: List<ApiTierTable>,
    seasons: List<ApiSeason>,
    queues: List<ApiQueue>,
    weapons: List<ApiWeapon>,
    titles: List<ApiTitle>,
    contracts: List<ApiContract>,
): GameContent {
    val episodes = seasons.filter { it.parentUuid == null }.associate { it.uuid.lowercase() to it.displayName }
    return GameContent(
        version = version,
        agents = agents.associate {
            it.uuid.lowercase() to AgentInfo(it.displayName, it.displayIcon, it.fullPortrait, it.role?.displayName, it.isBaseContent)
        },
        maps = maps.mapNotNull { map ->
            map.mapUrl?.let { it.lowercase() to MapInfo(map.displayName, map.splash, map.listViewIcon) }
        }.toMap(),
        // The newest table applies to current matches; older episodes' tiers line up with it.
        ranks = tierTables.lastOrNull()?.tiers.orEmpty()
            .filterNot { it.tierName.startsWith("Unused") }
            .associate { it.tier to RankInfo(it.tierName, it.color ?: "ffffffff", it.smallIcon) },
        acts = seasons.filter { it.type == "EAresSeasonType::Act" }.mapNotNull { act ->
            val start = act.startTime?.let(::parseInstant) ?: return@mapNotNull null
            val end = act.endTime?.let(::parseInstant) ?: return@mapNotNull null
            val episode = act.parentUuid?.let { episodes[it.lowercase()] }
            ActInfo(act.uuid.lowercase(), listOfNotNull(episode, act.displayName).joinToString(" · "), start, end)
        },
        queues = queues.filterNot { it.queueId.startsWith("console_") }.associate { it.queueId to it.displayName },
        weapons = weapons.associate {
            it.uuid.lowercase() to WeaponInfo(it.displayName, it.category.substringAfterLast("::"), it.displayIcon)
        },
        titles = titles.mapNotNull { t -> t.titleText?.takeIf { it.isNotBlank() }?.let { t.uuid.lowercase() to it } }.toMap(),
        battlePasses = contracts.filter { it.content.relationType == "Season" && it.content.relationUuid != null }.associate { contract ->
            contract.uuid.lowercase() to BattlePassInfo(
                name = contract.displayName,
                actUuid = contract.content.relationUuid!!.lowercase(),
                levelXp = contract.content.chapters.flatMap { chapter -> chapter.levels.map { it.xp } },
            )
        },
    )
}

private fun parseInstant(iso: String): Long? = runCatching { Instant.parse(iso).toEpochMilli() }.getOrNull()

/** Same caching scheme as [SkinCatalogRepository]: downloaded once per game version, kept compact on disk. */
class GameContentRepository(
    private val http: OkHttpClient,
    private val json: Json,
    private val cacheFile: File,
) {
    private val mutex = Mutex()

    @Volatile
    private var memory: GameContent? = null

    suspend fun content(version: String): GameContent {
        memory?.takeIf { it.version == version }?.let { return it }
        return mutex.withLock {
            memory?.takeIf { it.version == version }
                ?: (readDisk()?.takeIf { it.version == version } ?: downloadOrStale(version))
                    .also { memory = it }
        }
    }

    /** A stale index still names almost everything, so prefer it to failing when valorant-api.com is down. */
    private suspend fun downloadOrStale(version: String): GameContent = try {
        download(version).also { writeDisk(it) }
    } catch (e: IOException) {
        readDisk() ?: throw e
    }

    private suspend fun download(version: String): GameContent = coroutineScope {
        val agents = async { get<List<ApiAgent>>("https://valorant-api.com/v1/agents?isPlayableCharacter=true") }
        val maps = async { get<List<ApiMap>>("https://valorant-api.com/v1/maps") }
        val tiers = async { get<List<ApiTierTable>>("https://valorant-api.com/v1/competitivetiers") }
        val seasons = async { get<List<ApiSeason>>("https://valorant-api.com/v1/seasons") }
        val queues = async { get<List<ApiQueue>>("https://valorant-api.com/v1/gamemodes/queues") }
        val weapons = async { get<List<ApiWeapon>>("https://valorant-api.com/v1/weapons") }
        val titles = async { get<List<ApiTitle>>("https://valorant-api.com/v1/playertitles") }
        val contracts = async { get<List<ApiContract>>("https://valorant-api.com/v1/contracts") }
        withContext(Dispatchers.Default) {
            buildGameContent(
                version,
                agents.await(),
                maps.await(),
                tiers.await(),
                seasons.await(),
                queues.await(),
                weapons.await(),
                titles.await(),
                contracts.await(),
            )
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    private suspend inline fun <reified T> get(url: String): T = withContext(Dispatchers.IO) {
        http.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} from valorant-api.com")
            json.decodeFromStream<ContentEnvelope<T>>(response.body.byteStream()).data
        }
    }

    private suspend fun readDisk(): GameContent? = withContext(Dispatchers.IO) {
        runCatching { json.decodeFromString<GameContent>(cacheFile.readText()) }.getOrNull()
    }

    private suspend fun writeDisk(content: GameContent) = withContext(Dispatchers.IO) {
        val tmp = File(cacheFile.parentFile, cacheFile.name + ".tmp")
        tmp.writeText(json.encodeToString(GameContent.serializer(), content))
        tmp.renameTo(cacheFile)
    }

    @Serializable
    private data class ContentEnvelope<T>(val data: T)
}

/** Player card art follows a fixed CDN path, so it needs no lookup. */
object CardArt {
    fun wide(cardId: String?) = cardId?.takeIf { it.isNotBlank() }?.let { "https://media.valorant-api.com/playercards/$it/wideart.png" }
    fun small(cardId: String?) = cardId?.takeIf { it.isNotBlank() }?.let { "https://media.valorant-api.com/playercards/$it/smallart.png" }
}
