package app.radianite.data.content

import app.radianite.data.Fixture
import app.radianite.data.Reply
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ContentTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val reaver = """{"data":[{"uuid":"reaver","displayName":"Reaver Vandal","levels":[{"uuid":"LEVEL-1"}]}]}"""

    private fun Fixture.version(v: String) = servers.json("valorant-api.com/v1/version", """{"data":{"riotClientVersion":"$v"}}""")

    @Test
    fun `an index downloads once per version and is reused from disk`() = runTest {
        val app = Fixture(tmp.root)
        app.servers.json("valorant-api.com/v1/weapons/skins", reaver)

        app.content.catalog()
        app.content.catalog()
        // A fresh start reads the index back from disk.
        val restarted = Fixture(tmp.root).also { it.servers.json("valorant-api.com/v1/weapons/skins", reaver) }
        val catalog = restarted.content.catalog()

        assertEquals(1, app.servers.count("valorant-api.com/v1/weapons/skins"))
        assertEquals(0, restarted.servers.count("valorant-api.com/v1/weapons/skins"))
        assertEquals("Reaver Vandal", catalog.skinsByLevel["level-1"]?.name)
    }

    @Test
    fun `a new patch rebuilds the index`() = runTest {
        val app = Fixture(tmp.root)
        app.content.catalog()
        app.version("release-13.09")
        app.now += 60 * 60 * 1000

        assertEquals("release-13.09", app.content.catalog().version)
        assertEquals(2, app.servers.count("valorant-api.com/v1/weapons/skins"))
    }

    @Test
    fun `when the new patch's skins can't be downloaded, the last catalog stands in`() = runTest {
        val app = Fixture(tmp.root)
        app.servers.json("valorant-api.com/v1/weapons/skins", reaver)
        app.content.catalog()

        app.version("release-13.09")
        app.now += 60 * 60 * 1000
        app.servers.on("valorant-api.com/v1/weapons/skins") { Reply(503) }

        val catalog = app.content.catalog()
        assertEquals("release-13.08", catalog.version)
        assertNotNull(catalog.skinsByLevel["level-1"])
    }

    @Test
    fun `game content falls back the same way`() = runTest {
        val app = Fixture(tmp.root)
        app.content.gameContent()
        app.version("release-13.09")
        app.now += 60 * 60 * 1000
        app.servers.on("valorant-api.com/v1/agents") { Reply(503) }

        assertEquals("release-13.08", app.content.gameContent().version)
        assertEquals("release-13.08", app.content.game.value?.version)
    }

    @Test
    fun `offline, the version comes from the last index`() = runTest {
        Fixture(tmp.root).content.catalog()
        val offline = Fixture(tmp.root)
        offline.servers.on("valorant-api.com/v1/version") { Reply(503) }

        assertEquals("release-13.08", offline.content.version())
    }

    @Test
    fun `the version is reused for a while`() = runTest {
        val app = Fixture(tmp.root)
        app.content.version()
        app.now += 60 * 1000
        app.content.version()

        assertEquals(1, app.servers.count("valorant-api.com/v1/version"))
    }
}
