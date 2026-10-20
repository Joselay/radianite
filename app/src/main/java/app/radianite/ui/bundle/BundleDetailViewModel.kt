package app.radianite.ui.bundle

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.radianite.data.content.Content
import app.radianite.data.store.BundleContents
import app.radianite.data.store.StoreBundle
import app.radianite.data.store.bundleContents
import app.radianite.ui.common.loadReporting
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface BundleDetailUiState {
    data object Loading : BundleDetailUiState
    data class Error(val message: String) : BundleDetailUiState
    data class Loaded(val contents: BundleContents) : BundleDetailUiState
}

class BundleDetailViewModel(
    private val content: Content,
    private val bundle: StoreBundle,
) : ViewModel() {
    private val _state = MutableStateFlow<BundleDetailUiState>(BundleDetailUiState.Loading)
    val state: StateFlow<BundleDetailUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        _state.value = BundleDetailUiState.Loading
        viewModelScope.launch {
            loadReporting({ _state.value = BundleDetailUiState.Error(it) }) {
                _state.value = BundleDetailUiState.Loaded(content.bundleContents(bundle))
            }
        }
    }
}
