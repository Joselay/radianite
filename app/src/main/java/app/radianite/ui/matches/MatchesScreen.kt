package app.radianite.ui.matches

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.radianite.data.match.MatchSummary
import app.radianite.ui.common.AccountsButton
import app.radianite.ui.common.AgentIcon
import app.radianite.ui.common.HudChip
import app.radianite.ui.common.LocalGameContent
import app.radianite.ui.common.MessageBanner
import app.radianite.ui.common.RankIcon
import app.radianite.ui.common.Skeleton
import app.radianite.ui.common.WinGreen
import app.radianite.ui.common.color
import app.radianite.ui.common.relativeTime
import app.radianite.ui.common.signed
import app.radianite.ui.theme.Display
import app.radianite.ui.theme.InkRaised
import app.radianite.ui.theme.Mono
import app.radianite.ui.theme.Muted
import app.radianite.ui.theme.ValorantRed
import coil3.compose.AsyncImage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MatchesScreen(riotId: String, viewModel: MatchesViewModel, onOpenAccounts: () -> Unit, onOpenMatch: (MatchSummary) -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Matches", fontWeight = FontWeight.Bold)
                        Text(riotId, style = MaterialTheme.typography.labelMedium, color = Muted)
                    }
                },
                actions = { AccountsButton(onOpenAccounts) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = viewModel::refresh,
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                // One always-present top item, so a banner that appears while you're at the top isn't
                // inserted off-screen above the list's scroll anchor.
                item(key = "header") {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        state.message?.let { MessageBanner(it) }
                        QueueChips(state.filter, onSelect = viewModel::select)
                    }
                }
                items(state.matches, key = { it.record.matchId }) { match ->
                    MatchRow(match, onClick = { onOpenMatch(match) })
                }
                if (state.matches.isEmpty() && state.refreshing) {
                    items(PLACEHOLDER_ROWS, key = { "skeleton-$it" }) { Skeleton(Modifier.fillMaxWidth().height(72.dp)) }
                }
                if (state.matches.isEmpty() && !state.refreshing && state.message == null) {
                    item(key = "empty") {
                        val queue = state.filter.takeIf { it != QueueFilter.ALL }?.label
                        Text(if (queue == null) "No recent matches." else "No recent $queue matches.", color = Muted, modifier = Modifier.padding(vertical = 32.dp))
                    }
                }
                if (state.matches.isNotEmpty() && !state.endReached) {
                    item(key = "more") {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            if (state.loadingMore) {
                                CircularProgressIndicator(Modifier.padding(12.dp).size(24.dp), strokeWidth = 2.dp)
                            } else {
                                TextButton(onClick = viewModel::loadMore) { Text("Load more", color = ValorantRed) }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** One chip per queue; the list reloads from the top for the one picked. */
@Composable
private fun QueueChips(selected: QueueFilter, onSelect: (QueueFilter) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(QueueFilter.entries, key = { it.name }) { filter ->
            HudChip(filter.label, selected = filter == selected, onClick = { onSelect(filter) })
        }
    }
}

@Composable
private fun MatchRow(match: MatchSummary, onClick: () -> Unit) {
    val content = LocalGameContent.current
    val rr = match.rr
    val record = match.record
    val me = match.me
    val map = content?.map(record.mapId)
    val accent = match.outcome.color()
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(InkRaised)
            .clickable(onClick = onClick),
    ) {
        // Map art fades in from the right behind the stats.
        AsyncImage(
            model = map?.listIcon,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            alpha = 0.35f,
            modifier = Modifier.matchParentSize(),
        )
        Box(Modifier.matchParentSize().background(Brush.horizontalGradient(listOf(InkRaised, InkRaised.copy(alpha = 0.85f), Color.Transparent))))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(72.dp)) {
            Box(Modifier.width(4.dp).height(72.dp).background(accent))
            Spacer(Modifier.width(12.dp))
            AgentIcon(me.agentId, 48.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(map?.name ?: "Unknown map", fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${content?.queueName(record.queueId) ?: record.queueId} · ${relativeTime(record.startMillis)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = Muted,
                    maxLines = 1,
                )
                Text("${me.kills} / ${me.deaths} / ${me.assists}", fontFamily = Mono, fontSize = 12.sp)
            }
            Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(end = 14.dp)) {
                Text(match.scoreLine, fontFamily = Display, fontSize = 22.sp, color = accent)
                if (rr != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RankIcon(rr.tierAfter, 16.dp)
                        Spacer(Modifier.width(4.dp))
                        Text(
                            "${signed(rr.rrEarned)} RR",
                            fontFamily = Mono,
                            fontSize = 11.sp,
                            color = if (rr.rrEarned >= 0) WinGreen else ValorantRed,
                        )
                    }
                } else if (record.isTeamMode) {
                    // Free-for-all modes count as a single round, so ACS means nothing there.
                    Text("ACS ${me.acs}", fontFamily = Mono, fontSize = 11.sp, color = Muted)
                }
            }
        }
    }
}

private const val PLACEHOLDER_ROWS = 6
