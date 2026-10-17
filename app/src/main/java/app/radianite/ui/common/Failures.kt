package app.radianite.ui.common

import app.radianite.data.Failure
import app.radianite.data.toFailure
import kotlinx.coroutines.CancellationException

fun Failure.userMessage(): String = when (this) {
    Failure.Expired -> "Your Riot session expired. Sign in again."
    Failure.Maintenance -> "VALORANT servers are under maintenance."
    Failure.RateLimited -> "Riot is rate-limiting requests. Try again in a minute."
    Failure.Offline -> "Network problem. Check your connection and try again."
    is Failure.Riot -> "Riot returned an error ($code)."
    is Failure.Other -> message ?: "Something went wrong."
}

/**
 * Runs a screen's load. Cancellation propagates; an expired session is left alone, since the Account
 * session already returns the app to sign-in; any other failure is handed to [onFailure] as a message.
 */
suspend fun loadReporting(onFailure: (message: String) -> Unit, block: suspend () -> Unit) {
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        val failure = e.toFailure()
        if (failure != Failure.Expired) onFailure(failure.userMessage())
    }
}
