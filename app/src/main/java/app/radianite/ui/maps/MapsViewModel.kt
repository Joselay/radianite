package app.radianite.ui.maps

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.radianite.data.comps.CompGuide
import app.radianite.data.comps.TeamComps
import app.radianite.data.match.MapPlay
import app.radianite.data.match.MatchHistory
import app.radianite.data.match.mapPlay
import app.radianite.data.optional
import app.radianite.ui.common.loadReporting
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** [play] is keyed by lowercase map path. */
data class MapsUiState(
    val guide: CompGuide? = null,
    val play: Map<String, MapPlay> = emptyMap(),
    val refreshing: Boolean = true,
    val message: String? = null,
)

class MapsViewModel(private val comps: TeamComps, private val matches: MatchHistory) : ViewModel() {
    private val _state = MutableStateFlow(MapsUiState())
    val state: StateFlow<MapsUiState> = _state.asStateFlow()

    private var refreshJob: Job? = null

    init {
        viewModelScope.launch {
            // The guide on the phone and the matches already kept show at once; the network fills in after.
            val guide = comps.local()
            val play = optional("Stored matches") { mapPlay(matches.stored()) }
            _state.update { it.copy(guide = guide, play = play ?: it.play) }
            refresh()
        }
    }

    fun refresh() {
        if (refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch {
            _state.update { it.copy(refreshing = true, message = null) }
            loadReporting({ message -> _state.update { it.copy(refreshing = false, message = message) } }) {
                val guide = comps.refreshed()
                _state.update { it.copy(guide = guide) }
                // Loading the newest matches keeps them on the phone, where the per-map stats read them.
                matches.recent(RECENT_MATCHES)
                _state.update { it.copy(play = mapPlay(matches.stored()), refreshing = false) }
            }
        }
    }

    private companion object {
        const val RECENT_MATCHES = 20
    }
}
