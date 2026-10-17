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
import androidx.compose.runtime.LaunchedEffect
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import app.radianite.data.match.LiveMatch
import app.radianite.data.match.LivePlayer
import app.radianite.data.match.MatchSummary
import app.radianite.ui.common.AgentIcon
import app.radianite.ui.common.LocalGameContent
import app.radianite.ui.common.MessageBanner
import app.radianite.ui.common.RankIcon
import app.radianite.ui.common.SectionHeader
import app.radianite.ui.common.WinGreen
import app.radianite.ui.common.color
import app.radianite.ui.common.relativeTime
import app.radianite.ui.common.signed
import app.radianite.ui.theme.Display
import app.radianite.ui.theme.Ink
import app.radianite.ui.theme.InkRaised
import app.radianite.ui.theme.Mono
import app.radianite.ui.theme.Muted
import app.radianite.ui.theme.ValorantRed
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MatchesScreen(riotId: String, viewModel: MatchesViewModel, onOpenMatch: (MatchSummary) -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Poll for agent select / live games only while this tab is visible and the app is in front.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                delay(LIVE_POLL_MILLIS)
                viewModel.refreshLive()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Matches", fontWeight = FontWeight.Bold)
                        Text(riotId, style = MaterialTheme.typography.labelMedium, color = Muted)
                    }
                },
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
                // One always-present top item, so a live card that appears while you're at the top isn't
                // inserted off-screen above the list's scroll anchor.
                item(key = "header") {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        state.message?.let { MessageBanner(it) }
                        state.live?.let { LiveMatchCard(it) }
                        if (state.matches.isNotEmpty()) SectionHeader("RECENT MATCHES")
                    }
                }
                items(state.matches, key = { it.record.matchId }) { match ->
                    MatchRow(match, onClick = { onOpenMatch(match) })
                }
                if (state.matches.isEmpty() && !state.refreshing && state.message == null) {
                    item(key = "empty") { Text("No recent matches.", color = Muted, modifier = Modifier.padding(vertical = 32.dp)) }
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

@Composable
private fun LiveMatchCard(live: LiveMatch) {
    val content = LocalGameContent.current
    val map = content?.map(live.mapId)
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Ink),
    ) {
        AsyncImage(
            model = map?.splash,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            alpha = 0.4f,
            modifier = Modifier.matchParentSize(),
        )
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(ValorantRed))
                Spacer(Modifier.width(8.dp))
                Text(
                    if (live.phase == LiveMatch.Phase.AGENT_SELECT) "LIVE · AGENT SELECT" else "LIVE · IN PROGRESS",
                    fontFamily = Mono,
                    fontSize = 11.sp,
                    letterSpacing = 1.5.sp,
                    color = ValorantRed,
                )
            }
            Text(map?.name ?: "Loading map", fontFamily = Display, fontSize = 28.sp)
            live.queueId?.let { Text(content?.queueName(it) ?: it, color = Muted, style = MaterialTheme.typography.labelMedium) }
            SectionHeader("YOUR TEAM")
            live.allies.forEach { LivePlayerRow(it) }
            if (live.enemies.isNotEmpty()) {
                SectionHeader("ENEMY TEAM")
                live.enemies.forEach { LivePlayerRow(it) }
            }
        }
    }
}

@Composable
private fun LivePlayerRow(player: LivePlayer) {
    val agent = LocalGameContent.current?.agent(player.agentId)
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 3.dp)) {
        AgentIcon(player.agentId, 32.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                player.name ?: agent?.name ?: "Picking…",
                fontWeight = if (player.isSelf) FontWeight.Bold else FontWeight.Normal,
                color = if (player.isSelf) MaterialTheme.colorScheme.primary else Color.Unspecified,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val detail = listOfNotNull(
                agent?.name?.takeIf { player.name != null },
                if (!player.locked && player.agentId != null) "hovering" else null,
            ).joinToString(" · ")
            if (detail.isNotEmpty()) Text(detail, style = MaterialTheme.typography.labelSmall, color = Muted)
        }
        player.accountLevel?.let { Text("LV $it", fontFamily = Mono, fontSize = 11.sp, color = Muted) }
    }
}

private const val LIVE_POLL_MILLIS = 20_000L
