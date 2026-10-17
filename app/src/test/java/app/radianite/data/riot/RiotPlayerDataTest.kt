package app.radianite.data.riot

import app.radianite.data.Fixture
import app.radianite.data.Reply
import app.radianite.data.Slots
import app.radianite.data.bearer
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.Collections

class RiotPlayerDataTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val walletTokens: MutableList<String> = Collections.synchronizedList(mutableListOf())

    /** Riot rejects the token "old" and accepts anything else. */
    private fun Fixture.walletAccepting(vararg valid: String) = servers.on("/store/v1/wallet/") { request ->
        val token = request.bearer()!!
        walletTokens += token
        if (token in valid) Reply(body = """{"Balances":{"85ad13f7-3d1b-5128-9eb2-7cd8ee0b5741":1000}}""")
        else Reply(401, """{"errorCode":"BAD_CLAIMS","message":"expired"}""")
    }

    @Test
    fun `renews rejected tokens with the Riot cookies and retries once`() = runTest {
        val app = Fixture(tmp.root).apply { signIn(); walletAccepting("new") }

        val wallet = app.riot.wallet()

        assertEquals(1000, wallet.balances.values.single())
        assertEquals(listOf("old", "new"), walletTokens)
        val renewed = app.riot.savedSession()!!
        assertEquals("new", renewed.accessToken)
        assertEquals("ent-2", renewed.entitlementsToken)
        // Region and shard carry over without another geo lookup.
        assertEquals("latam", renewed.affinity)
        assertEquals("na", renewed.shard)
        assertEquals(0, app.servers.count("riot-geo"))
        assertEquals("new", app.local.read(Slots.session)!!.accessToken)
    }

    @Test
    fun `sends the game client's headers to the account's shard`() = runTest {
        val app = Fixture(tmp.root).apply { signIn(accessToken = "new"); walletAccepting("new") }

        app.riot.wallet()

        val request = app.servers.requests.single { "/store/v1/wallet/" in it.url.toString() }
        assertEquals("https://pd.na.a.pvp.net/store/v1/wallet/puuid-1", request.url.toString())
        assertEquals("release-13.08", request.header("X-Riot-ClientVersion"))
        assertEquals("ent-1", request.header("X-Riot-Entitlements-JWT"))
    }

    @Test
    fun `calls that hit the same rejection renew the session once`() = runTest {
        val app = Fixture(tmp.root).apply { signIn(); walletAccepting("new") }

        List(5) { async { app.riot.wallet() } }.awaitAll()

        assertEquals(1, app.servers.count("auth.riotgames.com/authorize"))
        assertEquals(1, app.servers.count("auth.riotgames.com/userinfo"))
    }

    @Test
    fun `tokens rejected again after renewal expire the session`() = runTest {
        val app = Fixture(tmp.root).apply { signIn(); walletAccepting() }
        val expired = async { app.riot.expired.first() }
        testScheduler.runCurrent()

        val error = runCatching { app.riot.wallet() }.exceptionOrNull()

        assertTrue("was $error", error is SessionExpiredException)
        expired.await()
    }

    @Test
    fun `a plain 403 after renewal stays an error rather than signing out`() = runTest {
        val app = Fixture(tmp.root).apply { signIn() }
        app.servers.on("/store/v1/wallet/") { Reply(403, "{}") }

        val error = runCatching { app.riot.wallet() }.exceptionOrNull()

        assertTrue("was $error", error is RiotHttpException && error.status == 403)
    }

    @Test
    fun `without cookies the session expires and listeners hear about it`() = runTest {
        val app = Fixture(tmp.root).apply { signIn(); walletAccepting("new") }
        app.cookies.header = null
        val expired = async { app.riot.expired.first() }
        testScheduler.runCurrent()

        val error = runCatching { app.riot.wallet() }.exceptionOrNull()

        assertTrue(error is SessionExpiredException)
        expired.await()
    }

    @Test
    fun `signing out forgets the tokens and the cookies`() = runTest {
        val app = Fixture(tmp.root).apply { signIn(accessToken = "new"); walletAccepting("new") }

        app.riot.signOut()

        assertNull(app.riot.savedSession())
        assertNull(app.cookies.header)
        assertTrue(runCatching { app.riot.wallet() }.exceptionOrNull() is SessionExpiredException)
    }

    @Test
    fun `no live match reads as null`() = runTest {
        val app = Fixture(tmp.root).apply { signIn(accessToken = "new") }

        assertNull(app.riot.pregame())
        assertNull(app.riot.coreGame())
    }
}
