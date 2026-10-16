package app.radianite.ui.store

import android.webkit.CookieManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.radianite.data.riot.AuthTokens
import app.radianite.data.riot.RiotHttpException
import app.radianite.data.store.DailyStore
import app.radianite.data.store.FeaturedBundle
import app.radianite.data.store.SessionExpiredException
import app.radianite.data.store.StoreRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
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
        val riotId: String,
        val store: DailyStore?,
        val refreshing: Boolean,
        val message: String?,
    ) : AppUiState
}

class StoreViewModel(private val repository: StoreRepository) : ViewModel() {
    private val _state = MutableStateFlow<AppUiState>(AppUiState.Loading)
    val state: StateFlow<AppUiState> = _state.asStateFlow()

    private val _featuredBundles = MutableStateFlow<List<FeaturedBundle>>(emptyList())
    val featuredBundles: StateFlow<List<FeaturedBundle>> = _featuredBundles.asStateFlow()

    private var refreshJob: Job? = null

    init {
        viewModelScope.launch {
            _featuredBundles.value = repository.featuredBundles()
            val session = repository.savedSession()
            if (session == null) {
                _state.value = AppUiState.SignedOut()
                return@launch
            }
            // Show the cached store instantly, then refresh in the background.
            _state.value = AppUiState.Store(session.riotId, repository.cachedStore(session.puuid), refreshing = true, message = null)
            refresh()
        }
    }

    fun refresh() {
        if (refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch {
            _state.update { (it as? AppUiState.Store)?.copy(refreshing = true, message = null) ?: it }
            try {
                val store = repository.fetchDailyStore()
                val riotId = repository.savedSession()?.riotId.orEmpty()
                _state.value = AppUiState.Store(riotId, store, refreshing = false, message = null)
                _featuredBundles.value = repository.featuredBundles()
            } catch (e: CancellationException) {
                throw e
            } catch (e: SessionExpiredException) {
                repository.signOut()
                _state.value = AppUiState.SignedOut(e.message)
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
                val session = repository.completeSignIn(tokens)
                _state.value = AppUiState.Store(session.riotId, store = null, refreshing = true, message = null)
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
            repository.signOut()
            _state.value = AppUiState.SignedOut()
        }
    }

    private fun Exception.toUserMessage(): String = when {
        this is RiotHttpException && isMaintenance -> "VALORANT servers are under maintenance."
        this is RiotHttpException -> "Riot returned an error (${errorCode ?: status})."
        this is IOException -> "Network problem. Check your connection and try again."
        else -> message ?: "Something went wrong."
    }
}
