package app.radianite.data.riot

import app.radianite.data.LocalStore
import app.radianite.data.Slots
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Base64

/** The Riot session cookies live in the WebView's cookie store; this is the seam to it. */
interface RiotCookies {
    suspend fun header(): String?
    suspend fun store(setCookies: List<String>)

    /** Persists cookies the sign-in WebView just received, so silent renewal can use them. */
    suspend fun flush()
    suspend fun clear()
}

/** The Riot session is gone and the user has to sign in again. */
class SessionExpiredException : Exception("Your Riot session expired. Sign in again.")

/** ItemTypeIDs for [RiotPlayerData.ownedItems]. */
object ItemTypes {
    const val AGENTS = "01bb38e1-da47-4e6a-9b3d-945fe4655707"
    const val SKIN_LEVELS = "e7c63390-eda7-46e0-bb7a-a6abdacd2433"
}

/**
 * The signed-in account's VALORANT player data. Callers ask for the thing they want; this module finds a
 * valid session, picks the shard and region hosts, sends the game client's headers, renews rejected tokens
 * once (shared by every call that hit the same rejection), and reports a session that can't be renewed as
 * [SessionExpiredException], also announced on [expired].
 *
 * The pd and glz endpoints are the ones the game client uses; they are undocumented and not part of
 * Riot's official developer API. Tests substitute the transport through the [OkHttpClient].
 */
