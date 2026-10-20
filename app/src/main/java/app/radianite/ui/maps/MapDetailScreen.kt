package app.radianite.ui.maps

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.radianite.R
import app.radianite.data.comps.BestComp
import app.radianite.data.comps.CompGuide
import app.radianite.data.comps.MapGuide
import app.radianite.data.match.AgentResult
import app.radianite.data.match.MapPlay
import app.radianite.ui.common.AgentIcon
import app.radianite.ui.common.BackButton
import app.radianite.ui.common.LocalGameContent
import app.radianite.ui.common.SectionHeader
import app.radianite.ui.common.StatTile
import app.radianite.ui.common.WinGreen
import app.radianite.ui.common.plural
import app.radianite.ui.theme.Display
import app.radianite.ui.theme.Ink
import app.radianite.ui.theme.InkRaised
import app.radianite.ui.theme.Mono
import app.radianite.ui.theme.Muted
import app.radianite.ui.theme.ValorantRed
import coil3.compose.AsyncImage

/**
 * One map: its best comp in Radiant ranked games and why it's the best, then how the map and the player's agents have
 * gone in their own games. [play] is null when none of those games are on the phone.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapDetailScreen(map: MapGuide, guide: CompGuide, play: MapPlay?, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val listState = rememberLazyListState()
    // The hero already names the map; the bar takes the name only once the hero scrolls away.
    val heroGone by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 } }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    AnimatedVisibility(heroGone, enter = fadeIn(), exit = fadeOut()) {
                        Text(map.map, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                navigationIcon = { BackButton(onBack) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            item(key = "hero") { Hero(map.map) }
            item(key = "best-header") { SectionHeader("BEST COMP") }
            item(key = "best") { CompCard(map.best, "%,d ${guide.rankFilter} games · ${map.period}".format(map.best.matches)) }
            item(key = "why") {
                SourceLine(
                    title = "Why this one",
                    detail = "Of the comps at least 1 in 100 ${guide.rankFilter} teams played on ${map.map}, it won the most, " +
                        "counting how many games each had, so a lucky streak can't top it.",
                    link = guide.sourceName.uppercase(),
                    url = guide.sourceUrl,
                )
            }
            when {
                !map.inPool -> item(key = "older") { Note("Ranked doesn't play ${map.map} right now; this is from the last act it did.") }
                !map.thisAct -> item(key = "older") {
                    Note("Too few players have reached ${guide.rankFilter} this act to say yet; this is from the last act with enough ${guide.rankFilter} games on ${map.map}.")
                }
            }

            item(key = "yours-header") { SectionHeader("IN YOUR GAMES") }
            if (play == null) {
                item(key = "yours-empty") {
                    Text(
                        "None of your competitive, unrated or Premier games on ${map.map} are on this phone yet. They're added as you open the app after playing.",
                        color = Muted,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            } else {
                item(key = "yours-record") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatTile("RECORD", "${play.wins}W ${play.matches - play.wins}L", Modifier.weight(1f))
                        StatTile("WIN %", "${play.winPercent}%", Modifier.weight(1f), valueColor = if (play.winPercent >= 50) WinGreen else ValorantRed)
                    }
                }
                item(key = "mine-header") { SubHeader("YOUR AGENTS HERE") }
                items(play.mine, key = { "mine-" + it.agentId }) { AgentResultRow(it) }
            }
        }
    }
}

@Composable
private fun Hero(name: String) {
    val content = LocalGameContent.current
    val art = content?.mapPathNamed(name)?.let { content.map(it) }?.splash
    Box(
        Modifier
            .fillMaxWidth()
            .height(180.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(InkRaised),
    ) {
        AsyncImage(model = art, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Ink.copy(alpha = 0.85f)))))
        Text(
            name.uppercase(),
            fontFamily = Display,
            fontSize = 32.sp,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(14.dp),
        )
    }
}

/** [title] over [detail], with a link to where the numbers come from. */
@Composable
private fun SourceLine(title: String, detail: String, link: String, url: String) {
    val uriHandler = LocalUriHandler.current
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(detail, color = Muted, fontSize = 12.sp)
        }
        Text(
            "$link ↗",
            fontFamily = Mono,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = ValorantRed,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .clickable(role = Role.Button, onClickLabel = "Open the source") { uriHandler.openUri(url) }
                .padding(horizontal = 6.dp, vertical = 4.dp),
        )
    }
}

/** Something to keep in mind about the numbers above. */
@Composable
private fun Note(text: String) {
    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier
            .fillMaxWidth()
            .background(Muted.copy(alpha = 0.12f), RoundedCornerShape(8.dp))
            .padding(12.dp),
    ) {
        Icon(painterResource(R.drawable.ic_error_outline), contentDescription = null, tint = Muted, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodySmall)
    }
}

/** The comp's five agents with their names and roles, over how much it was played and how often it won. */
@Composable
private fun CompCard(comp: BestComp, usage: String) {
    val content = LocalGameContent.current
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(InkRaised)
            .padding(12.dp),
    ) {
        Row(Modifier.fillMaxWidth()) {
            content.byRole(comp.agents).forEach { name ->
                val agent = content?.agentIdNamed(name)
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                    AgentIcon(agent, 48.dp)
                    Spacer(Modifier.height(4.dp))
                    Text(name, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                    Text(
                        agent?.let { content.agent(it)?.role }.orEmpty().uppercase(),
                        fontFamily = Mono,
                        fontSize = 8.sp,
                        color = Muted,
                        maxLines = 1,
                    )
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(usage, color = Muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
            Text("WON %.1f%%".format(comp.winPercent), fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = WinGreen)
        }
    }
}

/** An agent's games and win rate. */
@Composable
private fun AgentResultRow(result: AgentResult) {
    val content = LocalGameContent.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(InkRaised)
            .padding(8.dp),
    ) {
        AgentIcon(result.agentId, 36.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(content?.agent(result.agentId)?.name ?: "Agent", fontWeight = FontWeight.SemiBold)
            Text(
                plural(result.picks, "game", "games"),
                style = MaterialTheme.typography.labelSmall,
                color = Muted,
            )
        }
        Text(
            "${result.winPercent}%",
            fontFamily = Mono,
            fontWeight = FontWeight.Bold,
            color = if (result.winPercent >= 50) WinGreen else ValorantRed,
        )
    }
}

@Composable
private fun SubHeader(text: String) {
    Text(text, fontFamily = Mono, fontSize = 10.sp, letterSpacing = 1.sp, color = Muted, modifier = Modifier.padding(top = 8.dp))
}
