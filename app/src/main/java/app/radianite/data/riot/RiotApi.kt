package app.radianite.data.riot

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
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
        execute(
            Request.Builder()
                .url("https://pd.${session.shard}.a.pvp.net/store/v3/storefront/${session.puuid}")
                .bearer(session.accessToken)
                .header("X-Riot-Entitlements-JWT", session.entitlementsToken)
                .header("X-Riot-ClientPlatform", CLIENT_PLATFORM)
                .header("X-Riot-ClientVersion", clientVersion)
                .post(EMPTY_JSON)
                .build(),
        )

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
