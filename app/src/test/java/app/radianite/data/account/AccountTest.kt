package app.radianite.data.account

import app.radianite.data.Failure
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AccountTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun Fixture.account(scope: CoroutineScope) = Account(riot, cookies, local, scope)

    @Test
    fun `restores the saved session`() = runTest {
        val app = Fixture(tmp.root).apply { signIn() }

        val state = app.account(backgroundScope).state.first { it != AccountState.Unknown }

        assertEquals(AccountState.SignedIn("puuid-1", "Me#NA1"), state)
    }

    @Test
    fun `with no saved session the app starts signed out`() = runTest {
        val state = Fixture(tmp.root).account(backgroundScope).state.first { it != AccountState.Unknown }

        assertEquals(AccountState.SignedOut(), state)
    }

    @Test
    fun `completing sign-in keeps the Riot cookies and saves the session`() = runTest {
        val app = Fixture(tmp.root)
        val account = app.account(backgroundScope)

        account.completeSignIn(AuthTokens("fresh", "id", 3600))

        assertEquals(AccountState.SignedIn("puuid-1", "Me#NA1"), account.state.value)
        assertTrue(app.cookies.flushed)
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
    fun `a session found gone anywhere signs out and clears the account's data`() = runTest {
        val app = Fixture(tmp.root).apply { signIn() }
        app.local.write(Slots.dailyStore, DailyStore("puuid-1", emptyList(), expiresAtMillis = Long.MAX_VALUE))
        app.local.write(Slots.featuredBundles, listOf(FeaturedBundle("Reaver", "reaver.png")))
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
        // The welcome screen still has its bundles.
        assertEquals(1, app.local.read(Slots.featuredBundles)?.size)
    }

    @Test
    fun `signing out clears the account's data`() = runTest {
        val app = Fixture(tmp.root).apply { signIn() }
        val account = app.account(backgroundScope)
        account.state.first { it is AccountState.SignedIn }

        account.signOut()

        assertEquals(AccountState.SignedOut(), account.state.value)
        assertNull(app.local.read(Slots.session))
        assertNull(app.cookies.header)
    }
}
