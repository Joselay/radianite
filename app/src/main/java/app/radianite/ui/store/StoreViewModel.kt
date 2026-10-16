package app.radianite.ui.store

import android.util.Log
import android.webkit.CookieManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.radianite.data.riot.AuthTokens
import app.radianite.data.riot.RiotHttpException
import app.radianite.data.riot.SessionExpiredException
import app.radianite.data.riot.SessionManager
import app.radianite.data.store.DailyStore
import app.radianite.data.store.FeaturedBundle
import app.radianite.data.store.StoreRepository
import app.radianite.data.store.Wallet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException

sealed interface AppUiState {
    data object Loading : AppUiState
    data class SignedOut(val message: String? = null) : AppUiState
    data object SigningIn : AppUiState
    data object CompletingSignIn : AppUiState
    data class Store(
        /** Keys the per-account screens, so a different sign-in never sees the last account's data. */
        val puuid: String,
        val riotId: String,
        val store: DailyStore?,
        val refreshing: Boolean,
        val message: String?,
        val wallet: Wallet? = null,
    ) : AppUiState
}

/** App-level state: who's signed in, plus the store (the home tab). */
class StoreViewModel(
    private val sessions: SessionManager,
    private val repository: StoreRepository,
) : ViewModel() {
    private val _state = MutableStateFlow<AppUiState>(AppUiState.Loading)
    val state: StateFlow<AppUiState> = _state.asStateFlow()

    private val _featuredBundles = MutableStateFlow<List<FeaturedBundle>>(emptyList())
    val featuredBundles: StateFlow<List<FeaturedBundle>> = _featuredBundles.asStateFlow()

    private var refreshJob: Job? = null

    init {
        // Any tab can find the session gone; all of them land back on sign-in.
        viewModelScope.launch {
            sessions.expired.collect {
                if (_state.value !is AppUiState.SignedOut) expireSession()
            }
        }
        viewModelScope.launch {
            _featuredBundles.value = repository.featuredBundles()
            val session = sessions.savedSession()
            if (session == null) {
                _state.value = AppUiState.SignedOut()
                return@launch
            }
            // Show the cached store instantly, then refresh in the background.
            _state.value = AppUiState.Store(session.puuid, session.riotId, repository.cachedStore(session.puuid), refreshing = true, message = null)
            refresh()
        }
    }

    fun refresh() {
        if (refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch {
            _state.update { (it as? AppUiState.Store)?.copy(refreshing = true, message = null) ?: it }
            try {
                // The wallet is a nice-to-have; the store shows without it.
                val wallet = async { runCatching { repository.fetchWallet() }.onFailure { Log.w(TAG, "Wallet unavailable", it) }.getOrNull() }
                val store = repository.fetchDailyStore()
                val session = sessions.savedSession()
                _state.value = AppUiState.Store(
                    puuid = store.puuid,
                    riotId = session?.riotId.orEmpty(),
                    store = store,
                    refreshing = false,
                    message = null,
                    wallet = wallet.await(),
                )
                _featuredBundles.value = repository.featuredBundles()
            } catch (e: CancellationException) {
                throw e
            } catch (e: SessionExpiredException) {
                // The expired flow handles it.
            } catch (e: Exception) {
                _state.update { (it as? AppUiState.Store)?.copy(refreshing = false, message = e.toUserMessage()) ?: it }
            }
        }
    }

    fun startSignIn() {
        _state.value = AppUiState.SigningIn
    }

    fun cancelSignIn() {
        _state.value = AppUiState.SignedOut()
    }

    fun onSignInRedirect(tokens: AuthTokens) {
        CookieManager.getInstance().flush()
        _state.value = AppUiState.CompletingSignIn
        viewModelScope.launch {
            try {
                val session = sessions.completeSignIn(tokens)
                _state.value = AppUiState.Store(session.puuid, session.riotId, store = null, refreshing = true, message = null)
                refresh()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = AppUiState.SignedOut("Sign-in failed: ${e.toUserMessage()}")
            }
        }
    }

    fun signOut() {
        refreshJob?.cancel()
        viewModelScope.launch {
            sessions.signOut()
            _state.value = AppUiState.SignedOut()
        }
    }

    private suspend fun expireSession() {
        refreshJob?.cancel()
        sessions.signOut()
        _state.value = AppUiState.SignedOut(SessionExpiredException().message)
    }

}

private const val TAG = "StoreViewModel"

fun Exception.toUserMessage(): String = when {
    this is RiotHttpException && isMaintenance -> "VALORANT servers are under maintenance."
    this is RiotHttpException && status == 429 -> "Riot is rate-limiting requests. Try again in a minute."
    this is RiotHttpException -> "Riot returned an error (${errorCode ?: status})."
    this is IOException -> "Network problem. Check your connection and try again."
    else -> message ?: "Something went wrong."
}
