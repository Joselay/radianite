package app.radianite.data.riot

import app.radianite.data.AppFiles
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The Riot session cookies live in the WebView's cookie store; this is the seam to it. */
interface RiotCookies {
    suspend fun header(): String?
    suspend fun store(setCookies: List<String>)
    suspend fun clear()
}

/** The Riot session is gone and the user has to sign in again. */
class SessionExpiredException : Exception("Your Riot session expired. Sign in again.")

/**
 * Owns the signed-in account's tokens: persists them, renews them silently with the Riot cookies,
 * and hands a valid session to every pd/glz call.
 */
class SessionManager(
    private val riot: RiotApi,
    private val cookies: RiotCookies,
    private val files: AppFiles,
    private val userAgent: () -> String,
    private val fetchClientVersion: suspend () -> String,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()

    @Volatile
    private var session: RiotSession? = null

    @Volatile
    private var version: Pair<String, Long>? = null

    private val _expired = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Fires when a call finds the Riot session gone, so the app can return to sign-in. */
    val expired: SharedFlow<Unit> = _expired.asSharedFlow()

    suspend fun savedSession(): RiotSession? = session ?: files.read(files.session, RiotSession.serializer())?.also { session = it }

    suspend fun completeSignIn(tokens: AuthTokens): RiotSession = mutex.withLock { establish(tokens, previous = null) }

    /** Current client version for X-Riot-ClientVersion; it changes once per patch, so it's reused for a while. */
    suspend fun clientVersion(): String {
        version?.takeIf { clock() - it.second < VERSION_TTL_MILLIS }?.let { return it.first }
        return fetchClientVersion().also { version = it to clock() }
    }

    /** Runs [block] with a valid session, renewing the tokens once if Riot rejects them. */
    suspend fun <T> call(block: suspend (session: RiotSession, clientVersion: String) -> T): T = try {
        val clientVersion = clientVersion()
        val current = fresh(forceReauth = false)
        try {
            block(current, clientVersion)
        } catch (e: RiotHttpException) {
            if (!e.isAuthError) throw e
            block(fresh(forceReauth = true), clientVersion)
        }
    } catch (e: SessionExpiredException) {
        _expired.tryEmit(Unit)
        throw e
    }

    suspend fun signOut() {
        session = null
        cookies.clear()
        files.clear()
    }

    private suspend fun fresh(forceReauth: Boolean): RiotSession = mutex.withLock {
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
        val region = async { previous?.region ?: riot.liveAffinity(tokens.accessToken, tokens.idToken) }
        val user = info.await()
        RiotSession(
            accessToken = tokens.accessToken,
            idToken = tokens.idToken,
            expiresAtMillis = clock() + tokens.expiresInSeconds * 1000,
            entitlementsToken = entitlements.await(),
            puuid = user.sub,
            gameName = user.acct?.gameName,
            tagLine = user.acct?.tagLine,
            shard = previous?.shard ?: RiotAuth.shardForAffinity(region.await()),
            region = region.await(),
        ).also {
            session = it
            files.write(files.session, RiotSession.serializer(), it)
        }
    }

    private companion object {
        const val TOKEN_MARGIN_MILLIS = 2 * 60 * 1000L
        const val VERSION_TTL_MILLIS = 15 * 60 * 1000L
    }
}
