package app.radianite.ui.skin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.radianite.data.content.SkinDetail
import app.radianite.data.content.SkinDetailRepository
import app.radianite.data.content.SkinVariant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface SkinDetailUiState {
    data object Loading : SkinDetailUiState
    data class Error(val message: String) : SkinDetailUiState
    data class Loaded(val detail: SkinDetail, val selected: SkinVariant) : SkinDetailUiState
}

class SkinDetailViewModel(
    private val repository: SkinDetailRepository,
    private val skinUuid: String,
) : ViewModel() {
    private val _state = MutableStateFlow<SkinDetailUiState>(SkinDetailUiState.Loading)
    val state: StateFlow<SkinDetailUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        _state.value = SkinDetailUiState.Loading
        viewModelScope.launch {
            _state.value = try {
                val detail = repository.detail(skinUuid)
                val first = detail.levels.firstOrNull { it.videoUrl != null }
                    ?: detail.levels.firstOrNull()
                    ?: detail.chromas.first()
                SkinDetailUiState.Loaded(detail, first)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SkinDetailUiState.Error("Couldn't load this skin's previews.")
            }
        }
    }

    fun select(variant: SkinVariant) {
        _state.update { (it as? SkinDetailUiState.Loaded)?.copy(selected = variant) ?: it }
    }
}
