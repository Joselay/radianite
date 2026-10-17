package app.radianite.ui.career

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.radianite.data.content.CardArt
import app.radianite.data.match.CareerStats
import app.radianite.data.match.RankedRatingChange
import app.radianite.data.player.ActRank
import app.radianite.data.player.BattlePassProgress
import app.radianite.data.player.Career
import app.radianite.data.player.RR_PER_TIER
import app.radianite.data.player.RankStatus
import app.radianite.data.player.XP_PER_ACCOUNT_LEVEL
import app.radianite.ui.VersionLabel
import app.radianite.ui.common.AgentIcon
import app.radianite.ui.common.LocalGameContent
import app.radianite.ui.common.MessageBanner
import app.radianite.ui.common.ProgressBar
import app.radianite.ui.common.RankIcon
import app.radianite.ui.common.RankName
import app.radianite.ui.common.SectionHeader
import app.radianite.ui.common.StatTile
import app.radianite.ui.common.WinGreen
import app.radianite.ui.common.countdown
import app.radianite.ui.common.plural
import app.radianite.ui.theme.Display
import app.radianite.ui.theme.Ink
import app.radianite.ui.theme.InkRaised
import app.radianite.ui.theme.Mono
import app.radianite.ui.theme.Muted
import app.radianite.ui.theme.ValorantRed
import app.radianite.ui.theme.rgbaColor
import coil3.compose.AsyncImage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CareerScreen(viewModel: CareerViewModel, onOpenLeaderboard: () -> Unit, onSignOut: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Career", fontWeight = FontWeight.Bold) },
                actions = { TextButton(onClick = onSignOut) { Text("Sign out", color = Muted) } },
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
            // A plain column: sections load at different times, and a lazy list would keep its scroll
            // anchored to whichever arrived first, leaving the profile above it off-screen.
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                state.message?.let { MessageBanner(it, Modifier.padding(bottom = 8.dp)) }
                state.career?.let { career ->
                    IdentityBanner(career)
                    AccountProgress(career)
                    career.rank?.let { RankCard(it, onOpenLeaderboard) }
                    if (career.rrTrend.size >= 2) RrTrend(career.rrTrend)
                }
                RecentStats(state.stats, state.statsLoading)
                state.career?.let { career ->
                    if (career.actHistory.isNotEmpty()) ActHistory(career.actHistory)
                    career.battlePass?.let { BattlePass(it) }
                    if (career.rank == null) {
                        TextButton(onClick = onOpenLeaderboard) { Text("View the leaderboard", color = ValorantRed) }
                    }
                }
                Box(Modifier.fillMaxWidth().padding(top = 16.dp), contentAlignment = Alignment.Center) { VersionLabel() }
            }
        }
    }
}

