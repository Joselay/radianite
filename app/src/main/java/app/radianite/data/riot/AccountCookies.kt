package app.radianite.data.riot

import app.radianite.data.LocalStore
import app.radianite.data.Slots
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * One account's Riot session cookies, kept in its own store so several accounts can stay signed in at once.
 * The sign-in page's cookies are copied in when the account signs in; renewals merge in what Riot rotates.
 */
class AccountCookies(private val store: LocalStore) : RiotCookies {
    private val mutex = Mutex()

    override suspend fun header(): String? = mutex.withLock {
        store.read(Slots.cookies)?.takeIf { it.isNotEmpty() }?.entries?.joinToString("; ") { (name, value) -> "$name=$value" }
    }

    override suspend fun replace(header: String?) = mutex.withLock {
        val jar = parseCookieHeader(header)
        if (jar.isEmpty()) store.delete(Slots.cookies) else store.write(Slots.cookies, jar)
        Unit
    }

    override suspend fun store(setCookies: List<String>) = mutex.withLock {
        if (setCookies.isEmpty()) return@withLock
        val jar = mergeSetCookies(store.read(Slots.cookies).orEmpty(), setCookies)
        store.write(Slots.cookies, jar)
    }

    override suspend fun clear() = mutex.withLock {
        store.delete(Slots.cookies)
        Unit
    }
}

/** "a=1; b=2" as name to value, in order. */
internal fun parseCookieHeader(header: String?): Map<String, String> =
    header.orEmpty().split(';').mapNotNull { pair ->
        val eq = pair.indexOf('=')
        if (eq <= 0) null else pair.substring(0, eq).trim() to pair.substring(eq + 1).trim()
    }.toMap(LinkedHashMap())

/** Applies Set-Cookie headers to [jar]: new values replace old ones, and emptied or expired cookies are dropped. */
internal fun mergeSetCookies(jar: Map<String, String>, setCookies: List<String>, nowMillis: Long = System.currentTimeMillis()): Map<String, String> {
    val merged = LinkedHashMap(jar)
    for (header in setCookies) {
        val parts = header.split(';').map { it.trim() }
        val eq = parts.first().indexOf('=')
        if (eq <= 0) continue
        val name = parts.first().substring(0, eq)
        val value = parts.first().substring(eq + 1)
        val expired = parts.drop(1).any { it.isExpiry(nowMillis) }
        if (value.isEmpty() || expired) merged.remove(name) else merged[name] = value
    }
    return merged
}

/** A Set-Cookie attribute that deletes the cookie: Max-Age of zero or less, or an Expires date already past. */
private fun String.isExpiry(nowMillis: Long): Boolean {
    val (key, value) = split('=', limit = 2).let { it[0].trim() to it.getOrElse(1) { "" }.trim() }
    return when {
        key.equals("Max-Age", ignoreCase = true) -> (value.toLongOrNull() ?: 1) <= 0
        key.equals("Expires", ignoreCase = true) ->
            runCatching { ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() < nowMillis }.getOrDefault(false)
        else -> false
    }
}
