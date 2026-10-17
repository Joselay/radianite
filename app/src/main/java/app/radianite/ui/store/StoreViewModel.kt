package app.radianite.ui.store

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.radianite.data.store.DailyStore
import app.radianite.data.store.StoreRepository
import app.radianite.data.store.Wallet
import app.radianite.ui.common.loadReporting
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class StoreUiState(
    val store: DailyStore? = null,
    val wallet: Wallet? = null,
    val refreshing: Boolean = true,
    val message: String? = null,
)

class StoreViewModel(private val repository: StoreRepository) : ViewModel() {
    private val _state = MutableStateFlow(StoreUiState())
    val state: StateFlow<StoreUiState> = _state.asStateFlow()

    private var refreshJob: Job? = null

    init {
        viewModelScope.launch {
            // Show the cached store instantly, then refresh in the background.
            repository.cached()?.let { cached -> _state.update { it.copy(store = cached.store) } }
            refresh()
        }
    }

    fun refresh() {
        if (refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch {
            _state.update { it.copy(refreshing = true, message = null) }
            loadReporting({ message -> _state.update { it.copy(refreshing = false, message = message) } }) {
                val fresh = repository.refresh()
                _state.update { it.copy(store = fresh.store, wallet = fresh.wallet, refreshing = false) }
            }
        }
    }
}
