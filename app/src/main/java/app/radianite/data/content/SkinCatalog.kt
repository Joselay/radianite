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

/** Compact lookup from skin-level UUID (what the store returns) to display data. */
@Serializable
data class Catalog(
    val version: String,
    val skinsByLevel: Map<String, CatalogSkin>,
    val tiers: Map<String, CatalogTier>,
    // No default: catalogs cached before bundles were indexed fail to decode and get rebuilt.
    val bundles: Map<String, CatalogBundle>,
)

@Serializable
data class CatalogSkin(
    // No default: catalogs cached before this field existed fail to decode and get rebuilt.
    val skinUuid: String,
    val name: String,
    val icon: String?,
    val tierUuid: String?,
)

/** [color] is RRGGBBAA hex, as published by valorant-api.com. */
@Serializable
data class CatalogTier(
    val name: String,
    val color: String,
    val icon: String?,
)

@Serializable
data class CatalogBundle(
    val name: String,
    val art: String?,
)

@Serializable
private data class Envelope<T>(val data: T)

@Serializable
data class ApiVersion(val riotClientVersion: String)

@Serializable
data class ApiSkin(
    val uuid: String,
    val displayName: String,
    val displayIcon: String? = null,
    val contentTierUuid: String? = null,
    val levels: List<ApiSkinLevel> = emptyList(),
    val chromas: List<ApiChroma> = emptyList(),
)

@Serializable
data class ApiSkinLevel(val uuid: String, val displayIcon: String? = null)

@Serializable
data class ApiChroma(val fullRender: String? = null)

@Serializable
data class ApiContentTier(
    val uuid: String,
    val devName: String,
    val highlightColor: String,
    val displayIcon: String? = null,
)

@Serializable
data class ApiBundle(
    val uuid: String,
    val displayName: String,
    val displayIcon: String? = null,
    val verticalPromoImage: String? = null,
)

fun buildCatalog(
    version: String,
    skins: List<ApiSkin>,
    tiers: List<ApiContentTier>,
    bundles: List<ApiBundle> = emptyList(),
): Catalog {
    val byLevel = HashMap<String, CatalogSkin>(skins.size * 2)
    for (skin in skins) {
        val icon = skin.levels.firstOrNull()?.displayIcon
            ?: skin.displayIcon
            ?: skin.chromas.firstOrNull()?.fullRender
        val entry = CatalogSkin(skin.uuid, skin.displayName, icon, skin.contentTierUuid)
        for (level in skin.levels) byLevel[level.uuid.lowercase()] = entry
    }
    return Catalog(
        version = version,
        skinsByLevel = byLevel,
        tiers = tiers.associate { it.uuid.lowercase() to CatalogTier(it.devName, it.highlightColor, it.displayIcon) },
        bundles = bundles.associate { it.uuid.lowercase() to CatalogBundle(it.displayName, it.verticalPromoImage ?: it.displayIcon) },
    )
}

/**
 * Skin names, icons, rarity tiers and bundle art from the community-run valorant-api.com. The full skin
 * list is a few MB, so it's reduced to a compact index and cached on disk per game version.
 */
class SkinCatalogRepository(
    private val http: OkHttpClient,
    private val json: Json,
    private val cacheFile: File,
) {
    private val mutex = Mutex()

    @Volatile
    private var memory: Catalog? = null

    /** Current Riot client version string, needed for the X-Riot-ClientVersion header. */
    suspend fun clientVersion(): String = try {
        get<ApiVersion>("https://valorant-api.com/v1/version").riotClientVersion
    } catch (e: IOException) {
        // Offline or valorant-api.com down: the last cached version is almost always still accepted.
        loadedCatalog()?.version ?: throw e
    }

    suspend fun catalog(version: String): Catalog {
        memory?.takeIf { it.version == version }?.let { return it }
        return mutex.withLock {
            memory?.takeIf { it.version == version }
                ?: (readDisk()?.takeIf { it.version == version } ?: download(version).also { writeDisk(it) })
                    .also { memory = it }
        }
    }

    private suspend fun loadedCatalog(): Catalog? = memory ?: mutex.withLock { memory ?: readDisk()?.also { memory = it } }

    private suspend fun download(version: String): Catalog = coroutineScope {
        val skins = async { get<List<ApiSkin>>("https://valorant-api.com/v1/weapons/skins") }
        val tiers = async { get<List<ApiContentTier>>("https://valorant-api.com/v1/contenttiers") }
        val bundles = async { get<List<ApiBundle>>("https://valorant-api.com/v1/bundles") }
        val (s, t, b) = Triple(skins.await(), tiers.await(), bundles.await())
        withContext(Dispatchers.Default) { buildCatalog(version, s, t, b) }
    }

    @OptIn(ExperimentalSerializationApi::class)
    private suspend inline fun <reified T> get(url: String): T = withContext(Dispatchers.IO) {
        http.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} from valorant-api.com")
            json.decodeFromStream<Envelope<T>>(response.body.byteStream()).data
        }
    }

    private suspend fun readDisk(): Catalog? = withContext(Dispatchers.IO) {
        runCatching { json.decodeFromString<Catalog>(cacheFile.readText()) }.getOrNull()
    }

    private suspend fun writeDisk(catalog: Catalog) = withContext(Dispatchers.IO) {
        val tmp = File(cacheFile.parentFile, cacheFile.name + ".tmp")
        tmp.writeText(json.encodeToString(Catalog.serializer(), catalog))
        tmp.renameTo(cacheFile)
    }
}
