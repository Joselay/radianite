package app.radianite.data

import app.radianite.data.account.SignInCookies
import app.radianite.data.content.Content
import app.radianite.data.riot.RiotCookies
import app.radianite.data.riot.RiotPlayerData
import app.radianite.data.riot.RiotSession
import app.radianite.data.riot.RiotSignIn
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.File
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

class Reply(val code: Int = 200, val body: String = "{}", val location: String? = null, val retryAfter: Long? = null)

/**
 * Stands in for Riot and valorant-api.com at the OkHttp seam. A request goes to the route with the longest key
 * its URL contains; unrouted requests get a 404.
 */
class FakeServers {
    private val routes = ConcurrentHashMap<String, (Request) -> Reply>()
    val requests: MutableList<Request> = Collections.synchronizedList(mutableListOf())

    fun on(urlPart: String, reply: (Request) -> Reply) {
        routes[urlPart] = reply
    }

    fun json(urlPart: String, body: String) = on(urlPart) { Reply(body = body) }

    fun count(urlPart: String) = synchronized(requests) { requests.count { urlPart in it.url.toString() } }

    val client: OkHttpClient = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
        val request = chain.request()
        requests += request
        val url = request.url.toString()
        val reply = routes.keys.filter { it in url }.maxByOrNull { it.length }?.let { routes.getValue(it)(request) } ?: Reply(404)
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(reply.code)
            .message("")
            .apply { if (reply.location != null) header("Location", reply.location) }
            .apply { if (reply.retryAfter != null) header("Retry-After", reply.retryAfter.toString()) }
            .body(reply.body.toResponseBody())
            .build()
    }).build()
}

class FakeCookies(@Volatile var header: String? = "ssid=abc") : RiotCookies {
    override suspend fun header() = header
    override suspend fun replace(header: String?) {
        this.header = header
    }
    override suspend fun store(setCookies: List<String>) = Unit
    override suspend fun clear() {
        header = null
    }
}

/** The sign-in page's cookie store, holding [header] until it's taken. */
class FakeSignInCookies(@Volatile var header: String? = "ssid=page") : SignInCookies {
    override suspend fun take(): String? = header.also { header = null }
    override suspend fun clear() {
        header = null
    }
}

/**
 * A signed-in-able app graph over [FakeServers]: valorant-api.com serves version release-13.08 and empty
 * indexes, and a cookie reauthorization hands out the access token "new". [riot] and [local] are the
 * account "puuid-1"'s; [riotFor] gives any other account its own.
 */
class Fixture(dir: File) {
    val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    val servers = FakeServers()
    val cookies = FakeCookies()
    val device = LocalStore(dir, json)
    val local = device.forAccount("puuid-1")

    @Volatile
    var now = 1_000_000L

    val content = Content(servers.client, json, device, clock = { now })
    val riotSignIn = RiotSignIn(servers.client, json, clock = { now })
    val riot = riotOver(local, cookies)

    private val others = ConcurrentHashMap<String, RiotPlayerData>()
    private val otherCookies = ConcurrentHashMap<String, FakeCookies>()

    fun riotFor(puuid: String): RiotPlayerData =
        if (puuid == "puuid-1") riot else others.computeIfAbsent(puuid) { riotOver(device.forAccount(it), cookiesFor(it)) }

    fun cookiesFor(puuid: String): FakeCookies = if (puuid == "puuid-1") cookies else otherCookies.computeIfAbsent(puuid) { FakeCookies() }

    private fun riotOver(store: LocalStore, cookies: RiotCookies) =
        RiotPlayerData(servers.client, json, cookies, store, riotSignIn, userAgent = { "test" }, clientVersion = content::version, clock = { now })

    init {
        servers.json("valorant-api.com/v1/version", """{"data":{"riotClientVersion":"release-13.08"}}""")
        for (index in listOf("weapons", "contenttiers", "bundles", "agents", "maps")) {
            servers.json("valorant-api.com/v1/$index", """{"data":[]}""")
        }
        servers.on("auth.riotgames.com/authorize") {
            Reply(303, location = "https://playvalorant.com/opt_in#access_token=new&id_token=id2&expires_in=3600")
        }
        servers.json("auth.riotgames.com/userinfo", """{"sub":"puuid-1","acct":{"game_name":"Me","tag_line":"NA1"}}""")
        servers.json("entitlements.auth.riotgames.com", """{"entitlements_token":"ent-2"}""")
        servers.json("riot-geo.pas.si.riotgames.com", """{"affinities":{"live":"na"}}""")
    }

    /** Saves a session for [puuid] in its own store; it's the shown account only if [Account] is told so. */
    suspend fun signIn(puuid: String = "puuid-1", accessToken: String = "old", gameName: String = "Me") {
        device.forAccount(puuid).write(Slots.session, session(puuid, accessToken, gameName))
    }

    fun session(puuid: String = "puuid-1", accessToken: String = "old", gameName: String = "Me") =
        RiotSession(accessToken, "id1", expiresAtMillis = Long.MAX_VALUE, entitlementsToken = "ent-1", puuid = puuid, gameName = gameName, tagLine = "NA1", shard = "na", region = "latam")
}

fun Request.bearer(): String? = header("Authorization")?.removePrefix("Bearer ")
