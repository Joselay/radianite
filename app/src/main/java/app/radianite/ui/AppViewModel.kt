package app.radianite.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.radianite.data.Failure
import app.radianite.data.account.Account
import app.radianite.data.account.AccountState
import app.radianite.data.account.SavedAccount
import app.radianite.data.riot.AuthTokens
import app.radianite.data.store.FeaturedBundle
import app.radianite.ui.common.userMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface AppUiState {
    data object Loading : AppUiState
    data class SignedOut(val message: String? = null) : AppUiState
    data object SigningIn : AppUiState
    data object CompletingSignIn : AppUiState
    data class SignedIn(val puuid: String, val riotId: String) : AppUiState
}

/**
 * Which top-level screen shows: the Account session's state, plus the sign-in page while it's open, whether
 * for a first account or another one alongside.
 */
class AppViewModel(
    private val account: Account,
    private val featuredBundles: suspend () -> List<FeaturedBundle>,
) : ViewModel() {
    private val signInOpen = MutableStateFlow(false)

    val state: StateFlow<AppUiState> = combine(account.state, signInOpen) { account, open ->
        when {
            account == AccountState.Unknown -> AppUiState.Loading
            account == AccountState.CompletingSignIn -> AppUiState.CompletingSignIn
            open -> AppUiState.SigningIn
            account is AccountState.SignedOut -> AppUiState.SignedOut(account.failure?.signedOutMessage())
            account is AccountState.SignedIn -> AppUiState.SignedIn(account.puuid, account.riotId)
            else -> AppUiState.Loading
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, AppUiState.Loading)

    /** Every account signed in on this device. */
    val accounts: StateFlow<List<SavedAccount>> = account.saved

    private val _bundles = MutableStateFlow<List<FeaturedBundle>>(emptyList())
    val bundles: StateFlow<List<FeaturedBundle>> = _bundles.asStateFlow()

    init {
        // The welcome screen shows the bundles the last store fetch saw.
        viewModelScope.launch {
            account.state.collect { if (it is AccountState.SignedOut) _bundles.value = featuredBundles() }
        }
    }

    /** Opens Riot's sign-in page for a new account, emptied first so it doesn't sign the last one straight back in. */
    fun startSignIn() {
        viewModelScope.launch {
            account.prepareSignIn()
            signInOpen.value = true
        }
    }

    fun switchTo(puuid: String) {
        viewModelScope.launch { account.switchTo(puuid) }
    }

    fun cancelSignIn() {
        signInOpen.value = false
    }

    fun onSignInRedirect(tokens: AuthTokens) {
        // Launched first so the account is already completing sign-in when the page closes, with no signed-out flash.
        viewModelScope.launch { account.completeSignIn(tokens) }
        signInOpen.value = false
    }

    fun signOut() {
        viewModelScope.launch { account.signOut() }
    }

    private fun Failure.signedOutMessage() = if (this == Failure.Expired) userMessage() else "Sign-in failed: ${userMessage()}"
}
