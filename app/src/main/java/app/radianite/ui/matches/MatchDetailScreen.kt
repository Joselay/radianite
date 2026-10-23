package app.radianite.ui.matches

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.radianite.data.match.MatchSummary
import app.radianite.data.match.PlayerRecord
import app.radianite.data.match.RoundRecord
import app.radianite.ui.common.BackButton
import app.radianite.ui.common.AgentIcon
import app.radianite.ui.common.LocalGameContent
import app.radianite.ui.common.RankIcon
import app.radianite.ui.common.SectionHeader
import app.radianite.ui.common.StatTile
import app.radianite.ui.common.WinGreen
import app.radianite.ui.common.color
import app.radianite.ui.common.label
import app.radianite.ui.common.shortDuration
import app.radianite.ui.common.signed
import app.radianite.ui.theme.Display
import app.radianite.ui.theme.Ink
import app.radianite.ui.theme.InkRaised
import app.radianite.ui.theme.Mono
import app.radianite.ui.theme.Muted
import app.radianite.ui.theme.ValorantRed
import coil3.compose.AsyncImage
import java.text.DateFormat
import java.util.Date

/** [knownRiotIds] names the accounts signed in on this device by PUUID, the only players Riot's blank names can be filled in for. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MatchDetailScreen(match: MatchSummary, knownRiotIds: Map<String, String>, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val content = LocalGameContent.current
    val record = match.record
    val me = match.me
    val myTeam = match.myTeamId

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(content?.map(record.mapId)?.name ?: "Match", fontWeight = FontWeight.Bold) },
                navigationIcon = { BackButton(onBack) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(bottom = 24.dp),
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            item(key = "header") { Header(match) }
            item(key = "me") {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    SectionHeader("YOUR PERFORMANCE")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatTile("K / D / A", "${me.kills}/${me.deaths}/${me.assists}", Modifier.weight(1.4f))
                        StatTile("K/D", "%.2f".format(me.kd), Modifier.weight(1f))
                        StatTile("ACS", me.acs.toString(), Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatTile("ADR", me.adr.toString(), Modifier.weight(1f))
                        StatTile("HS%", me.headshotPercent?.let { "$it%" } ?: "–", Modifier.weight(1f))
                        StatTile("FK / FD", "${me.firstKills}/${me.firstDeaths}", Modifier.weight(1f))
                    }
                }
            }
            if (record.isTeamMode && record.rounds.isNotEmpty() && myTeam != null) {
                item(key = "rounds") {
                    Column {
                        SectionHeader("ROUNDS", Modifier.padding(horizontal = 16.dp))
                        RoundTimeline(record.rounds, myTeam, record.halfRounds)
                    }
                }
            }
            match.scoreboard().forEach { board ->
                item(key = "team-${board.teamId}") {
                    val team = board.team
                    val title = when {
                        !record.isTeamMode -> "SCOREBOARD"
                        board.isMine -> "YOUR TEAM"
                        else -> "ENEMY TEAM"
                    }
                    Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.Bottom) {
                        SectionHeader(title, Modifier.weight(1f))
                        if (team != null && record.isTeamMode) {
                            Text(
                                team.roundsWon.toString(),
                                fontFamily = Display,
                                fontSize = 18.sp,
                                color = if (team.won) WinGreen else ValorantRed,
                                modifier = Modifier.padding(bottom = 6.dp),
                            )
                        }
                    }
                    ScoreboardHeader()
                }
                items(board.players, key = { "player-" + it.puuid }) { player ->
                    val isMe = player.puuid == me.puuid
                    ScoreboardRow(
                        player,
                        isMe = isMe,
                        // Everyone queues in a party of their own, so a shared one means they queued together.
                        inMyParty = !isMe && me.partyId.isNotBlank() && player.partyId == me.partyId,
                        knownRiotId = knownRiotIds[player.puuid],
                        showRank = record.isRanked,
                    )
                }
            }
        }
    }
}

@Composable
private fun Header(match: MatchSummary) {
    val content = LocalGameContent.current
    val rr = match.rr
    val record = match.record
    val accent = match.outcome.color()
    Box(
        Modifier
            .fillMaxWidth()
            .height(170.dp),
    ) {
        AsyncImage(
            model = content?.map(record.mapId)?.splash,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.matchParentSize(),
        )
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(Ink.copy(alpha = 0.3f), Ink))))
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .padding(16.dp),
        ) {
            Text(match.outcome.label(), fontFamily = Mono, fontSize = 12.sp, letterSpacing = 2.sp, color = accent)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(match.scoreLine, fontFamily = Display, fontSize = 44.sp, color = accent)
                if (rr != null) {
                    Spacer(Modifier.width(16.dp))
                    RankIcon(rr.tierAfter, 28.dp, Modifier.padding(bottom = 10.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "${signed(rr.rrEarned)} RR",
                        fontFamily = Mono,
                        fontWeight = FontWeight.Bold,
                        color = if (rr.rrEarned >= 0) WinGreen else ValorantRed,
                        modifier = Modifier.padding(bottom = 12.dp),
                    )
                }
            }
            val details = listOfNotNull(
                content?.queueName(record.queueId) ?: record.queueId,
                record.lengthMillis?.let(::shortDuration),
                DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(record.startMillis)),
            )
            Text(details.joinToString(" · "), style = MaterialTheme.typography.labelMedium, color = Muted)
        }
    }
}

/** One chip per round: green for rounds your team won, red for lost, with how it ended. */
@Composable
private fun RoundTimeline(rounds: List<RoundRecord>, myTeam: String, halfRounds: Int) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        rounds.forEachIndexed { index, round ->
            // A gap where sides swap; overtime rounds follow without one.
            if (index == halfRounds) Spacer(Modifier.width(8.dp))
            val won = round.winningTeam == myTeam
            // Read as "Round 3, won, elimination" rather than a letter and a number.
            val spoken = "Round ${index + 1}, ${if (won) "won" else "lost"}, ${roundEnding(round.resultCode)}"
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clearAndSetSemantics { contentDescription = spoken }) {
                Box(
                    Modifier
                        .size(width = 22.dp, height = 28.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (won) WinGreen.copy(alpha = 0.25f) else ValorantRed.copy(alpha = 0.22f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(roundGlyph(round.resultCode), fontFamily = Mono, fontSize = 11.sp, color = if (won) WinGreen else ValorantRed)
                }
                Text((index + 1).toString(), fontFamily = Mono, fontSize = 9.sp, color = Muted)
            }
        }
    }
    Text(
        "E elimination · S spike detonated · D defused · T time ran out",
        fontFamily = Mono,
        fontSize = 9.sp,
        color = Muted,
        // The rounds speak for themselves to a screen reader; the key is only for the glyphs.
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).clearAndSetSemantics { },
    )
}

