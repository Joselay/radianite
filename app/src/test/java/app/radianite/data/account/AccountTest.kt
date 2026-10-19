package app.radianite.data.account

import app.radianite.data.Failure
import app.radianite.data.FakeSignInCookies
import app.radianite.data.Fixture
import app.radianite.data.Reply
import app.radianite.data.Slots
import app.radianite.data.riot.AuthTokens
import app.radianite.data.store.DailyStore
import app.radianite.data.store.FeaturedBundle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AccountTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val web = FakeSignInCookies()

    private fun Fixture.account(scope: CoroutineScope) = Account(device, riotSignIn, web, ::riotFor, scope)

    /** Saves sessions for [puuids], showing the first, as if each had signed in. */
    private suspend fun Fixture.signedIn(vararg puuids: String) {
        puuids.forEach { signIn(puuid = it, gameName = it) }
        device.write(Slots.accounts, AccountIndex(active = puuids.first(), accounts = puuids.map { SavedAccount(it, "$it#NA1") }))
    }

    private fun Fixture.userInfo(puuid: String, name: String) =
        servers.json("auth.riotgames.com/userinfo", """{"sub":"$puuid","acct":{"game_name":"$name","tag_line":"NA1"}}""")

    @Test
    fun `restores the shown account`() = runTest {
        val app = Fixture(tmp.root).apply { signedIn("puuid-1", "puuid-2") }

        val account = app.account(backgroundScope)
        val state = account.state.first { it != AccountState.Unknown }

        assertEquals(AccountState.SignedIn("puuid-1", "puuid-1#NA1"), state)
        assertEquals(listOf("puuid-1", "puuid-2"), account.saved.value.map { it.puuid })
    }

    @Test
    fun `with no saved account the app starts signed out`() = runTest {
        val state = Fixture(tmp.root).account(backgroundScope).state.first { it != AccountState.Unknown }

        assertEquals(AccountState.SignedOut(), state)
    }

    @Test
    fun `completing sign-in takes the sign-in page's cookies into the account and saves its session`() = runTest {
        val app = Fixture(tmp.root)
        val account = app.account(backgroundScope)

        account.completeSignIn(AuthTokens("fresh", "id", 3600))

        assertEquals(AccountState.SignedIn("puuid-1", "Me#NA1"), account.state.value)
        assertEquals(listOf(SavedAccount("puuid-1", "Me#NA1")), account.saved.value)
        assertEquals("ssid=page", app.cookies.header)
        assertNull("the page starts empty for the next sign-in", web.header)
        assertEquals("fresh", app.local.read(Slots.session)?.accessToken)
    }

    @Test
    fun `a failed sign-in says why`() = runTest {
        val app = Fixture(tmp.root)
        app.servers.on("auth.riotgames.com/userinfo") { Reply(503) }
        val account = app.account(backgroundScope)

        account.completeSignIn(AuthTokens("fresh", "id", 3600))

        assertEquals(AccountState.SignedOut(Failure.Maintenance), account.state.value)
    }

    @Test
    fun `a second account signs in alongside the first, and the app can switch back`() = runTest {
        val app = Fixture(tmp.root)
        val account = app.account(backgroundScope)
        account.completeSignIn(AuthTokens("first", "id", 3600))

        app.userInfo("puuid-2", "Alt")
        account.prepareSignIn()
        web.header = "ssid=alt"
        account.completeSignIn(AuthTokens("second", "id", 3600))

        assertEquals(AccountState.SignedIn("puuid-2", "Alt#NA1"), account.state.value)
        assertEquals(listOf("Me#NA1", "Alt#NA1"), account.saved.value.map { it.riotId })
        assertEquals("ssid=alt", app.cookiesFor("puuid-2").header)
        assertEquals("ssid=page", app.cookies.header)

        account.switchTo("puuid-1")

        assertEquals(AccountState.SignedIn("puuid-1", "Me#NA1"), account.state.value)
        assertEquals("first", app.riot.savedSession()?.accessToken)
        assertEquals("puuid-1", app.device.read(Slots.accounts)?.active)
    }

    @Test
    fun `signing in an account again replaces its session rather than adding it twice`() = runTest {
        val app = Fixture(tmp.root)
        val account = app.account(backgroundScope)
        account.completeSignIn(AuthTokens("first", "id", 3600))

        account.completeSignIn(AuthTokens("again", "id", 3600))

        assertEquals(1, account.saved.value.size)
        assertEquals("again", app.riot.savedSession()?.accessToken)
    }

    @Test
    fun `signing out moves on to the next account and clears only the one signed out`() = runTest {
        val app = Fixture(tmp.root).apply { signedIn("puuid-1", "puuid-2") }
        app.local.write(Slots.dailyStore, DailyStore("puuid-1", emptyList(), expiresAtMillis = Long.MAX_VALUE))
        val account = app.account(backgroundScope)
        account.ready()

        account.signOut()

        assertEquals(AccountState.SignedIn("puuid-2", "puuid-2#NA1"), account.state.value)
        assertEquals(listOf("puuid-2"), account.saved.value.map { it.puuid })
        assertNull(app.local.read(Slots.session))
        assertNull(app.local.read(Slots.dailyStore))
        assertNull(app.cookies.header)
        assertNotNull(app.device.forAccount("puuid-2").read(Slots.session))
    }

    @Test
    fun `signing out the last account returns to sign-in`() = runTest {
        val app = Fixture(tmp.root).apply { signedIn("puuid-1") }
        val account = app.account(backgroundScope)
        account.ready()

        account.signOut()

        assertEquals(AccountState.SignedOut(), account.state.value)
        assertEquals(emptyList<SavedAccount>(), account.saved.value)
        assertNull(app.local.read(Slots.session))
    }

    @Test
    fun `a session found gone anywhere signs that account out, keeping the others`() = runTest {
        val app = Fixture(tmp.root).apply { signedIn("puuid-1", "puuid-2") }
        app.local.write(Slots.dailyStore, DailyStore("puuid-1", emptyList(), expiresAtMillis = Long.MAX_VALUE))
        app.device.write(Slots.featuredBundles, listOf(FeaturedBundle("Reaver", "reaver.png")))
        // Riot rejects the tokens, and without cookies they can't be renewed.
        app.servers.on("/store/v1/wallet/") { Reply(401) }
        app.cookies.header = null
        val account = app.account(backgroundScope)
        account.state.first { it is AccountState.SignedIn }

        runCatching { app.riot.wallet() }
        val state = account.state.first { it is AccountState.SignedOut }

        assertEquals(AccountState.SignedOut(Failure.Expired), state)
        assertNull(app.local.read(Slots.session))
        assertNull(app.local.read(Slots.dailyStore))
        assertEquals("the other account can still be picked", listOf("puuid-2"), account.saved.value.map { it.puuid })
        // The welcome screen still has its bundles.
        assertEquals(1, app.device.read(Slots.featuredBundles)?.size)
    }

    @Test
    fun `an account signed in by an older build moves into its own store, cookies and all`() = runTest {
        val app = Fixture(tmp.root)
        app.device.write(Slots.session, app.session(accessToken = "legacy"))
        app.device.write(Slots.dailyStore, DailyStore("puuid-1", emptyList(), expiresAtMillis = Long.MAX_VALUE))
        web.header = "ssid=legacy"
        val account = app.account(backgroundScope)

        val state = account.state.first { it != AccountState.Unknown }

        assertEquals(AccountState.SignedIn("puuid-1", "Me#NA1"), state)
        assertEquals("legacy", app.local.read(Slots.session)?.accessToken)
        assertNotNull(app.local.read(Slots.dailyStore))
        assertNull(app.device.read(Slots.session))
        assertEquals("ssid=legacy", app.cookies.header)
        assertEquals(AccountIndex("puuid-1", listOf(SavedAccount("puuid-1", "Me#NA1"))), app.device.read(Slots.accounts))
    }
}
