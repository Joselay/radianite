package app.radianite.data

import android.util.Log
import app.radianite.data.riot.RiotHttpException
import app.radianite.data.riot.SessionExpiredException
import kotlinx.coroutines.CancellationException
import java.io.IOException

/** Why a load failed, in the terms the app reacts to. */
sealed interface Failure {
    /** The Riot session is gone; the Account session returns the app to sign-in. */
    data object Expired : Failure
    data object Maintenance : Failure
    data object RateLimited : Failure
    data object Offline : Failure

    /** Riot refused the request for another reason; [code] is its error code or HTTP status. */
    data class Riot(val code: String) : Failure
    data class Other(val message: String?) : Failure
}

fun Throwable.toFailure(): Failure = when {
    this is SessionExpiredException -> Failure.Expired
    this is RiotHttpException && isMaintenance -> Failure.Maintenance
    this is RiotHttpException && status == 429 -> Failure.RateLimited
    this is RiotHttpException -> Failure.Riot(errorCode ?: status.toString())
    this is IOException -> Failure.Offline
    else -> Failure.Other(message)
}

/**
 * Runs a best-effort part of a load: null when it fails, so the rest can still show. Cancellation and an
 * expired session always reach the caller, since neither is something a section can hide.
 */
suspend fun <T> optional(label: String, block: suspend () -> T): T? = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: SessionExpiredException) {
    throw e
} catch (e: Exception) {
    Log.w("Radianite", "$label unavailable", e)
    null
}