class RiotPlayerData(
    private val http: OkHttpClient,
    private val json: Json,
    private val cookies: RiotCookies,
    private val store: LocalStore,
    private val userAgent: () -> String,
    /** Current client version for X-Riot-ClientVersion. */
    private val clientVersion: suspend () -> String,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()

    @Volatile
    private var session: RiotSession? = null

    private val noRedirects = http.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    private val _expired = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Fires when a call finds the Riot session gone, so the app can return to sign-in. */
    val expired: SharedFlow<Unit> = _expired.asSharedFlow()

    suspend fun savedSession(): RiotSession? = session ?: store.read(Slots.session)?.also { session = it }

    /** The signed-in player's PUUID. */
    suspend fun puuid(): String = expiring { (savedSession() ?: throw SessionExpiredException()).puuid }

    /** Exchanges the tokens from Riot's sign-in redirect for a saved session. */
    suspend fun signIn(tokens: AuthTokens): RiotSession = mutex.withLock { establish(tokens, previous = null) }

    /** Forgets the tokens and the Riot cookies. Waits for any renewal in flight, so it can't resurrect them. */
    suspend fun signOut() = mutex.withLock {
        session = null
        store.delete(Slots.session)
        cookies.clear()
    }

    suspend fun storefront(): StorefrontResponse =
        call(StorefrontResponse.serializer(), body = EMPTY_JSON) { pd(it, "store/v3/storefront/${it.puuid}") }

    suspend fun wallet(): WalletResponse =
        call(WalletResponse.serializer()) { pd(it, "store/v1/wallet/${it.puuid}") }

    /** [itemTypeId] is one of the [ItemTypes] constants. */
    suspend fun ownedItems(itemTypeId: String): OwnedItemsResponse =
        call(OwnedItemsResponse.serializer()) { pd(it, "store/v1/entitlements/${it.puuid}/$itemTypeId") }

    /** Newest first. Riot caps a page at 20 matches. */
    suspend fun matchHistory(startIndex: Int, count: Int): MatchHistoryResponse =
        call(MatchHistoryResponse.serializer()) {
            pd(it, "match-history/v1/history/${it.puuid}?startIndex=$startIndex&endIndex=${startIndex + count}")
        }

    suspend fun matchDetails(matchId: String): MatchDetailsResponse =
        call(MatchDetailsResponse.serializer()) { pd(it, "match-details/v1/matches/$matchId") }

    suspend fun playerMmr(): PlayerMmrResponse =
        call(PlayerMmrResponse.serializer()) { pd(it, "mmr/v1/players/${it.puuid}") }

    /** The newest [count] competitive games' ranked rating changes. */
    suspend fun competitiveUpdates(count: Int): CompetitiveUpdatesResponse =
        call(CompetitiveUpdatesResponse.serializer()) {
            pd(it, "mmr/v1/players/${it.puuid}/competitiveupdates?startIndex=0&endIndex=$count&queue=competitive")
        }

    suspend fun accountXp(): AccountXpResponse =
        call(AccountXpResponse.serializer()) { pd(it, "account-xp/v1/players/${it.puuid}") }

    suspend fun playerLoadout(): PlayerLoadoutResponse =
        call(PlayerLoadoutResponse.serializer()) { pd(it, "personalization/v3/players/${it.puuid}/playerloadout") }

    suspend fun contracts(): ContractsResponse =
        call(ContractsResponse.serializer()) { pd(it, "contracts/v1/contracts/${it.puuid}") }

    /** The player's region's competitive leaderboard for the act [seasonId]. */
    suspend fun leaderboard(seasonId: String, startIndex: Int, size: Int, query: String?): LeaderboardResponse =
        call(LeaderboardResponse.serializer()) {
            pd(it, "mmr/v1/leaderboards/affinity/${it.affinity}/queue/competitive/season/$seasonId")
                .toHttpUrl().newBuilder()
                .addQueryParameter("startIndex", startIndex.toString())
                .addQueryParameter("size", size.toString())
                .apply { if (!query.isNullOrBlank()) addQueryParameter("query", query) }
                .build()
                .toString()
        }

    suspend fun names(puuids: List<String>): List<NameServiceEntry> {
        val body = json.encodeToString(ListSerializer(String.serializer()), puuids).toRequestBody(JSON_MEDIA)
        return call(ListSerializer(NameServiceEntry.serializer()), body = body, method = "PUT") { pd(it, "name-service/v2/players") }
    }

    /** The agent select the player is in, or null when they aren't in one. */
    suspend fun pregame(): PregameMatchResponse? {
        val pointer = callOrNullIfMissing(LiveMatchPointer.serializer()) { glz(it, "pregame/v1/players/${it.puuid}") } ?: return null
        return call(PregameMatchResponse.serializer()) { glz(it, "pregame/v1/matches/${pointer.matchId}") }
    }

    /** The in-progress game the player is in, or null when they aren't in one. */
    suspend fun coreGame(): CoreGameMatchResponse? {
        val pointer = callOrNullIfMissing(LiveMatchPointer.serializer()) { glz(it, "core-game/v1/players/${it.puuid}") } ?: return null
        return call(CoreGameMatchResponse.serializer()) { glz(it, "core-game/v1/matches/${pointer.matchId}") }
    }

    /** One pd/glz request with a valid session, renewing the tokens once if Riot rejects them. */
    private suspend fun <T> call(
        deserializer: DeserializationStrategy<T>,
        body: RequestBody? = null,
        method: String = if (body == null) "GET" else "POST",
        url: (RiotSession) -> String,
    ): T = expiring {
        val version = clientVersion()
        val current = fresh(rejected = null)
        try {
            execute(gameRequest(current, version, url(current), method, body), deserializer)
        } catch (e: RiotHttpException) {
            if (!e.isAuthError) throw e
            val renewed = fresh(rejected = current)
            try {
                execute(gameRequest(renewed, version, url(renewed), method, body), deserializer)
            } catch (e: RiotHttpException) {
                // Tokens Riot handed out moments ago are rejected too: the session is no good.
                if (e.isTokenRejected) throw SessionExpiredException()
                throw e
            }
        }
    }

    /** Riot answers 404 (RESOURCE_NOT_FOUND) when the player has no live match of that kind. */
    private suspend fun <T> callOrNullIfMissing(deserializer: DeserializationStrategy<T>, url: (RiotSession) -> String): T? = try {
        call(deserializer, url = url)
    } catch (e: RiotHttpException) {
        if (e.status == 404) null else throw e
    }

    private suspend fun <T> expiring(block: suspend () -> T): T = try {
        block()
    } catch (e: SessionExpiredException) {
        _expired.tryEmit(Unit)
        throw e
    }

    /**
     * A session that's safe to use. [rejected] is one Riot just turned down; when another call has already
     * renewed past it, the renewed session is returned rather than renewing again.
     */
    private suspend fun fresh(rejected: RiotSession?): RiotSession = mutex.withLock {
        val current = savedSession() ?: throw SessionExpiredException()
        val unexpired = current.expiresAtMillis - clock() > TOKEN_MARGIN_MILLIS
        if (unexpired && current.accessToken != rejected?.accessToken) return current

        val cookieHeader = cookies.header() ?: throw SessionExpiredException()
        val reauth = reauthorize(cookieHeader) ?: throw SessionExpiredException()
        cookies.store(reauth.setCookies)
        establish(reauth.tokens, previous = current)
    }

    /**
     * Re-runs the authorize request with the WebView's Riot cookies. If the Riot session is
     * still valid, Riot redirects straight to the opt-in URL with fresh tokens.
     */
    private suspend fun reauthorize(cookieHeader: String): Reauthorization? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(RiotAuth.AUTHORIZE_URL)
            .header("Cookie", cookieHeader)
            .header("User-Agent", userAgent())
            .build()
        noRedirects.newCall(request).execute().use { response ->
            val location = response.header("Location") ?: return@use null
            val tokens = RiotAuth.parseRedirect(location) ?: return@use null
            Reauthorization(tokens, response.headers("Set-Cookie"))
        }
    }

    private suspend fun establish(tokens: AuthTokens, previous: RiotSession?): RiotSession = coroutineScope {
        val info = async { userInfo(tokens.accessToken) }
        val entitlements = async { entitlementsToken(tokens.accessToken) }
        val region = async { previous?.region ?: liveAffinity(tokens.accessToken, tokens.idToken) }
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
            store.write(Slots.session, it)
        }
    }

    private suspend fun userInfo(accessToken: String): UserInfoResponse =
        execute(Request.Builder().url("https://auth.riotgames.com/userinfo").bearer(accessToken).build(), UserInfoResponse.serializer())

    private suspend fun entitlementsToken(accessToken: String): String =
        execute(
            Request.Builder().url("https://entitlements.auth.riotgames.com/api/token/v1").bearer(accessToken).post(EMPTY_JSON).build(),
            EntitlementsResponse.serializer(),
        ).entitlementsToken

    /** The account's live region affinity, e.g. "ap", "na", "eu". */
    private suspend fun liveAffinity(accessToken: String, idToken: String): String {
        val body = """{"id_token":"$idToken"}""".toRequestBody(JSON_MEDIA)
        val response = execute(
            Request.Builder().url("https://riot-geo.pas.si.riotgames.com/pas/v1/product/valorant").bearer(accessToken).put(body).build(),
            PasResponse.serializer(),
        )
        return response.affinities["live"] ?: throw RiotHttpException(200, null, "Riot didn't return a region for this account")
    }

    private suspend fun <T> execute(request: Request, deserializer: DeserializationStrategy<T>): T = withContext(Dispatchers.IO) {
        http.newCall(request).execute().use { response ->
            val body = response.body.string()
            if (!response.isSuccessful) {
                val error = runCatching { json.decodeFromString(RiotErrorBody.serializer(), body) }.getOrNull()
                throw RiotHttpException(
                    status = response.code,
                    errorCode = error?.errorCode,
                    message = error?.message ?: "HTTP ${response.code} from ${request.url.host}",
                )
            }
            json.decodeFromString(deserializer, body)
        }
    }

    private fun pd(session: RiotSession, path: String) = "https://pd.${session.shard}.a.pvp.net/$path"

    private fun glz(session: RiotSession, path: String) = "https://glz-${session.affinity}-1.${session.shard}.a.pvp.net/$path"

    /** Headers the game client sends on every pd/glz call. */
    private fun gameRequest(session: RiotSession, clientVersion: String, url: String, method: String, body: RequestBody?) =
        Request.Builder()
            .url(url)
            .bearer(session.accessToken)
            .header("X-Riot-Entitlements-JWT", session.entitlementsToken)
            .header("X-Riot-ClientPlatform", CLIENT_PLATFORM)
            .header("X-Riot-ClientVersion", clientVersion)
            .method(method, body)
            .build()

    private fun Request.Builder.bearer(token: String) = header("Authorization", "Bearer $token")

    /** Result of a cookie-based silent sign-in: fresh tokens plus any rotated cookies. */
    private class Reauthorization(val tokens: AuthTokens, val setCookies: List<String>)

    private companion object {
        const val TOKEN_MARGIN_MILLIS = 2 * 60 * 1000L

        val JSON_MEDIA = "application/json".toMediaType()
        val EMPTY_JSON = "{}".toRequestBody(JSON_MEDIA)

        // Same shape the PC client sends: tab-indented JSON with CRLF line endings, base64-encoded.
        val CLIENT_PLATFORM: String = Base64.getEncoder().encodeToString(
            (
                "{\r\n" +
                    "\t\"platformType\": \"PC\",\r\n" +
                    "\t\"platformOS\": \"Windows\",\r\n" +
                    "\t\"platformOSVersion\": \"10.0.19042.1.256.64bit\",\r\n" +
                    "\t\"platformChipset\": \"Unknown\"\r\n" +
                    "}"
                ).toByteArray(),
        )
    }
}
