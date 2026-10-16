package app.radianite.data.store

import app.radianite.data.content.SkinCatalogRepository
import app.radianite.data.riot.AuthTokens
import app.radianite.data.riot.RiotApi
import app.radianite.data.riot.RiotAuth
import app.radianite.data.riot.RiotHttpException
import app.radianite.data.riot.RiotSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import java.io.File

/** The Riot session cookies live in the WebView's cookie store; this is the seam to it. */
interface RiotCookies {
    suspend fun header(): String?
    suspend fun store(setCookies: List<String>)
    suspend fun clear()
}

/** The Riot session is gone and the user has to sign in again. */
class SessionExpiredException : Exception("Your Riot session expired. Sign in again.")

class StoreRepository(
    private val riot: RiotApi,
    private val catalog: SkinCatalogRepository,
    private val cookies: RiotCookies,
    private val files: AppFiles,
    private val userAgent: () -> String,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val sessionMutex = Mutex()

    @Volatile
    private var session: RiotSession? = null

    suspend fun savedSession(): RiotSession? = session ?: files.read(files.session, RiotSession.serializer())?.also { session = it }

    /** Last fetched store for this account, if it hasn't rotated yet. */
    suspend fun cachedStore(puuid: String): DailyStore? =
        files.read(files.dailyStore, DailyStore.serializer())?.takeIf { it.puuid == puuid && it.expiresAtMillis > clock() }

    suspend fun completeSignIn(tokens: AuthTokens): RiotSession = sessionMutex.withLock { establish(tokens, previous = null) }

    suspend fun fetchDailyStore(): DailyStore = coroutineScope {
        val version = catalog.clientVersion()
        val catalogJob = async { catalog.catalog(version) }
        val session = freshSession(forceReauth = false)
        val (usedSession, front) = try {
            session to riot.storefront(session, version)
        } catch (e: RiotHttpException) {
            if (!e.isAuthError) throw e
            val renewed = freshSession(forceReauth = true)
            renewed to riot.storefront(renewed, version)
        }
        front.toDailyStore(usedSession.puuid, catalogJob.await(), clock())
            .also { files.write(files.dailyStore, DailyStore.serializer(), it) }
    }

    suspend fun signOut() {
        session = null
        cookies.clear()
        files.clear()
    }

    private suspend fun freshSession(forceReauth: Boolean): RiotSession = sessionMutex.withLock {
        val current = savedSession() ?: throw SessionExpiredException()
        if (!forceReauth && current.expiresAtMillis - clock() > TOKEN_MARGIN_MILLIS) return current

        val cookieHeader = cookies.header() ?: throw SessionExpiredException()
        val reauth = riot.reauthorize(cookieHeader, userAgent()) ?: throw SessionExpiredException()
        cookies.store(reauth.setCookies)
        establish(reauth.tokens, previous = current)
    }

    private suspend fun establish(tokens: AuthTokens, previous: RiotSession?): RiotSession = coroutineScope {
        val info = async { riot.userInfo(tokens.accessToken) }
        val entitlements = async { riot.entitlementsToken(tokens.accessToken) }
        val shard = async {
            previous?.shard ?: RiotAuth.shardForAffinity(riot.liveAffinity(tokens.accessToken, tokens.idToken))
        }
        val user = info.await()
        RiotSession(
            accessToken = tokens.accessToken,
            idToken = tokens.idToken,
            expiresAtMillis = clock() + tokens.expiresInSeconds * 1000,
            entitlementsToken = entitlements.await(),
            puuid = user.sub,
            gameName = user.acct?.gameName,
            tagLine = user.acct?.tagLine,
            shard = shard.await(),
        ).also {
            session = it
            files.write(files.session, RiotSession.serializer(), it)
        }
    }

    private companion object {
        const val TOKEN_MARGIN_MILLIS = 2 * 60 * 1000L
    }
}

/** Small JSON files in app-private storage (excluded from backups). */
class AppFiles(dir: File, private val json: Json) {
    val session = File(dir, "session.json")
    val dailyStore = File(dir, "daily_store.json")

    suspend fun <T> read(file: File, serializer: KSerializer<T>): T? = withContext(Dispatchers.IO) {
        if (!file.exists()) null else runCatching { json.decodeFromString(serializer, file.readText()) }.getOrNull()
    }

    suspend fun <T> write(file: File, serializer: KSerializer<T>, value: T) = withContext(Dispatchers.IO) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.encodeToString(serializer, value))
        tmp.renameTo(file)
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        session.delete()
        dailyStore.delete()
    }
}
