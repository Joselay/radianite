package app.radianite

import android.app.Application
import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebSettings
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import app.radianite.data.content.SkinCatalogRepository
import app.radianite.data.content.SkinDetailRepository
import app.radianite.data.riot.RiotApi
import app.radianite.data.riot.RiotAuth
import app.radianite.data.store.AppFiles
import app.radianite.data.store.RiotCookies
import app.radianite.data.store.StoreRepository
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.Cache
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

class RadianiteApp : Application(), SingletonImageLoader.Factory {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            // Coil keeps its own disk cache, so images skip the OkHttp cache.
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { container.http.newBuilder().cache(null).build() })) }
            .crossfade(true)
            .build()
}

/** Hand-wired dependencies; the graph is small enough that a DI framework would only cost startup time. */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .cache(Cache(File(appContext.cacheDir, "http"), 20L * 1024 * 1024))
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    val storeRepository: StoreRepository by lazy {
        StoreRepository(
            riot = RiotApi(http, json),
            catalog = SkinCatalogRepository(http, json, File(appContext.filesDir, "skin_catalog.json")),
            cookies = WebViewRiotCookies(),
            files = AppFiles(appContext.filesDir, json),
            userAgent = { WebSettings.getDefaultUserAgent(appContext) },
        )
    }

    val skinDetailRepository: SkinDetailRepository by lazy { SkinDetailRepository(http, json) }

    // SimpleCache must be a single instance per directory for the whole process.
    @get:OptIn(UnstableApi::class)
    private val videoCache: SimpleCache by lazy {
        SimpleCache(
            File(appContext.cacheDir, "video"),
            LeastRecentlyUsedCacheEvictor(300L * 1024 * 1024),
            StandaloneDatabaseProvider(appContext),
        )
    }

    /** Skin preview videos stream from Riot's CDN; replays come from the disk cache. */
    @OptIn(UnstableApi::class)
    fun newVideoPlayer(context: Context): ExoPlayer {
        val dataSource = CacheDataSource.Factory()
            .setCache(videoCache)
            .setUpstreamDataSourceFactory(OkHttpDataSource.Factory(http.newBuilder().cache(null).build()))
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        return ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSource))
            .build()
    }
}

private class WebViewRiotCookies : RiotCookies {
    override suspend fun header(): String? = withContext(Dispatchers.Main) {
        CookieManager.getInstance().getCookie(RiotAuth.COOKIE_URL)?.takeIf { it.isNotBlank() }
    }

    override suspend fun store(setCookies: List<String>) = withContext(Dispatchers.Main) {
        val manager = CookieManager.getInstance()
        setCookies.forEach { manager.setCookie(RiotAuth.COOKIE_URL, it) }
        manager.flush()
    }

    override suspend fun clear() = withContext(Dispatchers.Main) {
        val manager = CookieManager.getInstance()
        suspendCancellableCoroutine { cont -> manager.removeAllCookies { cont.resume(Unit) } }
        manager.flush()
    }
}
