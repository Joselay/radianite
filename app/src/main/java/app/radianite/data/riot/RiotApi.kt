package app.radianite.data.riot

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Base64

/** Result of a cookie-based silent sign-in: fresh tokens plus any rotated cookies. */
class Reauthorization(val tokens: AuthTokens, val setCookies: List<String>)

/**
 * Riot auth and VALORANT player-data calls. The pd endpoints are the ones the game client
 * uses; they are undocumented and not part of Riot's official developer API.
 */
/** ItemTypeIDs for [RiotApi.ownedItems]. */
object ItemTypes {
    const val AGENTS = "01bb38e1-da47-4e6a-9b3d-945fe4655707"
    const val SKIN_LEVELS = "e7c63390-eda7-46e0-bb7a-a6abdacd2433"
}

class RiotApi(
    private val http: OkHttpClient,
    private val json: Json,
) {
    private val noRedirects = http.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    suspend fun userInfo(accessToken: String): UserInfoResponse =
        execute(
            Request.Builder()
                .url("https://auth.riotgames.com/userinfo")
                .bearer(accessToken)
                .build(),
        )

    suspend fun entitlementsToken(accessToken: String): String =
        execute<EntitlementsResponse>(
            Request.Builder()
                .url("https://entitlements.auth.riotgames.com/api/token/v1")
                .bearer(accessToken)
                .post(EMPTY_JSON)
                .build(),
        ).entitlementsToken

    /** Returns the account's live region affinity, e.g. "ap", "na", "eu". */
    suspend fun liveAffinity(accessToken: String, idToken: String): String {
        val body = """{"id_token":"$idToken"}""".toRequestBody(JSON_MEDIA)
        val response = execute<PasResponse>(
            Request.Builder()
                .url("https://riot-geo.pas.si.riotgames.com/pas/v1/product/valorant")
                .bearer(accessToken)
                .put(body)
                .build(),
        )
        return response.affinities["live"]
            ?: throw RiotHttpException(200, null, "Riot didn't return a region for this account")
    }

    suspend fun storefront(session: RiotSession, clientVersion: String): StorefrontResponse =
        execute(game(session, clientVersion, pd(session, "store/v3/storefront/${session.puuid}")).post(EMPTY_JSON).build())

    suspend fun wallet(session: RiotSession, clientVersion: String): WalletResponse =
        execute(game(session, clientVersion, pd(session, "store/v1/wallet/${session.puuid}")).build())

    /** [itemTypeId] is one of the [ItemTypes] constants. */
    suspend fun ownedItems(session: RiotSession, clientVersion: String, itemTypeId: String): OwnedItemsResponse =
        execute(game(session, clientVersion, pd(session, "store/v1/entitlements/${session.puuid}/$itemTypeId")).build())

    /** [endIndex] is exclusive; Riot caps a page at 20 matches. */
    suspend fun matchHistory(session: RiotSession, clientVersion: String, startIndex: Int, endIndex: Int): MatchHistoryResponse =
        execute(
            game(session, clientVersion, pd(session, "match-history/v1/history/${session.puuid}?startIndex=$startIndex&endIndex=$endIndex"))
                .build(),
        )

    suspend fun matchDetails(session: RiotSession, clientVersion: String, matchId: String): MatchDetailsResponse =
        execute(game(session, clientVersion, pd(session, "match-details/v1/matches/$matchId")).build())

    suspend fun playerMmr(session: RiotSession, clientVersion: String): PlayerMmrResponse =
        execute(game(session, clientVersion, pd(session, "mmr/v1/players/${session.puuid}")).build())

    suspend fun competitiveUpdates(session: RiotSession, clientVersion: String, endIndex: Int): CompetitiveUpdatesResponse =
        execute(
            game(
                session,
                clientVersion,
                pd(session, "mmr/v1/players/${session.puuid}/competitiveupdates?startIndex=0&endIndex=$endIndex&queue=competitive"),
            ).build(),
        )

    suspend fun accountXp(session: RiotSession, clientVersion: String): AccountXpResponse =
        execute(game(session, clientVersion, pd(session, "account-xp/v1/players/${session.puuid}")).build())

    suspend fun playerLoadout(session: RiotSession, clientVersion: String): PlayerLoadoutResponse =
        execute(game(session, clientVersion, pd(session, "personalization/v3/players/${session.puuid}/playerloadout")).build())

    suspend fun contracts(session: RiotSession, clientVersion: String): ContractsResponse =
        execute(game(session, clientVersion, pd(session, "contracts/v1/contracts/${session.puuid}")).build())

    suspend fun leaderboard(session: RiotSession, clientVersion: String, seasonId: String, startIndex: Int, size: Int, query: String?): LeaderboardResponse {
        val url = pd(session, "mmr/v1/leaderboards/affinity/${session.affinity}/queue/competitive/season/$seasonId")
            .toHttpUrl().newBuilder()
            .addQueryParameter("startIndex", startIndex.toString())
            .addQueryParameter("size", size.toString())
            .apply { if (!query.isNullOrBlank()) addQueryParameter("query", query) }
            .build()
        return execute(game(session, clientVersion, url.toString()).build())
    }

    suspend fun names(session: RiotSession, clientVersion: String, puuids: List<String>): List<NameServiceEntry> {
        val body = json.encodeToString(ListSerializer(String.serializer()), puuids).toRequestBody(JSON_MEDIA)
        return execute(game(session, clientVersion, pd(session, "name-service/v2/players")).put(body).build())
    }

    /** The pre-game (agent select) the player is in, or null when they aren't in one. */
    suspend fun pregamePlayer(session: RiotSession, clientVersion: String): LiveMatchPointer? =
        executeOrNullIfMissing(game(session, clientVersion, glz(session, "pregame/v1/players/${session.puuid}")).build())

    suspend fun pregameMatch(session: RiotSession, clientVersion: String, matchId: String): PregameMatchResponse =
        execute(game(session, clientVersion, glz(session, "pregame/v1/matches/$matchId")).build())

    /** The in-progress game the player is in, or null when they aren't in one. */
    suspend fun coreGamePlayer(session: RiotSession, clientVersion: String): LiveMatchPointer? =
        executeOrNullIfMissing(game(session, clientVersion, glz(session, "core-game/v1/players/${session.puuid}")).build())

    suspend fun coreGameMatch(session: RiotSession, clientVersion: String, matchId: String): CoreGameMatchResponse =
        execute(game(session, clientVersion, glz(session, "core-game/v1/matches/$matchId")).build())

    /**
     * Re-runs the authorize request with the WebView's Riot cookies. If the Riot session is
     * still valid, Riot redirects straight to the opt-in URL with fresh tokens.
     */
    suspend fun reauthorize(cookieHeader: String, userAgent: String): Reauthorization? =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(RiotAuth.AUTHORIZE_URL)
                .header("Cookie", cookieHeader)
                .header("User-Agent", userAgent)
                .build()
            noRedirects.newCall(request).execute().use { response ->
                val location = response.header("Location") ?: return@use null
                val tokens = RiotAuth.parseRedirect(location) ?: return@use null
                Reauthorization(tokens, response.headers("Set-Cookie"))
            }
        }

    private suspend inline fun <reified T> execute(request: Request): T =
        withContext(Dispatchers.IO) {
            http.newCall(request).execute().use { response ->
                val body = response.body.string()
                if (!response.isSuccessful) {
                    val error = runCatching { json.decodeFromString<RiotErrorBody>(body) }.getOrNull()
                    throw RiotHttpException(
                        status = response.code,
                        errorCode = error?.errorCode,
                        message = error?.message ?: "HTTP ${response.code} from ${request.url.host}",
                    )
                }
                json.decodeFromString<T>(body)
            }
        }

    /** Riot answers 404 (RESOURCE_NOT_FOUND) when the player has no live match of that kind. */
    private suspend inline fun <reified T> executeOrNullIfMissing(request: Request): T? = try {
        execute<T>(request)
    } catch (e: RiotHttpException) {
        if (e.status == 404) null else throw e
    }

    private fun pd(session: RiotSession, path: String) = "https://pd.${session.shard}.a.pvp.net/$path"

    private fun glz(session: RiotSession, path: String) = "https://glz-${session.affinity}-1.${session.shard}.a.pvp.net/$path"

    /** Headers the game client sends on every pd/glz call. */
    private fun game(session: RiotSession, clientVersion: String, url: String) = Request.Builder()
        .url(url)
        .bearer(session.accessToken)
        .header("X-Riot-Entitlements-JWT", session.entitlementsToken)
        .header("X-Riot-ClientPlatform", CLIENT_PLATFORM)
        .header("X-Riot-ClientVersion", clientVersion)

    private fun Request.Builder.bearer(token: String) = header("Authorization", "Bearer $token")

    private companion object {
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
