package app.radianite.data.riot

import app.radianite.data.AppFiles
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SessionManagerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val walletCalls = mutableListOf<String>()

    /** Stands in for Riot: the "old" token is rejected; a cookie reauth hands out "new". */
    private val fakeRiot = Interceptor { chain ->
        val request = chain.request()
        val url = request.url.toString()
        fun respond(code: Int, body: String = "{}", location: String? = null) = Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("")
            .apply { if (location != null) header("Location", location) }
            .body(body.toResponseBody())
            .build()
        when {
            url.startsWith(RiotAuth.AUTHORIZE_URL.substringBefore('?')) ->
                respond(303, location = "https://playvalorant.com/opt_in#access_token=new&id_token=id2&expires_in=3600")
            url == "https://auth.riotgames.com/userinfo" -> respond(200, """{"sub":"puuid-1","acct":{"game_name":"Me","tag_line":"NA1"}}""")
            url.startsWith("https://entitlements.auth.riotgames.com") -> respond(200, """{"entitlements_token":"ent-2"}""")
            url.contains("/store/v1/wallet/") -> {
                val token = request.header("Authorization")!!.removePrefix("Bearer ")
                walletCalls += token
                if (token == "new") respond(200, """{"Balances":{"85ad13f7-3d1b-5128-9eb2-7cd8ee0b5741":1000}}""")
                else respond(401, """{"errorCode":"BAD_CLAIMS","message":"expired"}""")
            }
            else -> respond(404)
        }
    }

    private val riot = RiotApi(OkHttpClient.Builder().addInterceptor(fakeRiot).build(), json)

    private class FakeCookies(var header: String?) : RiotCookies {
        val stored = mutableListOf<String>()
        override suspend fun header() = header
        override suspend fun store(setCookies: List<String>) { stored += setCookies }
        override suspend fun clear() { header = null }
    }

    private suspend fun manager(cookies: RiotCookies, files: AppFiles): SessionManager {
        files.write(
            files.session,
            RiotSession.serializer(),
            RiotSession("old", "id1", expiresAtMillis = Long.MAX_VALUE, entitlementsToken = "ent-1", puuid = "puuid-1", gameName = "Me", tagLine = "NA1", shard = "na", region = "latam"),
        )
        return SessionManager(
            riot = riot,
            cookies = cookies,
            files = files,
            userAgent = { "test" },
            fetchClientVersion = { "release-13.08" },
        )
    }

    @Test
    fun `renews rejected tokens with the Riot cookies and retries once`() = runTest {
        val files = AppFiles(tmp.root, json)
        val sessions = manager(FakeCookies("ssid=abc"), files)

        val wallet = sessions.call { session, version -> riot.wallet(session, version) }

        assertEquals(1000, wallet.balances.values.single())
        assertEquals(listOf("old", "new"), walletCalls)
        val renewed = sessions.savedSession()!!
        assertEquals("new", renewed.accessToken)
        assertEquals("ent-2", renewed.entitlementsToken)
        // Region and shard carry over without another geo lookup.
        assertEquals("latam", renewed.affinity)
        assertEquals("na", renewed.shard)
        assertEquals("new", files.read(files.session, RiotSession.serializer())!!.accessToken)
    }

    @Test
    fun `without cookies the session expires and listeners hear about it`() = runTest {
        val sessions = manager(FakeCookies(header = null), AppFiles(tmp.root, json))
        val expired = async { sessions.expired.first() }
        testScheduler.runCurrent()

        val error = runCatching {
            sessions.call { session, version -> riot.wallet(session, version) }
        }.exceptionOrNull()

        assertTrue(error is SessionExpiredException)
        expired.await()
    }
}
