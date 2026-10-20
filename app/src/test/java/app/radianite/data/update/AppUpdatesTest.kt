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

    private fun updates(installed: String = "1.0.0") =
        AppUpdates(servers.client, json, LocalStore(File(tmp.root, "store"), json), File(tmp.root, "updates"), AppVersion.parse(installed)!!, latestUrl = "https://api.github.com/latest")

    private fun publish(tag: String, apk: String? = "radianite-${tag.removePrefix("v")}.apk", size: Long = 4) {
        val assets = apk?.let { """[{"name":"$it","browser_download_url":"https://github.com/dl/$it","size":$size}]""" } ?: "[]"
        servers.json("api.github.com/latest", """{"tag_name":"$tag","name":"Radianite ${tag.removePrefix("v")}","body":"- Show bundles\n","assets":$assets}""")
    }

    @Test
    fun `reads and orders versions`() {
        assertEquals(AppVersion(1, 2, 3), AppVersion.parse("v1.2.3"))
        assertEquals("a development build counts as its release", AppVersion(1, 2, 3), AppVersion.parse("1.2.3-dev+abc1234"))
        assertNull("builds from before versions read 1.2.3", AppVersion.parse("19 (a550b1b)"))
        assertTrue(AppVersion(1, 10, 0) > AppVersion(1, 9, 9))
        assertTrue(AppVersion(2, 0, 0) > AppVersion(1, 99, 99))
        assertEquals("1.2.3", AppVersion(1, 2, 3).toString())
    }

    @Test
    fun `offers a release newer than the installed version`() = runTest {
        publish("v1.1.0")

        val release = updates(installed = "1.0.9").newer()

        assertEquals(AppRelease(AppVersion(1, 1, 0), "Radianite 1.1.0", "- Show bundles", "https://github.com/dl/radianite-1.1.0.apk", 4), release)
    }

    @Test
    fun `nothing to offer when up to date, with no release yet, or for a release that isn't a version`() = runTest {
        publish("v1.0.0")
        assertNull(updates(installed = "1.0.0").newer())
        assertNull("a development build of the latest release", updates(installed = "1.0.0-dev+abc1234").newer())

        servers.on("api.github.com/latest") { Reply(404) }
        assertNull(updates().newer())

        publish("v19")
        assertNull("an old build-number tag", updates().newer())

        publish("v1.2.3-beta")
        assertNull(updates().newer())

        publish("v3.0.0", apk = null)
        assertNull("a release without an APK", updates().newer())
    }

    @Test(expected = IOException::class)
    fun `an unreachable GitHub is an error, not "up to date"`() = runTest {
        servers.on("api.github.com/latest") { Reply(503) }
        updates().newer()
    }

    @Test
    fun `asks until put off, and announces once, per release`() = runTest {
        publish("v1.0.1")
        val updates = updates()
        val first = updates.newer()!!

        assertTrue(updates.shouldAsk(first))
        updates.putOff(first)
        assertFalse(updates.shouldAsk(first))
        assertTrue(updates.firstAnnouncement(first))
        assertFalse(updates.firstAnnouncement(first))

        publish("v1.0.2")
        val next = updates.newer()!!
        assertTrue("a newer release asks again", updates.shouldAsk(next))
        assertTrue(updates.firstAnnouncement(next))
    }

    @Test
    fun `downloads the APK, reuses it, and clears it once installed`() = runTest {
        publish("v1.0.1")
        servers.on("github.com/dl/radianite-1.0.1.apk") { Reply(body = "apk!") }
        val updates = updates()
        val release = updates.newer()!!
        val progress = mutableListOf<Float>()

        val apk = updates.download(release) { progress += it }

        assertEquals("apk!", apk.readText())
        assertEquals(1f, progress.last())
        updates.download(release)
        assertEquals("downloaded once", 1, servers.count("github.com/dl/"))

        // Installed: the same release is now current, so its APK goes.
        updates(installed = "1.0.1").newer()
        assertFalse(apk.exists())
    }

    @Test(expected = IOException::class)
    fun `a cut-off download isn't kept`() = runTest {
        publish("v1.0.1", size = 1000)
        servers.on("github.com/dl/radianite-1.0.1.apk") { Reply(body = "apk!") }
        val updates = updates()
        try {
            updates.download(updates.newer()!!)
        } finally {
            assertEquals(emptyList<String>(), File(tmp.root, "updates").list()?.toList().orEmpty())
        }
    }
}
