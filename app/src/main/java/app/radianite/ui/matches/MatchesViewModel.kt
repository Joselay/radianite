package app.radianite.ui.matches

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.radianite.data.match.MatchHistory
import app.radianite.data.match.MatchPage
import app.radianite.data.match.MatchSummary
import app.radianite.ui.common.loadReporting
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The queues the match list can be narrowed to; [queueId] is Riot's, null for every queue. */
enum class QueueFilter(val label: String, val queueId: String?) {
    ALL("All", null),
    COMPETITIVE("Competitive", "competitive"),
    UNRATED("Unrated", "unrated"),
    SWIFTPLAY("Swiftplay", "swiftplay"),
    SPIKE_RUSH("Spike Rush", "spikerush"),
    DEATHMATCH("Deathmatch", "deathmatch"),
    TEAM_DEATHMATCH("Team Deathmatch", "hurm"),
    PREMIER("Premier", "premier"),
}

data class MatchesUiState(
    val filter: QueueFilter = QueueFilter.ALL,
    val matches: List<MatchSummary> = emptyList(),
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

    /** Shows only [filter]'s matches, loading them from the top. */
    fun select(filter: QueueFilter) {
        if (filter == _state.value.filter) return
        // The old queue's matches go at once, so they're never shown under the new filter.
        _state.update { it.copy(filter = filter, matches = emptyList(), endReached = false) }
        refresh()
    }

    fun refresh() {
        job?.cancel()
        job = viewModelScope.launch {
            // A refresh replaces any page still loading, so its spinner goes too.
            _state.update { it.copy(refreshing = true, loadingMore = false, message = null) }
            val queue = _state.value.filter.queueId
            load {
                val page = history.firstPage(queue)
                _state.update { it.withPage(page).copy(refreshing = false) }
            }
        }
    }

    fun loadMore() {
        val s = _state.value
        if (s.refreshing || s.loadingMore || s.endReached) return
        job = viewModelScope.launch {
            _state.update { it.copy(loadingMore = true, message = null) }
            load {
                val page = history.nextPage(s.filter.queueId)
                _state.update { it.withPage(page).copy(loadingMore = false) }
            }
        }
    }

    private fun MatchesUiState.withPage(page: MatchPage) = copy(matches = page.matches, endReached = page.endReached)

    private suspend fun load(block: suspend () -> Unit) =
        loadReporting({ message -> _state.update { it.copy(refreshing = false, loadingMore = false, message = message) } }, block)
}
