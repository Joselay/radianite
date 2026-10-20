package app.radianite.data.update

import app.radianite.data.LocalStore
import app.radianite.data.Slots
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

/** A version of the app, as in "1.2.3"; newer versions compare greater. */
data class AppVersion(val major: Int, val minor: Int, val patch: Int) : Comparable<AppVersion> {
    override fun compareTo(other: AppVersion) = compareValuesBy(this, other, AppVersion::major, AppVersion::minor, AppVersion::patch)

    override fun toString() = "$major.$minor.$patch"

    companion object {
        private val PATTERN = Regex("""^v?(\d+)\.(\d+)\.(\d+)""")

        /** Reads "1.2.3" or "v1.2.3", ignoring anything after, like "-dev+abc1234"; null for anything else. */
        fun parse(text: String): AppVersion? =
            PATTERN.find(text)?.destructured?.let { (major, minor, patch) -> AppVersion(major.toInt(), minor.toInt(), patch.toInt()) }
    }
}

/** A published version of the app: [notes] says what changed. */
data class AppRelease(
    val version: AppVersion,
    val title: String,
    val notes: String,
    val apkUrl: String,
    /** 0 when GitHub didn't say. */
    val apkBytes: Long,
)

/** The versions the user last put off, and was notified about, so each release asks and notifies once. */
@Serializable
data class UpdateMarks(val dismissed: String? = null, val announced: String? = null)

/**
 * New versions of the app, published as GitHub releases of the app's repository, each tagged with its version
 * ("v1.2.3") and carrying the signed APK. Finds a release newer than the installed version, remembers which ones
 * the user put off or was told about, and downloads the APK for Android's installer.
 */
class AppUpdates(
    private val http: OkHttpClient,
    private val json: Json,
    private val device: LocalStore,
    /** Where APKs download to; only ever holds the newest one. */
    private val downloads: File,
    private val installedVersion: AppVersion,
    private val latestUrl: String = LATEST_RELEASE_URL,
) {
    /** The newest release, when it's newer than the installed version. Throws when GitHub can't be reached. */
    suspend fun newer(): AppRelease? {
        val release = latest()?.takeIf { it.version > installedVersion }
        // Only the newer build's APK is worth keeping; one for a build that's now installed has done its job.
        val keep = apkFile(release)?.name
        withContext(Dispatchers.IO) { downloads.listFiles()?.filterNot { keep != null && it.name.startsWith(keep) }?.forEach { it.delete() } }
        return release
    }

    /** Whether [release] should still ask to be installed: it does until the user puts it off. */
    suspend fun shouldAsk(release: AppRelease) = !release.isAtMost(marks().dismissed)

    suspend fun putOff(release: AppRelease) = device.write(Slots.updateMarks, marks().copy(dismissed = release.version.toString()))

    /** True the first time it's called for [release], so a release is notified about once. */
    suspend fun firstAnnouncement(release: AppRelease): Boolean {
        val marks = marks()
        if (release.isAtMost(marks.announced)) return false
        device.write(Slots.updateMarks, marks.copy(announced = release.version.toString()))
        return true
    }

    /** Downloads [release]'s APK, or reuses the one already downloaded, reporting the fraction done when the size is known. */
    suspend fun download(release: AppRelease, onProgress: (Float) -> Unit = {}): File = withContext(Dispatchers.IO) {
        val file = checkNotNull(apkFile(release))
        if (release.apkBytes > 0 && file.length() == release.apkBytes) return@withContext file
        downloads.mkdirs()
        val partial = File(downloads, file.name + ".part")
        http.newCall(Request.Builder().url(release.apkUrl).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} downloading the update")
            val total = response.body.contentLength().takeIf { it > 0 } ?: release.apkBytes
            response.body.byteStream().use { input ->
                partial.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        done += read
                        if (total > 0) onProgress((done.toFloat() / total).coerceAtMost(1f))
                    }
                }
            }
        }
        if (release.apkBytes > 0 && partial.length() != release.apkBytes) {
            partial.delete()
            throw IOException("The update didn't download completely.")
        }
        if (!partial.renameTo(file)) throw IOException("Couldn't save the update.")
        file
    }

    private suspend fun latest(): AppRelease? = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(latestUrl).header("Accept", "application/vnd.github+json").build()
        http.newCall(request).execute().use { response ->
            // No release published yet.
            if (response.code == 404) return@withContext null
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} from GitHub")
            json.decodeFromString(GitHubRelease.serializer(), response.body.string()).toAppRelease()
        }
    }

    private suspend fun marks() = device.read(Slots.updateMarks) ?: UpdateMarks()

    private fun apkFile(release: AppRelease?) = release?.let { File(downloads, "radianite-${it.version}.apk") }

    private fun AppRelease.isAtMost(marked: String?) = marked?.let(AppVersion::parse)?.let { version <= it } ?: false

    companion object {
        const val LATEST_RELEASE_URL = "https://api.github.com/repos/Joselay/radianite/releases/latest"
    }
}

@Serializable
data class GitHubRelease(
    @SerialName("tag_name") val tagName: String,
    val name: String? = null,
    val body: String? = null,
    val assets: List<GitHubAsset> = emptyList(),
)

@Serializable
data class GitHubAsset(
    val name: String,
    @SerialName("browser_download_url") val downloadUrl: String,
    val size: Long = 0,
)

/** Null for a release that isn't one of the app's versions: a tag other than "v1.2.3", or no APK. */
fun GitHubRelease.toAppRelease(): AppRelease? {
    val version = AppVersion.parse(tagName)?.takeIf { tagName == "v$it" } ?: return null
    val apk = assets.firstOrNull { it.name.endsWith(".apk") } ?: return null
    return AppRelease(
        version = version,
        title = name?.takeIf { it.isNotBlank() } ?: "Radianite $version",
        notes = body.orEmpty().trim(),
        apkUrl = apk.downloadUrl,
        apkBytes = apk.size,
    )
}
