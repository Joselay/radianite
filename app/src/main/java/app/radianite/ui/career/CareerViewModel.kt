package app.radianite.ui.career

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.radianite.data.match.CareerStats
import app.radianite.data.match.MatchHistory
import app.radianite.data.match.careerStats
import app.radianite.data.player.Career
import app.radianite.data.player.PlayerRepository
import app.radianite.ui.common.loadReporting
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CareerUiState(
    val career: Career? = null,
    val stats: CareerStats? = null,
    val refreshing: Boolean = true,
    val statsLoading: Boolean = true,
    val message: String? = null,
)

class CareerViewModel(
    private val players: PlayerRepository,
    private val matches: MatchHistory,
) : ViewModel() {
    private val _state = MutableStateFlow(CareerUiState())
    val state: StateFlow<CareerUiState> = _state.asStateFlow()

    private var careerJob: Job? = null
    private var statsJob: Job? = null

    init {
        refresh()
    }

    fun refresh() {
        careerJob?.cancel()
        statsJob?.cancel()
        _state.update { it.copy(refreshing = true, statsLoading = true, message = null) }
        careerJob = viewModelScope.launch {
            loadReporting({ message -> _state.update { it.copy(refreshing = false, message = message) } }) {
                val career = players.career()
                _state.update { it.copy(career = career, refreshing = false) }
            }
        }
        // Recent-match stats take a match-details call per uncached game, so they fill in afterwards.
        statsJob = viewModelScope.launch {
            loadReporting({ _state.update { it.copy(statsLoading = false) } }) {
                val stats = careerStats(matches.recent(STATS_MATCHES))
                _state.update { it.copy(stats = stats, statsLoading = false) }
            }
        }
    }

    private companion object {
        const val STATS_MATCHES = 20
    }
}
