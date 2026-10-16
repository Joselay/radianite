package app.radianite.ui.matches

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.radianite.data.content.GameContent
import app.radianite.data.match.LiveMatch
import app.radianite.data.match.MatchRecord
import app.radianite.data.match.MatchRepository
import app.radianite.data.match.MatchSummary
import app.radianite.data.match.summaryFor
import app.radianite.data.riot.CompetitiveUpdate
import app.radianite.data.riot.SessionExpiredException
import app.radianite.ui.store.toUserMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class MatchesUiState(
    val content: GameContent? = null,
    val matches: List<MatchSummary> = emptyList(),
    /** Ranked rating change per match ID, for competitive games. */
    val rrChanges: Map<String, CompetitiveUpdate> = emptyMap(),
    val live: LiveMatch? = null,
    val refreshing: Boolean = true,
    val loadingMore: Boolean = false,
    val endReached: Boolean = false,
    val message: String? = null,
)

class MatchesViewModel(private val repository: MatchRepository) : ViewModel() {
    private val _state = MutableStateFlow(MatchesUiState())
    val state: StateFlow<MatchesUiState> = _state.asStateFlow()

    /** How far into the history we've paged; separate from matches.size since unloadable matches are skipped. */
    private var nextIndex = 0
    private var job: Job? = null

    init {
        refresh()
    }

    fun refresh() {
        job?.cancel()
        job = viewModelScope.launch {
            _state.update { it.copy(refreshing = true, message = null) }
            load {
                coroutineScope {
                    val content = async { repository.content() }
                    val live = async { optional { repository.liveMatch() } }
                    val rr = async { optional { repository.rankedRatingChanges() }.orEmpty() }
                    val entries = repository.history(0, PAGE_SIZE)
                    val summaries = summarize(repository.records(entries))
                    nextIndex = entries.size
                    _state.update {
                        it.copy(
                            content = content.await(),
                            matches = summaries,
                            rrChanges = rr.await(),
                            live = live.await(),
                            refreshing = false,
                            endReached = entries.size < PAGE_SIZE,
                        )
                    }
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
                val entries = repository.history(nextIndex, PAGE_SIZE)
                val summaries = summarize(repository.records(entries))
                nextIndex += entries.size
                _state.update {
                    it.copy(
                        matches = (it.matches + summaries).distinctBy { m -> m.record.matchId },
                        loadingMore = false,
                        endReached = entries.size < PAGE_SIZE,
                    )
                }
            }
        }
    }

    /** Polled while the tab is on screen, so agent select and live games show up without a pull. */
    fun refreshLive() {
        if (job?.isActive == true) return
        viewModelScope.launch {
            val live = optional { repository.liveMatch() }
            _state.update { it.copy(live = live) }
        }
    }

    private suspend fun summarize(records: List<MatchRecord>): List<MatchSummary> {
        val puuid = repository.puuid() ?: return emptyList()
        return records.mapNotNull { it.summaryFor(puuid) }.sortedByDescending { it.record.startMillis }
    }

    private suspend fun load(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: SessionExpiredException) {
            // The app returns to sign-in.
        } catch (e: Exception) {
            _state.update { it.copy(refreshing = false, loadingMore = false, message = e.toUserMessage()) }
        }
    }

    private suspend fun <T> optional(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    private companion object {
        const val PAGE_SIZE = 10
    }
}
