package app.radianite.ui.matches

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.radianite.data.match.LiveMatch
import app.radianite.data.match.MatchHistory
import app.radianite.data.match.MatchPage
import app.radianite.data.match.MatchSummary
import app.radianite.data.optional
import app.radianite.ui.common.loadReporting
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class MatchesUiState(
    val matches: List<MatchSummary> = emptyList(),
    val live: LiveMatch? = null,
    val refreshing: Boolean = true,
    val loadingMore: Boolean = false,
    val endReached: Boolean = false,
    val message: String? = null,
)

class MatchesViewModel(private val history: MatchHistory) : ViewModel() {
    private val _state = MutableStateFlow(MatchesUiState())
    val state: StateFlow<MatchesUiState> = _state.asStateFlow()

    private var job: Job? = null

    init {
        refresh()
    }

    fun refresh() {
        job?.cancel()
        job = viewModelScope.launch {
            // A refresh replaces any page still loading, so its spinner goes too.
            _state.update { it.copy(refreshing = true, loadingMore = false, message = null) }
            load {
                coroutineScope {
                    val live = async { optional("Live match") { history.liveMatch() } }
                    val page = history.firstPage()
                    _state.update { it.withPage(page).copy(live = live.await(), refreshing = false) }
                }
            }
        }
    }

    fun loadMore() {
        val s = _state.value
        if (s.refreshing || s.loadingMore || s.endReached) return
        job = viewModelScope.launch {
            _state.update { it.copy(loadingMore = true, message = null) }
            load {
                val page = history.nextPage()
                _state.update { it.withPage(page).copy(loadingMore = false) }
            }
        }
    }

    /** Polled while the tab is on screen, so agent select and live games show up without a pull. */
    fun refreshLive() {
        if (job?.isActive == true) return
        viewModelScope.launch {
            loadReporting({ /* The next poll tries again. */ }) {
                val live = optional("Live match") { history.liveMatch() }
                _state.update { it.copy(live = live) }
            }
        }
    }

    private fun MatchesUiState.withPage(page: MatchPage) = copy(matches = page.matches, endReached = page.endReached)

    private suspend fun load(block: suspend () -> Unit) =
        loadReporting({ message -> _state.update { it.copy(refreshing = false, loadingMore = false, message = message) } }, block)
}
