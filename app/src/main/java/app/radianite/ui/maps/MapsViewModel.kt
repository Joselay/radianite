package app.radianite.ui.maps

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.radianite.data.comps.CompGuide
import app.radianite.data.comps.TeamComps
import app.radianite.ui.common.loadReporting
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class MapsUiState(
    val guide: CompGuide? = null,
    val refreshing: Boolean = true,
    val message: String? = null,
)

class MapsViewModel(private val comps: TeamComps) : ViewModel() {
    private val _state = MutableStateFlow(MapsUiState())
    val state: StateFlow<MapsUiState> = _state.asStateFlow()

    private var refreshJob: Job? = null

    init {
        viewModelScope.launch {
            // The guide on the phone shows at once; a newer one from the network fills in after.
            val guide = comps.local()
            _state.update { it.copy(guide = guide) }
            refresh()
        }
    }

    fun refresh() {
        if (refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch {
            _state.update { it.copy(refreshing = true, message = null) }
            loadReporting({ message -> _state.update { it.copy(refreshing = false, message = message) } }) {
                val guide = comps.refreshed()
                _state.update { it.copy(guide = guide, refreshing = false) }
            }
        }
    }
}
