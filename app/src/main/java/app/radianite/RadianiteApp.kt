@file:OptIn(UnstableApi::class)

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
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.mp4.Mp4Extractor
import androidx.media3.extractor.text.SubtitleParser
import app.radianite.data.LocalStore
import app.radianite.data.account.Account
import app.radianite.data.account.AccountState
import app.radianite.data.account.SignInCookies
import app.radianite.data.content.Content
import app.radianite.data.match.MatchHistory
import app.radianite.data.player.PlayerRepository
import app.radianite.data.riot.AccountCookies
import app.radianite.data.riot.RiotAuth
import app.radianite.data.riot.RiotPlayerData
import app.radianite.data.riot.RiotSignIn
import app.radianite.data.store.StoreRepository
import app.radianite.data.store.Wishlist
import app.radianite.data.update.AppUpdates
import app.radianite.data.update.AppVersion
import app.radianite.sync.StoreSync
import app.radianite.update.UpdateCheck
import app.radianite.widget.StoreWidget
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.Cache
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

class RadianiteApp : Application(), SingletonImageLoader.Factory {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        StoreSync.createChannel(this)
        UpdateCheck.createChannel(this)
        container.startBackgroundWork()
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

    /** For work that lasts as long as the process: the Account session and background scheduling. */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

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

    /** The app's own store; each account's data sits in a store of its own. */
    val device by lazy { LocalStore(appContext.filesDir, json) }

    val content: Content by lazy { Content(http, json, device) }

    private val riotSignIn by lazy { RiotSignIn(http, json) }

    private val accounts = ConcurrentHashMap<String, AccountData>()

    /** [puuid]'s data modules, created the first time anything asks for that account. */
    fun accountData(puuid: String): AccountData = accounts.computeIfAbsent(puuid) { AccountData(it) }

    val account: Account by lazy {
        Account(device, riotSignIn, WebViewSignInCookies(), riotFor = { accountData(it).riot }, appScope)
    }

    val wishlist: Wishlist by lazy { Wishlist(device) }

    /** New versions from the app's GitHub releases; null in debug builds, which are a separate app. */
    val updates: AppUpdates? by lazy {
        if (BuildConfig.DEBUG) return@lazy null
        // A build from before versions read "1.2.3" counts as older than any of them.
        val installed = AppVersion.parse(BuildConfig.VERSION_NAME) ?: AppVersion(0, 0, 0)
        AppUpdates(http.newBuilder().cache(null).build(), json, device, File(appContext.cacheDir, "updates"), installed)
    }

    /** Hands a downloaded update to Android's installer. */
    fun install(apk: File) = UpdateCheck.install(appContext, apk)

    /** One signed-in account's Riot connection and the data modules over it. */
    inner class AccountData(puuid: String) {
        private val local = device.forAccount(puuid)

        val riot = RiotPlayerData(
            http = http,
            json = json,
            cookies = AccountCookies(local),
            store = local,
            signIn = riotSignIn,
            userAgent = { WebSettings.getDefaultUserAgent(appContext) },
            clientVersion = content::version,
        )

        val store = StoreRepository(riot, content, local, device, onRefreshed = { StoreWidget.refresh(appContext) })

        val matches = MatchHistory(riot, local)

        val players = PlayerRepository(riot, content, matches)
    }

    /**
     * Checks for app updates daily, keeps the background store sync scheduled while any account is signed in,
     * and redraws the widget whenever the shown account changes.
     */
    fun startBackgroundWork() {
        if (updates != null) UpdateCheck.schedule(appContext)
        appScope.launch {
            account.ready()
            account.saved.map { it.isNotEmpty() }.distinctUntilChanged().collect { signedIn ->
                if (signedIn) StoreSync.schedule(appContext) else StoreSync.cancel(appContext)
            }
        }
        appScope.launch {
            account.state.filter { it != AccountState.Unknown && it != AccountState.CompletingSignIn }
                .map { (it as? AccountState.SignedIn)?.puuid }
                .distinctUntilChanged()
                .collect { StoreWidget.refresh(appContext) }
        }
    }

    // SimpleCache must be a single instance per directory for the whole process.
    private val videoCache: SimpleCache by lazy {
        SimpleCache(
            File(appContext.cacheDir, "video"),
            LeastRecentlyUsedCacheEvictor(300L * 1024 * 1024),
            StandaloneDatabaseProvider(appContext),
        )
    }

    /**
     * Skin preview videos stream from Riot's CDN; replays come from the disk cache. Every one is an MP4 with
     * sound, so the player gets only a video and an audio renderer and the MP4 extractor, which leaves the
     * rest of Media3 out of the app.
     */
    fun newVideoPlayer(context: Context): ExoPlayer {
        val dataSource = CacheDataSource.Factory()
            .setCache(videoCache)
            .setUpstreamDataSourceFactory(OkHttpDataSource.Factory(http.newBuilder().cache(null).build()))
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        val renderers = RenderersFactory { handler, video, audio, _, _ ->
            arrayOf(
                MediaCodecVideoRenderer(context, MediaCodecSelector.DEFAULT, VIDEO_JOINING_MILLIS, handler, video, DROPPED_FRAMES_TO_REPORT),
                MediaCodecAudioRenderer(context, MediaCodecSelector.DEFAULT, handler, audio),
            )
        }
        val mp4 = ProgressiveMediaSource.Factory(dataSource, ExtractorsFactory { arrayOf(Mp4Extractor(SubtitleParser.Factory.UNSUPPORTED)) })
        return ExoPlayer.Builder(context, renderers, mp4).build()
    }

    private companion object {
        // Media3's own defaults, from DefaultRenderersFactory.
        const val VIDEO_JOINING_MILLIS = 5_000L
        const val DROPPED_FRAMES_TO_REPORT = 50
    }
}

/** The sign-in page's cookies live in the WebView's cookie store, which must be used on the main thread. */
private class WebViewSignInCookies : SignInCookies {
    override suspend fun take(): String? = withContext(Dispatchers.Main) {
        val header = CookieManager.getInstance().getCookie(RiotAuth.COOKIE_URL)?.takeIf { it.isNotBlank() }
        clearNow()
        header
    }

    override suspend fun clear() = withContext(Dispatchers.Main) { clearNow() }

    private suspend fun clearNow() {
        val manager = CookieManager.getInstance()
        suspendCancellableCoroutine { cont -> manager.removeAllCookies { cont.resume(Unit) } }
        manager.flush()
    }
}
