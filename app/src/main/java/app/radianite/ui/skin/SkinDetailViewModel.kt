package app.radianite.ui.skin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.radianite.data.content.Content
import app.radianite.data.content.SkinDetail
import app.radianite.data.content.SkinVariant
import app.radianite.data.optional
import app.radianite.data.player.SkinUpgrades
import app.radianite.data.riot.SessionExpiredException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface SkinDetailUiState {
    data object Loading : SkinDetailUiState
    data class Error(val message: String) : SkinDetailUiState
    /** [upgrades] is null until it loads, and when the skin has nothing to buy with Radianite. */
    data class Loaded(val detail: SkinDetail, val selected: SkinVariant, val upgrades: SkinUpgrades? = null) : SkinDetailUiState
}

class SkinDetailViewModel(
    private val content: Content,
    private val skinUuid: String,
    /** The skin's Radianite prices and what the shown account has unlocked. */
    private val upgrades: suspend () -> SkinUpgrades?,
) : ViewModel() {
    private val _state = MutableStateFlow<SkinDetailUiState>(SkinDetailUiState.Loading)
    val state: StateFlow<SkinDetailUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        _state.value = SkinDetailUiState.Loading
        viewModelScope.launch {
            // Previews show without the prices; they fill in when Riot answers.
            // An expired session is announced by the Riot module; here it just means no prices.
            val prices = async {
                try {
                    optional("Skin upgrades") { upgrades() }
                } catch (e: SessionExpiredException) {
                    null
                }
            }
            _state.value = try {
                val detail = content.skinDetail(skinUuid)
                val first = detail.levels.firstOrNull { it.videoUrl != null }
                    ?: detail.levels.firstOrNull()
                    ?: detail.chromas.first()
                SkinDetailUiState.Loaded(detail, first)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SkinDetailUiState.Error("Couldn't load this skin's previews.")
            }
            prices.await()?.let { found -> _state.update { (it as? SkinDetailUiState.Loaded)?.copy(upgrades = found) ?: it } }
        }
    }

    fun select(variant: SkinVariant) {
        _state.update { (it as? SkinDetailUiState.Loaded)?.copy(selected = variant) ?: it }
    }
}
