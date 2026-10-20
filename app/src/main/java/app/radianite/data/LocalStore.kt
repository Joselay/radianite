package app.radianite.data

import app.radianite.data.account.AccountIndex
import app.radianite.data.content.Catalog
import app.radianite.data.content.GameContent
import app.radianite.data.match.MatchRecord
import app.radianite.data.riot.RiotSession
import app.radianite.data.store.DailyStore
import app.radianite.data.store.FeaturedBundle
import app.radianite.data.store.StoreSkin
import app.radianite.data.store.Wallet
import app.radianite.data.update.UpdateMarks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Where a slot's data lives and so how long: account data sits in that account's own store
 * ([LocalStore.forAccount]) and goes when it signs out; device data sits in the app's store and survives it.
 */
enum class Lifetime { ACCOUNT, DEVICE }

/** One JSON file in the [LocalStore]. */
class Slot<T>(val fileName: String, val serializer: KSerializer<T>, val lifetime: Lifetime)

/** A directory of JSON files keyed by ID, trimmed to the [capacity] most recently written. */
class Shelf<T>(val dirName: String, val serializer: KSerializer<T>, val lifetime: Lifetime, val capacity: Int)

/** Everything the app keeps on disk, and how long each piece lives. */
object Slots {
    val session = Slot("session.json", RiotSession.serializer(), Lifetime.ACCOUNT)
    val cookies = Slot("cookies.json", MapSerializer(String.serializer(), String.serializer()), Lifetime.ACCOUNT)
    val dailyStore = Slot("daily_store.json", DailyStore.serializer(), Lifetime.ACCOUNT)
    val wallet = Slot("wallet.json", Wallet.serializer(), Lifetime.ACCOUNT)

    /** Which accounts are signed in, and which one the app shows. */
    val accounts = Slot("accounts.json", AccountIndex.serializer(), Lifetime.DEVICE)

    /** Skins to watch for, whichever account's store they turn up in. */
    val wishlist = Slot("wishlist.json", ListSerializer(StoreSkin.serializer()), Lifetime.DEVICE)

    /** Stores already alerted about, so each one notifies once. */
    val storeAlerts = Slot("store_alerts.json", ListSerializer(String.serializer()), Lifetime.DEVICE)

    /** App updates already put off or notified about. */
    val updateMarks = Slot("update_marks.json", UpdateMarks.serializer(), Lifetime.DEVICE)

    // Kept across sign-outs for the signed-out welcome screen.
    val featuredBundles = Slot("featured_bundles.json", ListSerializer(FeaturedBundle.serializer()), Lifetime.DEVICE)

    // Content indexes are per game version, not per account.
    val skinCatalog = Slot("skin_catalog.json", Catalog.serializer(), Lifetime.DEVICE)
    val gameContent = Slot("game_content.json", GameContent.serializer(), Lifetime.DEVICE)

    /** One compact file per match; matches never change once played. */
    val matches = Shelf("matches", MatchRecord.serializer(), Lifetime.ACCOUNT, capacity = 200)

    internal val all: List<Slot<*>> = listOf(session, cookies, dailyStore, wallet, accounts, wishlist, storeAlerts, updateMarks, featuredBundles, skinCatalog, gameContent)
    internal val shelves: List<Shelf<*>> = listOf(matches)
}

/** Small JSON files in app-private storage (excluded from backups). Writes are atomic; unreadable files read as null. */
class LocalStore(private val dir: File, private val json: Json) {
    suspend fun <T> read(slot: Slot<T>): T? = read(File(dir, slot.fileName), slot.serializer)

    suspend fun <T> write(slot: Slot<T>, value: T) = write(File(dir, slot.fileName), slot.serializer, value)

    suspend fun delete(slot: Slot<*>) = withContext(Dispatchers.IO) { File(dir, slot.fileName).delete() }

    suspend fun <T> read(shelf: Shelf<T>, key: String): T? = read(shelfFile(shelf, key), shelf.serializer)

    suspend fun <T> write(shelf: Shelf<T>, key: String, value: T) {
        write(shelfFile(shelf, key), shelf.serializer, value)
        prune(shelf)
    }

    /** The store for one account's [Lifetime.ACCOUNT] slots and shelves. */
    fun forAccount(puuid: String) = LocalStore(File(File(dir, ACCOUNTS_DIR), puuid), json)

    /** Deletes everything in this store; for an account's store, when it signs out. */
    suspend fun clear() = withContext(Dispatchers.IO) { dir.deleteRecursively() }

    /**
     * Moves account data that builds before multi-account support kept in the app's store into the
     * account's own store. Returns the session it found there, if any.
     */
    suspend fun migrateSingleAccount(): RiotSession? {
        val session = read(Slots.session) ?: return null
        val target = forAccount(session.puuid).dir
        withContext(Dispatchers.IO) {
            target.mkdirs()
            val names = Slots.all.filter { it.lifetime == Lifetime.ACCOUNT }.map { it.fileName } +
                Slots.shelves.filter { it.lifetime == Lifetime.ACCOUNT }.map { it.dirName }
            // The session moves last: it's what marks the migration as still to do.
            for (name in names.sortedBy { it == Slots.session.fileName }) {
                val from = File(dir, name)
                val to = File(target, name)
                if (!from.exists()) continue
                to.deleteRecursively()
                Files.move(from.toPath(), to.toPath())
            }
        }
        return session
    }

    private companion object {
        const val ACCOUNTS_DIR = "accounts"
    }

    private fun shelfFile(shelf: Shelf<*>, key: String) = File(File(dir, shelf.dirName), "$key.json")

    private suspend fun <T> read(file: File, serializer: KSerializer<T>): T? = withContext(Dispatchers.IO) {
        if (!file.exists()) null else runCatching { json.decodeFromString(serializer, file.readText()) }.getOrNull()
    }

    private suspend fun <T> write(file: File, serializer: KSerializer<T>, value: T) = withContext(Dispatchers.IO) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.encodeToString(serializer, value))
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        Unit
    }

    private suspend fun prune(shelf: Shelf<*>) = withContext(Dispatchers.IO) {
        val files = File(dir, shelf.dirName).listFiles()?.takeIf { it.size > shelf.capacity } ?: return@withContext
        files.sortedByDescending { it.lastModified() }.drop(shelf.capacity).forEach { it.delete() }
    }
}
