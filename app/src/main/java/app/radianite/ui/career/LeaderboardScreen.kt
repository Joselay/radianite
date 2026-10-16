package app.radianite.ui.career

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.radianite.data.content.CardArt
import app.radianite.data.content.GameContent
import app.radianite.data.player.Leaderboard
import app.radianite.data.player.PlayerRepository
import app.radianite.data.riot.LeaderboardPlayer
import app.radianite.data.riot.SessionExpiredException
import app.radianite.ui.common.LoadingPane
import app.radianite.ui.common.MessageBanner
import app.radianite.ui.common.RankIcon
import app.radianite.ui.store.toUserMessage
import app.radianite.ui.theme.InkRaised
import app.radianite.ui.theme.Mono
import app.radianite.ui.theme.Muted
import app.radianite.ui.theme.ValorantRed
import coil3.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LeaderboardUiState(
    val query: String = "",
    val content: GameContent? = null,
    val leaderboard: Leaderboard? = null,
    val loading: Boolean = true,
    val message: String? = null,
)

class LeaderboardViewModel(private val repository: PlayerRepository) : ViewModel() {
    private val _state = MutableStateFlow(LeaderboardUiState())
    val state: StateFlow<LeaderboardUiState> = _state.asStateFlow()

    private var job: Job? = null

    init {
        load(debounce = false)
    }

    fun search(query: String) {
        _state.update { it.copy(query = query) }
        load(debounce = true)
    }

    private fun load(debounce: Boolean) {
        job?.cancel()
        job = viewModelScope.launch {
            if (debounce) delay(SEARCH_DEBOUNCE_MILLIS)
            _state.update { it.copy(loading = true, message = null) }
            try {
                val content = repository.content()
                val board = repository.leaderboard(_state.value.query.trim().ifEmpty { null })
                _state.update { it.copy(content = content, leaderboard = board, loading = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: SessionExpiredException) {
                // The app returns to sign-in.
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, message = e.toUserMessage()) }
            }
        }
    }

    private companion object {
        const val SEARCH_DEBOUNCE_MILLIS = 400L
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LeaderboardScreen(viewModel: LeaderboardViewModel, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Leaderboard", fontWeight = FontWeight.Bold)
                        state.leaderboard?.let { board ->
                            val subtitle = listOfNotNull(board.actName, "%,d ranked players".format(board.totalPlayers).takeIf { board.totalPlayers > 0 })
                            Text(subtitle.joinToString(" · "), style = MaterialTheme.typography.labelMedium, color = Muted)
                        }
                    }
                },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back", color = Muted) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::search,
                placeholder = { Text("Search by Riot ID") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
            state.message?.let { MessageBanner(it, Modifier.padding(horizontal = 16.dp)) }
            val players = state.leaderboard?.players.orEmpty()
            when {
                state.loading && players.isEmpty() -> LoadingPane()
                players.isEmpty() && state.message == null -> Text(
                    if (state.query.isBlank()) "No one is on the leaderboard yet this act." else "No ranked players match that name.",
                    color = Muted,
                    modifier = Modifier.padding(16.dp),
                )
                else -> LazyColumn(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(players, key = { it.leaderboardRank }) { LeaderboardRow(it, state.content) }
                }
            }
        }
    }
}

@Composable
private fun LeaderboardRow(player: LeaderboardPlayer, content: GameContent?) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(InkRaised)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Text(
            "#${player.leaderboardRank}",
            fontFamily = Mono,
            fontWeight = FontWeight.Bold,
            color = if (player.leaderboardRank <= 10) ValorantRed else Muted,
            modifier = Modifier.width(52.dp),
        )
        AsyncImage(
            model = CardArt.small(player.playerCardId),
            contentDescription = null,
            modifier = Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(4.dp)),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                if (player.isAnonymized || player.gameName.isBlank()) "Secret Agent" else "${player.gameName}#${player.tagLine}",
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text("${player.numberOfWins} wins", style = MaterialTheme.typography.labelSmall, color = Muted)
        }
        RankIcon(content, player.competitiveTier, 24.dp)
        Spacer(Modifier.width(6.dp))
        Text("${player.rankedRating} RR", fontFamily = Mono, fontSize = 12.sp)
    }
}
