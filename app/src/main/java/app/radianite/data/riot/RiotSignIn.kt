package app.radianite.data.riot

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Turns tokens from Riot's sign-in redirect into a [RiotSession]: who the account is, its entitlements
 * token and its region. Used for a new sign-in, before the account has anywhere to keep its data, and by
 * [RiotPlayerData] whenever it renews an account's tokens.
 */
class RiotSignIn(
    private val http: OkHttpClient,
    private val json: Json,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** [previous] is the session being renewed; its shard and region carry over without another geo lookup. */
    suspend fun session(tokens: AuthTokens, previous: RiotSession? = null): RiotSession = coroutineScope {
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
        )
    }

    private suspend fun userInfo(accessToken: String): UserInfoResponse =
        http.fetch(json, Request.Builder().url("https://auth.riotgames.com/userinfo").bearer(accessToken).build(), UserInfoResponse.serializer())

    private suspend fun entitlementsToken(accessToken: String): String =
        http.fetch(
            json,
            Request.Builder().url("https://entitlements.auth.riotgames.com/api/token/v1").bearer(accessToken).post("{}".toRequestBody(JSON_MEDIA)).build(),
            EntitlementsResponse.serializer(),
        ).entitlementsToken

    /** The account's live region affinity, e.g. "ap", "na", "eu". */
    private suspend fun liveAffinity(accessToken: String, idToken: String): String {
        val body = """{"id_token":"$idToken"}""".toRequestBody(JSON_MEDIA)
        val response = http.fetch(
            json,
            Request.Builder().url("https://riot-geo.pas.si.riotgames.com/pas/v1/product/valorant").bearer(accessToken).put(body).build(),
            PasResponse.serializer(),
        )
        return response.affinities["live"] ?: throw RiotHttpException(200, null, "Riot didn't return a region for this account")
    }
}

internal val JSON_MEDIA = "application/json".toMediaType()

internal fun Request.Builder.bearer(token: String) = header("Authorization", "Bearer $token")

/** Sends [request] and decodes the JSON reply; a non-2xx reply becomes a [RiotHttpException] carrying Riot's error code. */
internal suspend fun <T> OkHttpClient.fetch(json: Json, request: Request, deserializer: DeserializationStrategy<T>): T = withContext(Dispatchers.IO) {
    newCall(request).execute().use { response ->
        val body = response.body.string()
        if (!response.isSuccessful) {
            val error = runCatching { json.decodeFromString(RiotErrorBody.serializer(), body) }.getOrNull()
            throw RiotHttpException(
                status = response.code,
                errorCode = error?.errorCode,
                message = error?.message ?: "HTTP ${response.code} from ${request.url.host}",
                retryAfterSeconds = response.header("Retry-After")?.toLongOrNull(),
            )
        }
        json.decodeFromString(deserializer, body)
    }
}
