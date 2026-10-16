package app.radianite.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import java.io.File

/** Small JSON files in app-private storage (excluded from backups). */
class AppFiles(dir: File, private val json: Json) {
    val session = File(dir, "session.json")
    val dailyStore = File(dir, "daily_store.json")

    /** One compact file per match; matches never change once played. */
    val matches = File(dir, "matches")

    // Not account data, so clear() leaves it for the signed-out welcome screen.
    val featuredBundles = File(dir, "featured_bundles.json")

    suspend fun <T> read(file: File, serializer: KSerializer<T>): T? = withContext(Dispatchers.IO) {
        if (!file.exists()) null else runCatching { json.decodeFromString(serializer, file.readText()) }.getOrNull()
    }

    suspend fun <T> write(file: File, serializer: KSerializer<T>, value: T) = withContext(Dispatchers.IO) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.encodeToString(serializer, value))
        tmp.renameTo(file)
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        session.delete()
        dailyStore.delete()
        matches.deleteRecursively()
    }
}
