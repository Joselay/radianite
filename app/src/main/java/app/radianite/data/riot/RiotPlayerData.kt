package app.radianite.data.riot

import app.radianite.data.LocalStore
import app.radianite.data.Slots
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Base64

/** An account's Riot session cookies, which silent renewal signs in with. */
interface RiotCookies {
    /** The cookies as a Cookie header, or null when there are none. */
    suspend fun header(): String?

    /** Starts over with the cookies in [header], as the sign-in page left them. */
    suspend fun replace(header: String?)

    /** Merges in Set-Cookie headers from a renewal. */
    suspend fun store(setCookies: List<String>)
    suspend fun clear()
}

/** The Riot session is gone and the user has to sign in again. */
class SessionExpiredException : Exception("Your Riot session expired. Sign in again.")

/** ItemTypeIDs for [RiotPlayerData.ownedItems]. */
object ItemTypes {
    const val AGENTS = "01bb38e1-da47-4e6a-9b3d-945fe4655707"
    const val SKIN_LEVELS = "e7c63390-eda7-46e0-bb7a-a6abdacd2433"
    const val BUDDY_LEVELS = "dd3bf334-87f3-40bd-b043-682a57a8dc3a"
    const val SPRAYS = "d5f120f8-ff8c-4aac-92ea-f2b5acbe9475"
    const val PLAYER_CARDS = "3f296c07-64c3-494c-923b-fe692a4fa1bd"
    const val TITLES = "de7caa6b-adf7-4588-bbd1-143831e786c6"
}

/**
 * One signed-in account's VALORANT player data. Callers ask for the thing they want; this module finds a
 * valid session, picks the shard and region hosts, sends the game client's headers, renews rejected tokens
 * once (shared by every call that hit the same rejection), and reports a session that can't be renewed as
 * [SessionExpiredException], also announced on [expired].
 *
 * The pd endpoints are the ones the game client uses; they are undocumented and not part of
 * Riot's official developer API. Tests substitute the transport through the [OkHttpClient].
 *
 * Read-only by rule: nothing here buys, equips, picks agents, queues or otherwise acts on the account,
 * nothing reads a match while it's being played (the glz live-game endpoints), and nothing reveals what the
 * game hides. Those are what Riot treats as unfair advantages, automation or real-time help, and what puts
 * accounts at risk.
 */
class RiotPlayerData(
    private val http: OkHttpClient,
    private val json: Json,
    private val cookies: RiotCookies,
    /** The account's own store, where its session is kept. */
    private val store: LocalStore,
    private val signIn: RiotSignIn,
    private val userAgent: () -> String,
    /** Current client version for X-Riot-ClientVersion. */
    private val clientVersion: suspend () -> String,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()

    @Volatile
    private var session: RiotSession? = null

    /** Until when calls wait out a rate limit rather than ask Riot again. */
    @Volatile
    private var coolingDownUntil = 0L

    private val noRedirects = http.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    private val _expired = MutableStateFlow(false)

    /**
     * True once a call finds the Riot session gone, until a new session is adopted, so the app can return to
     * sign-in. A state rather than an event: whoever starts watching after the call still finds out.
     */
    val expired: StateFlow<Boolean> = _expired.asStateFlow()

    suspend fun savedSession(): RiotSession? = session ?: store.read(Slots.session)?.also { session = it }

    /** The signed-in player's PUUID. */
    suspend fun puuid(): String = expiring { (savedSession() ?: throw SessionExpiredException()).puuid }

    /** Takes over a session just signed in, with the cookies the sign-in page set. */
    suspend fun adopt(session: RiotSession, cookieHeader: String?) = mutex.withLock {
        cookies.replace(cookieHeader)
        save(session)
        _expired.value = false
    }

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

    /** Everything the store sells and its full price; not per account, but only reachable signed in. */
    suspend fun offers(): OffersResponse =
        call(OffersResponse.serializer()) { pd(it, "store/v1/offers/") }

    /** [itemTypeId] is one of the [ItemTypes] constants. */
    suspend fun ownedItems(itemTypeId: String): OwnedItemsResponse =
        call(OwnedItemsResponse.serializer()) { pd(it, "store/v1/entitlements/${it.puuid}/$itemTypeId") }

    /** Newest first, only [queue]'s matches when it's set (e.g. "competitive"). Riot caps a page at 20 matches. */
    suspend fun matchHistory(startIndex: Int, count: Int, queue: String? = null): MatchHistoryResponse =
        call(MatchHistoryResponse.serializer()) {
            pd(it, "match-history/v1/history/${it.puuid}?startIndex=$startIndex&endIndex=${startIndex + count}" + (queue?.let { q -> "&queue=$q" } ?: ""))
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

    /** One pd request with a valid session, renewing the tokens once if Riot rejects them. */
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

    private suspend fun <T> expiring(block: suspend () -> T): T = try {
        block()
    } catch (e: SessionExpiredException) {
        _expired.value = true
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
        save(signIn.session(reauth.tokens, previous = current))
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

    private suspend fun save(session: RiotSession): RiotSession {
        this.session = session
        store.write(Slots.session, session)
        return session
    }

    /**
     * Once Riot rate-limits a call, every call fails fast without the network until the wait it asked for
     * is over: carrying on, e.g. with the live-match poll, would look like a misbehaving client.
     */
    private suspend fun <T> execute(request: Request, deserializer: DeserializationStrategy<T>): T {
        val wait = coolingDownUntil - clock()
        if (wait > 0) throw RiotHttpException(429, null, "Waiting ${wait / 1000 + 1}s for Riot's rate limit", retryAfterSeconds = wait / 1000 + 1)
        try {
            return http.fetch(json, request, deserializer)
        } catch (e: RiotHttpException) {
            if (e.status == 429) {
                val seconds = (e.retryAfterSeconds ?: 0).coerceIn(MIN_COOL_DOWN_SECONDS, MAX_COOL_DOWN_SECONDS)
                coolingDownUntil = clock() + seconds * 1000
            }
            throw e
        }
    }

    private fun pd(session: RiotSession, path: String) = "https://pd.${session.shard}.a.pvp.net/$path"

    /** Headers the game client sends on every pd call. */
    private fun gameRequest(session: RiotSession, clientVersion: String, url: String, method: String, body: RequestBody?) =
        Request.Builder()
            .url(url)
            .bearer(session.accessToken)
            .header("X-Riot-Entitlements-JWT", session.entitlementsToken)
            .header("X-Riot-ClientPlatform", CLIENT_PLATFORM)
            .header("X-Riot-ClientVersion", clientVersion)
            .method(method, body)
            .build()

    /** Result of a cookie-based silent sign-in: fresh tokens plus any rotated cookies. */
    private class Reauthorization(val tokens: AuthTokens, val setCookies: List<String>)

    private companion object {
        const val TOKEN_MARGIN_MILLIS = 2 * 60 * 1000L
        const val MIN_COOL_DOWN_SECONDS = 60L
        const val MAX_COOL_DOWN_SECONDS = 15 * 60L

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