private fun roundEnding(resultCode: String) = when (resultCode) {
    "Elimination" -> "elimination"
    "Detonate" -> "spike detonated"
    "Defuse" -> "spike defused"
    "Surrendered" -> "surrender"
    else -> "time ran out"
}

private fun roundGlyph(resultCode: String) = when (resultCode) {
    "Elimination" -> "E"
    "Detonate" -> "S"
    "Defuse" -> "D"
    "Surrendered" -> "FF"
    else -> "T"
}

@Composable
private fun ScoreboardHeader() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Text("PLAYER", fontFamily = Mono, fontSize = 9.sp, color = Muted, modifier = Modifier.weight(1f))
        listOf("ACS", "K/D/A", "HS%", "ADR").forEachIndexed { i, label ->
            Text(label, fontFamily = Mono, fontSize = 9.sp, color = Muted, textAlign = TextAlign.End, modifier = Modifier.width(COLUMN_WIDTHS[i]))
        }
    }
}

@Composable
private fun ScoreboardRow(player: PlayerRecord, isMe: Boolean, inMyParty: Boolean, knownRiotId: String?, showRank: Boolean) {
    val content = LocalGameContent.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (isMe) ValorantRed.copy(alpha = 0.14f) else InkRaised)
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        AgentIcon(player.agentId, 30.dp)
        Spacer(Modifier.width(8.dp))
        val agentName = content?.agent(player.agentId)?.name
        val ranked = showRank && player.competitiveTier > 0
        Column(Modifier.weight(1f)) {
            Text(
                player.displayName(isMe, agentName, knownRiotId),
                fontWeight = if (isMe) FontWeight.Bold else FontWeight.Medium,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (ranked) {
                    RankIcon(player.competitiveTier, 14.dp)
                    Spacer(Modifier.width(4.dp))
                }
                if (inMyParty) {
                    Text("PARTY", fontFamily = Mono, fontSize = 9.sp, fontWeight = FontWeight.Bold, color = ValorantRed)
                    Spacer(Modifier.width(6.dp))
                }
                val detail = listOfNotNull(
                    agentName?.takeIf { player.hasName || isMe || knownRiotId != null },
                    content?.rank(player.competitiveTier)?.name?.takeIf { ranked },
                ).joinToString(" · ")
                Text(detail, fontSize = 10.sp, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        val values = listOf(
            player.acs.toString(),
            "${player.kills}/${player.deaths}/${player.assists}",
            player.headshotPercent?.let { "$it%" } ?: "–",
            player.adr.toString(),
        )
        values.forEachIndexed { i, value ->
            Text(
                value,
                fontFamily = Mono,
                fontSize = 11.sp,
                textAlign = TextAlign.End,
                color = if (i == 0) Color.White else MaterialTheme.colorScheme.onBackground,
                fontWeight = if (i == 0) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier.width(COLUMN_WIDTHS[i]),
            )
        }
    }
}

private val COLUMN_WIDTHS = listOf(36.dp, 64.dp, 36.dp, 36.dp)
