package app.radianite.data.update

import app.radianite.data.FakeServers
import app.radianite.data.LocalStore
import app.radianite.data.Reply
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class AppUpdatesTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val servers = FakeServers()

    private fun updates(installed: Int = 18) =
        AppUpdates(servers.client, json, LocalStore(File(tmp.root, "store"), json), File(tmp.root, "updates"), installed, latestUrl = "https://api.github.com/latest")

    private fun publish(tag: String, apk: String? = "radianite-$tag.apk", size: Long = 4) {
        val assets = apk?.let { """[{"name":"$it","browser_download_url":"https://github.com/dl/$it","size":$size}]""" } ?: "[]"
        servers.json("api.github.com/latest", """{"tag_name":"$tag","name":"Radianite $tag","body":"- Show bundles\n","assets":$assets}""")
    }

    @Test
    fun `offers a release newer than the installed build`() = runTest {
        publish("v20")

        val release = updates(installed = 18).newer()

        assertEquals(AppRelease(20, "Radianite v20", "- Show bundles", "https://github.com/dl/radianite-v20.apk", 4), release)
    }

    @Test
    fun `nothing to offer when up to date, with no release yet, or for a release that isn't a build`() = runTest {
        publish("v18")
        assertNull(updates(installed = 18).newer())

        servers.on("api.github.com/latest") { Reply(404) }
        assertNull(updates().newer())

        publish("nightly")
        assertNull(updates().newer())

        publish("v30", apk = null)
        assertNull("a release without an APK", updates().newer())
    }

    @Test(expected = IOException::class)
    fun `an unreachable GitHub is an error, not "up to date"`() = runTest {
        servers.on("api.github.com/latest") { Reply(503) }
        updates().newer()
    }

    @Test
    fun `asks until put off, and announces once, per release`() = runTest {
        publish("v20")
        val updates = updates()
        val v20 = updates.newer()!!

        assertTrue(updates.shouldAsk(v20))
        updates.putOff(v20)
        assertFalse(updates.shouldAsk(v20))
        assertTrue(updates.firstAnnouncement(v20))
        assertFalse(updates.firstAnnouncement(v20))

        publish("v21")
        val v21 = updates.newer()!!
        assertTrue("a newer release asks again", updates.shouldAsk(v21))
        assertTrue(updates.firstAnnouncement(v21))
    }

    @Test
    fun `downloads the APK, reuses it, and clears it once installed`() = runTest {
        publish("v20")
        servers.on("github.com/dl/radianite-v20.apk") { Reply(body = "apk!") }
        val updates = updates()
        val release = updates.newer()!!
        val progress = mutableListOf<Float>()

        val apk = updates.download(release) { progress += it }

        assertEquals("apk!", apk.readText())
        assertEquals(1f, progress.last())
        updates.download(release)
        assertEquals("downloaded once", 1, servers.count("github.com/dl/"))

        // Installed: the same release is now current, so its APK goes.
        updates(installed = 20).newer()
        assertFalse(apk.exists())
    }

    @Test(expected = IOException::class)
    fun `a cut-off download isn't kept`() = runTest {
        publish("v20", size = 1000)
        servers.on("github.com/dl/radianite-v20.apk") { Reply(body = "apk!") }
        val updates = updates()
        try {
            updates.download(updates.newer()!!)
        } finally {
            assertEquals(emptyList<String>(), File(tmp.root, "updates").list()?.toList().orEmpty())
        }
    }
}
