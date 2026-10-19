package app.radianite.data.content

import app.radianite.data.LocalStore
import app.radianite.data.Slot
import app.radianite.data.Slots
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Names and art for the current patch, from the community-run valorant-api.com. Works out the game version
 * itself, keeps each index compact on disk per version, and falls back to the last index it has when
 * valorant-api.com can't be reached, since a stale index still names almost everything.
 */
class Content(
    http: OkHttpClient,
    json: Json,
    store: LocalStore,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val api = ValorantApi(http, json)

    private val catalogIndex = VersionedIndex(store, Slots.skinCatalog, Catalog::version) { version ->
        coroutineScope {
            // The weapons list rather than weapons/skins: it says which weapon each skin is for.
            val skins = async { api.get("weapons", ListSerializer(ApiWeaponSkins.serializer())) }
            val tiers = async { api.get("contenttiers", ListSerializer(ApiContentTier.serializer())) }
            val bundles = async { api.get("bundles", ListSerializer(ApiBundle.serializer())) }
            val (s, t, b) = Triple(skins.await(), tiers.await(), bundles.await())
            withContext(Dispatchers.Default) { buildCatalog(version, s, t, b) }
        }
    }

    private val gameIndex = VersionedIndex(store, Slots.gameContent, GameContent::version) { version ->
        coroutineScope {
            val agents = async { api.get("agents?isPlayableCharacter=true", ListSerializer(ApiAgent.serializer())) }
            val maps = async { api.get("maps", ListSerializer(ApiMap.serializer())) }
            val tiers = async { api.get("competitivetiers", ListSerializer(ApiTierTable.serializer())) }
            val seasons = async { api.get("seasons", ListSerializer(ApiSeason.serializer())) }
            val queues = async { api.get("gamemodes/queues", ListSerializer(ApiQueue.serializer())) }
            val weapons = async { api.get("weapons", ListSerializer(ApiWeapon.serializer())) }
            val titles = async { api.get("playertitles", ListSerializer(ApiTitle.serializer())) }
            val contracts = async { api.get("contracts", ListSerializer(ApiContract.serializer())) }
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
    }

    private val skinDetails = ConcurrentHashMap<String, SkinDetail>()

    @Volatile
    private var version: Pair<String, Long>? = null

    private val _game = MutableStateFlow<GameContent?>(null)

    /** The newest game content loaded so far, for screens that name agents, maps and ranks. */
    val game: StateFlow<GameContent?> = _game.asStateFlow()

    /** Current Riot client version; it changes once per patch, so it's reused for a while. */
    suspend fun version(): String {
        version?.takeIf { clock() - it.second < VERSION_TTL_MILLIS }?.let { return it.first }
        val current = try {
            api.get("version", ApiVersion.serializer()).riotClientVersion
        } catch (e: IOException) {
            // Offline or valorant-api.com down: the last known version is almost always still accepted.
            version?.first ?: catalogIndex.loaded()?.version ?: gameIndex.loaded()?.version ?: throw e
        }
        return current.also { version = it to clock() }
    }

    /** Agents, maps, ranks, acts, queues, weapons, titles and battle passes. */
    suspend fun gameContent(): GameContent = gameIndex.get(version()).also { _game.value = it }

    /** Skins, rarity tiers and bundles. */
    suspend fun catalog(): Catalog = catalogIndex.get(version())

    /** One skin's levels and chromas, with their preview videos. */
    suspend fun skinDetail(skinUuid: String): SkinDetail =
        skinDetails[skinUuid] ?: api.get("weapons/skins/$skinUuid", ApiSkinDetail.serializer()).toSkinDetail().also { skinDetails[skinUuid] = it }

    private companion object {
        const val VERSION_TTL_MILLIS = 15 * 60 * 1000L
    }
}

/** An index built from several valorant-api.com lists, kept in memory and on disk for one game version at a time. */
private class VersionedIndex<T : Any>(
    private val store: LocalStore,
    private val slot: Slot<T>,
    private val versionOf: (T) -> String,
    private val download: suspend (version: String) -> T,
) {
    private val mutex = Mutex()

    @Volatile
    private var memory: T? = null

    suspend fun get(version: String): T {
        memory?.takeIf { versionOf(it) == version }?.let { return it }
        return mutex.withLock {
            memory?.takeIf { versionOf(it) == version }
                ?: (store.read(slot)?.takeIf { versionOf(it) == version } ?: downloadOrStale(version))
                    .also { memory = it }
        }
    }

    /** Whatever index is at hand, whatever its version. */
    suspend fun loaded(): T? = memory ?: mutex.withLock { memory ?: store.read(slot)?.also { memory = it } }

    private suspend fun downloadOrStale(version: String): T = try {
        download(version).also { store.write(slot, it) }
    } catch (e: IOException) {
        memory ?: store.read(slot) ?: throw e
    }
}

private class ValorantApi(private val http: OkHttpClient, private val json: Json) {
    @OptIn(ExperimentalSerializationApi::class)
    suspend fun <T> get(path: String, serializer: KSerializer<T>): T = withContext(Dispatchers.IO) {
        http.newCall(Request.Builder().url("https://valorant-api.com/v1/$path").build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} from valorant-api.com")
            json.decodeFromStream(Envelope.serializer(serializer), response.body.byteStream()).data
        }
    }

    @Serializable
    private data class Envelope<T>(val data: T)
}
