package app.radianite.ui.matches

import app.radianite.data.Fixture
import app.radianite.data.Reply
import app.radianite.data.match.MatchHistory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class MatchesViewModelTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `refreshing while the next page loads leaves paging working`() = runTest {
        val app = Fixture(tmp.root).apply { signIn(accessToken = "new") }
        val secondPageRequested = CountDownLatch(1)
        val releaseSecondPage = CountDownLatch(1)
        val holdSecondPage = AtomicBoolean(true)
        app.servers.on("/match-history/v1/history/") { request ->
            val start = request.url.queryParameter("startIndex")!!.toInt()
            if (start == 10 && holdSecondPage.getAndSet(false)) {
                secondPageRequested.countDown()
                releaseSecondPage.await(5, TimeUnit.SECONDS)
            }
            Reply(body = """{"History":[${(start until start + 10).joinToString(",") { """{"MatchID":"m$it"}""" }}]}""")
        }
        app.servers.on("/match-details/v1/matches/") { request ->
            val id = request.url.pathSegments.last()
            Reply(
                body = """
                    {"matchInfo": {"matchId": "$id", "gameStartMillis": ${1_000_000 - id.drop(1).toInt()}},
                     "players": [{"subject": "puuid-1", "teamId": "Blue"}],
                     "teams": [{"teamId": "Blue", "won": true}, {"teamId": "Red"}]}
                """.trimIndent(),
            )
        }
        val viewModel = MatchesViewModel(MatchHistory(app.riot, app.local))
        viewModel.state.first { !it.refreshing }

        viewModel.loadMore()
        withContext(Dispatchers.IO) { assertTrue(secondPageRequested.await(5, TimeUnit.SECONDS)) }
        viewModel.refresh()
        releaseSecondPage.countDown()
        val refreshed = viewModel.state.first { !it.refreshing }

        assertFalse(refreshed.loadingMore)
        assertEquals(10, refreshed.matches.size)
        viewModel.loadMore()
        assertEquals(20, viewModel.state.first { !it.loadingMore && it.matches.size > 10 }.matches.size)
    }
}