@Composable
private fun IdentityBanner(career: Career) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(112.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(InkRaised),
    ) {
        AsyncImage(
            model = CardArt.wide(career.cardId),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.matchParentSize(),
        )
        Box(Modifier.matchParentSize().background(Brush.horizontalGradient(listOf(Ink.copy(alpha = 0.85f), Color.Transparent))))
        Column(
            Modifier
                .align(Alignment.CenterStart)
                .padding(16.dp),
        ) {
            Text(career.riotId, fontFamily = Display, fontSize = 24.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            career.title?.let { Text(it, color = Muted, style = MaterialTheme.typography.labelLarge) }
        }
        career.level?.let { level ->
            Text(
                "LV $level",
                fontFamily = Mono,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(10.dp)
                    .background(Ink.copy(alpha = 0.75f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}

@Composable
private fun AccountProgress(career: Career) {
    Column(Modifier.padding(top = 12.dp)) {
        Row {
            Text("ACCOUNT XP", fontFamily = Mono, fontSize = 10.sp, color = Muted, modifier = Modifier.weight(1f))
            Text("%,d / %,d".format(career.xpIntoLevel, XP_PER_ACCOUNT_LEVEL), fontFamily = Mono, fontSize = 10.sp, color = Muted)
        }
        Spacer(Modifier.height(4.dp))
        ProgressBar(career.levelProgress, ValorantRed)
        career.nextFirstWinMillis?.let { next ->
            val remaining = next - System.currentTimeMillis()
            Text(
                if (remaining <= 0) "First-win bonus ready" else "Next first-win bonus in ${countdown(remaining)}",
                style = MaterialTheme.typography.labelMedium,
                color = if (remaining <= 0) WinGreen else Muted,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

@Composable
private fun RankCard(rank: RankStatus, onOpenLeaderboard: () -> Unit) {
    val content = LocalGameContent.current
    val color = remember(rank.tier, content) { rgbaColor(content?.rank(rank.tier)?.color)?.copy(alpha = 1f) ?: Muted }
    Column {
        SectionHeader("COMPETITIVE" + (rank.actName?.let { " · $it" } ?: ""))
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Brush.linearGradient(listOf(color.copy(alpha = 0.22f), InkRaised, InkRaised)))
                .padding(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RankIcon(rank.tier, 64.dp)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    RankName(rank.tier, fontSize = 26)
                    if (rank.rrCapped) {
                        Text("${rank.rankedRating} / $RR_PER_TIER RR", fontFamily = Mono, fontSize = 12.sp, color = Muted)
                        Spacer(Modifier.height(4.dp))
                        ProgressBar(rank.rankedRating / RR_PER_TIER.toFloat(), color)
                    } else {
                        Text("${rank.rankedRating} RR", fontFamily = Mono, fontWeight = FontWeight.Bold)
                    }
                }
            }
            if (rank.placementsRemaining > 0) {
                Text(
                    "${rank.placementsRemaining} placement games left this act",
                    style = MaterialTheme.typography.labelMedium,
                    color = Muted,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Darker tiles, so they stand out on the rank-colored card.
                val tile = Ink.copy(alpha = 0.6f)
                StatTile("WINS", "${rank.wins}", Modifier.weight(1f), background = tile)
                StatTile("WIN %", if (rank.games > 0) "${rank.winPercent}%" else "–", Modifier.weight(1f), background = tile)
                Column(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(tile)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    Text("ACT PEAK", fontFamily = Mono, fontSize = 10.sp, color = Muted)
                    RankIcon(rank.peakTier, 29.dp)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                if (rank.leaderboardRank > 0) {
                    Text("Leaderboard #${rank.leaderboardRank}", fontFamily = Mono, color = color, modifier = Modifier.weight(1f))
                } else {
                    Spacer(Modifier.weight(1f))
                }
                TextButton(onClick = onOpenLeaderboard) { Text("Leaderboard →", color = ValorantRed) }
            }
        }
    }
}

/** Bars for each recent ranked game's RR change, oldest on the left. */
@Composable
private fun RrTrend(trend: List<RankedRatingChange>) {
    val total = trend.sumOf { it.rrEarned }
    Column {
        Row(verticalAlignment = Alignment.Bottom) {
            SectionHeader("RR · LAST ${trend.size} RANKED", Modifier.weight(1f))
            Text(
                (if (total > 0) "+" else "") + "$total RR",
                fontFamily = Mono,
                fontSize = 12.sp,
                color = if (total >= 0) WinGreen else ValorantRed,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(80.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(InkRaised)
                .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            val maxAbs = trend.maxOf { kotlin.math.abs(it.rrEarned) }.coerceAtLeast(1)
            val mid = size.height / 2
            val slot = size.width / trend.size
            val barWidth = slot * 0.6f
            drawLine(Muted.copy(alpha = 0.3f), Offset(0f, mid), Offset(size.width, mid))
            trend.forEachIndexed { i, update ->
                val h = (update.rrEarned.toFloat() / maxAbs) * (mid - 2)
                val left = i * slot + (slot - barWidth) / 2
                drawRect(
                    color = if (update.rrEarned >= 0) WinGreen else ValorantRed,
                    topLeft = Offset(left, if (h >= 0) mid - h else mid),
                    size = Size(barWidth, kotlin.math.abs(h).coerceAtLeast(2f)),
                )
            }
        }
    }
}

@Composable
private fun RecentStats(stats: CareerStats?, loading: Boolean) {
    val content = LocalGameContent.current
    Column {
        SectionHeader("RECENT FORM" + (stats?.takeIf { it.matches > 0 }?.let { " · LAST ${it.matches} MATCHES" } ?: ""))
        when {
            stats == null && loading -> Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            }
            stats == null || stats.matches == 0 -> Text("Play a few matches to see your stats here.", color = Muted)
            else -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatTile("WIN %", "${stats.winPercent}%", Modifier.weight(1f))
                    StatTile("K/D", "%.2f".format(stats.kd), Modifier.weight(1f))
                    StatTile("ACS", "${stats.acs}", Modifier.weight(1f))
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatTile("ADR", "${stats.adr}", Modifier.weight(1f))
                    StatTile("HS%", stats.headshotPercent?.let { "$it%" } ?: "–", Modifier.weight(1f))
                    StatTile("KDA", "%.2f".format(stats.kda), Modifier.weight(1f))
                }
                if (stats.agents.isNotEmpty()) {
                    SectionHeader("TOP AGENTS")
                    stats.agents.take(4).forEach { agent ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 3.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(InkRaised)
                                .padding(8.dp),
                        ) {
                            AgentIcon(agent.agentId, 36.dp)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(content?.agent(agent.agentId)?.name ?: "Agent", fontWeight = FontWeight.SemiBold)
                                Text("${plural(agent.matches, "match", "matches")} · ${agent.winPercent}% wins", style = MaterialTheme.typography.labelSmall, color = Muted)
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text("%.2f K/D".format(agent.kd), fontFamily = Mono, fontSize = 12.sp)
                                Text("${agent.acs} ACS", fontFamily = Mono, fontSize = 11.sp, color = Muted)
                            }
                        }
                    }
                }
                if (stats.maps.isNotEmpty()) {
                    SectionHeader("MAPS")
                    stats.maps.forEach { map ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                            Text(content?.map(map.mapId)?.name ?: "Map", modifier = Modifier.width(96.dp), maxLines = 1)
                            ProgressBar(map.winPercent / 100f, if (map.winPercent >= 50) WinGreen else ValorantRed, Modifier.weight(1f))
                            Text(
                                "${map.winPercent}% · ${map.matches}",
                                fontFamily = Mono,
                                fontSize = 11.sp,
                                color = Muted,
                                modifier = Modifier.width(72.dp).padding(start = 8.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ActHistory(acts: List<ActRank>) {
    val content = LocalGameContent.current
    Column {
        SectionHeader("ACT RANKS")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
            acts.forEach { act ->
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .width(92.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(InkRaised)
                        .padding(8.dp),
                ) {
                    RankIcon(act.peakTier, 40.dp)
                    Text(content?.rank(act.peakTier)?.name ?: "UNRANKED", fontFamily = Mono, fontSize = 9.sp, maxLines = 1)
                    Text(act.actName, fontSize = 10.sp, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${act.wins}W / ${act.games}G", fontFamily = Mono, fontSize = 9.sp, color = Muted)
                }
            }
        }
    }
}

@Composable
private fun BattlePass(pass: BattlePassProgress) {
    Column {
        SectionHeader("BATTLE PASS")
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(InkRaised)
                .padding(16.dp),
        ) {
            Text(pass.name, fontWeight = FontWeight.SemiBold)
            Row(verticalAlignment = Alignment.Bottom) {
                Text("${pass.level}", fontFamily = Display, fontSize = 32.sp, color = ValorantRed)
                Text(" / ${pass.totalLevels}", fontFamily = Display, fontSize = 18.sp, color = Muted, modifier = Modifier.padding(bottom = 4.dp))
            }
            if (pass.level < pass.totalLevels && pass.xpForLevel > 0) {
                ProgressBar(pass.xpIntoLevel / pass.xpForLevel.toFloat(), ValorantRed)
                Text(
                    "%,d / %,d XP to level %d".format(pass.xpIntoLevel, pass.xpForLevel, pass.level + 1),
                    fontFamily = Mono,
                    fontSize = 10.sp,
                    color = Muted,
                    modifier = Modifier.padding(top = 4.dp),
                )
            } else if (pass.level >= pass.totalLevels) {
                Text("Complete", color = WinGreen)
            }
        }
    }
}
