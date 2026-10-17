package app.radianite.data.account

import app.radianite.data.Failure
import app.radianite.data.LocalStore
import app.radianite.data.riot.AuthTokens
import app.radianite.data.riot.RiotCookies
import app.radianite.data.riot.RiotPlayerData
import app.radianite.data.riot.RiotSession
import app.radianite.data.toFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface AccountState {
    /** The saved session hasn't been read yet. */
    data object Unknown : AccountState

    /** [failure] says why, when the user didn't sign out themselves: [Failure.Expired], or why sign-in failed. */
    data class SignedOut(val failure: Failure? = null) : AccountState
    data object CompletingSignIn : AccountState

    /** [puuid] keys the per-account screens, so a different sign-in never sees the last account's data. */
    data class SignedIn(val puuid: String, val riotId: String) : AccountState
}

/**
 * Who is signed in. Restores the saved session at start, completes sign-in from Riot's redirect, and signs
 * out, clearing the account's local data, whether the user asks or any call finds the session gone.
 */
class Account(
    private val riot: RiotPlayerData,
    private val cookies: RiotCookies,
    private val local: LocalStore,
    scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<AccountState>(AccountState.Unknown)
    val state: StateFlow<AccountState> = _state.asStateFlow()

    init {
        scope.launch {
            // Any screen can find the session gone; all of them land back on sign-in.
            launch {
                riot.expired.collect {
                    if (_state.value !is AccountState.SignedOut) end(Failure.Expired)
                }
            }
            val session = riot.savedSession()
            if (_state.value == AccountState.Unknown) {
                _state.value = session?.signedIn() ?: AccountState.SignedOut()
            }
        }
    }

    /** Takes the tokens from Riot's sign-in redirect. */
    suspend fun completeSignIn(tokens: AuthTokens) {
        _state.value = AccountState.CompletingSignIn
        try {
            // The sign-in page just set the Riot cookies; silent renewal depends on them.
            cookies.flush()
            _state.value = riot.signIn(tokens).signedIn()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.value = AccountState.SignedOut(e.toFailure())
        }
    }

    suspend fun signOut() = end(failure = null)

    private suspend fun end(failure: Failure?) {
        riot.signOut()
        local.clearAccountData()
        _state.value = AccountState.SignedOut(failure)
    }

    private fun RiotSession.signedIn() = AccountState.SignedIn(puuid, riotId)
}
