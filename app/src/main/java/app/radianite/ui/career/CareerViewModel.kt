package app.radianite.ui.career

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.radianite.data.content.GameContent
import app.radianite.data.match.CareerStats
import app.radianite.data.match.MatchRepository
import app.radianite.data.match.careerStats
import app.radianite.data.match.summaryFor
import app.radianite.data.player.Career
import app.radianite.data.player.PlayerRepository
import app.radianite.data.riot.SessionExpiredException
import app.radianite.ui.store.toUserMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CareerUiState(
    val content: GameContent? = null,
    val career: Career? = null,
    val stats: CareerStats? = null,
    val refreshing: Boolean = true,
    val statsLoading: Boolean = true,
    val message: String? = null,
)

class CareerViewModel(
    private val players: PlayerRepository,
    private val matches: MatchRepository,
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
            guard({ copy(refreshing = false, message = it) }) {
                val content = players.content()
                val career = players.career()
                _state.update { it.copy(content = content, career = career, refreshing = false) }
            }
        }
        // Recent-match stats take a match-details call per uncached game, so they fill in afterwards.
        statsJob = viewModelScope.launch {
            guard({ copy(statsLoading = false) }) {
                val puuid = matches.puuid() ?: return@guard
                val records = matches.records(matches.history(0, STATS_MATCHES))
                val stats = careerStats(records.mapNotNull { it.summaryFor(puuid) })
                _state.update { it.copy(stats = stats, statsLoading = false) }
            }
        }
    }

    private suspend fun guard(onError: CareerUiState.(String) -> CareerUiState, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: SessionExpiredException) {
            // The app returns to sign-in.
        } catch (e: Exception) {
            _state.update { it.onError(e.toUserMessage()) }
        }
    }

    private companion object {
        const val STATS_MATCHES = 20
    }
}
