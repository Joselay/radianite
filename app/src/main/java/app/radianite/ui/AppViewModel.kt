package app.radianite.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.radianite.data.Failure
import app.radianite.data.account.Account
import app.radianite.data.account.AccountState
import app.radianite.data.riot.AuthTokens
import app.radianite.data.store.FeaturedBundle
import app.radianite.data.store.StoreRepository
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

/** Which top-level screen shows: the Account session's state, plus the sign-in page while it's open. */
class AppViewModel(
    private val account: Account,
    private val store: StoreRepository,
) : ViewModel() {
    private val signInOpen = MutableStateFlow(false)

    val state: StateFlow<AppUiState> = combine(account.state, signInOpen) { account, open ->
        when (account) {
            AccountState.Unknown -> AppUiState.Loading
            is AccountState.SignedOut -> if (open) AppUiState.SigningIn else AppUiState.SignedOut(account.failure?.signedOutMessage())
            AccountState.CompletingSignIn -> AppUiState.CompletingSignIn
            is AccountState.SignedIn -> AppUiState.SignedIn(account.puuid, account.riotId)
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, AppUiState.Loading)

    private val _featuredBundles = MutableStateFlow<List<FeaturedBundle>>(emptyList())
    val featuredBundles: StateFlow<List<FeaturedBundle>> = _featuredBundles.asStateFlow()

    init {
        // The welcome screen shows the bundles the last store fetch saw.
        viewModelScope.launch {
            account.state.collect { if (it is AccountState.SignedOut) _featuredBundles.value = store.featuredBundles() }
        }
    }

    fun startSignIn() {
        signInOpen.value = true
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
