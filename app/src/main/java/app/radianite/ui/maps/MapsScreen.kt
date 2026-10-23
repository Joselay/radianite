package app.radianite.ui.maps

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.radianite.data.comps.MapGuide
import app.radianite.ui.common.AccountsButton
import app.radianite.ui.common.LocalGameContent
import app.radianite.ui.common.MessageBanner
import app.radianite.ui.common.SectionHeader
import app.radianite.ui.common.WinGreen
import app.radianite.ui.common.Skeleton
import app.radianite.ui.theme.Display
import app.radianite.ui.theme.InkRaised
import app.radianite.ui.theme.Mono
import app.radianite.ui.theme.Muted
import coil3.compose.AsyncImage
import kotlin.math.roundToInt

/** Every map with the comp that wins most on it in Radiant ranked games; the ranked pool first. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapsScreen(viewModel: MapsViewModel, onOpenAccounts: () -> Unit, onOpenMap: (MapGuide) -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val guide = state.guide
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Maps", fontWeight = FontWeight.Bold) },
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
                state.message?.let { message -> item(key = "message") { MessageBanner(message) } }
                if (guide == null) {
                    items(3, key = { "skeleton-$it" }) { Skeleton(Modifier.fillMaxWidth().height(MAP_CARD_HEIGHT)) }
                } else {
                    val (pool, retired) = guide.maps.partition { it.inPool }
                    item(key = "legend") {
                        Text("The comp that wins the most on each map in ${guide.rankFilter} ranked games.", color = Muted, fontSize = 13.sp)
                    }
                    items(pool, key = { it.map }) { MapCard(it, onClick = { onOpenMap(it) }) }
                    if (retired.isNotEmpty()) {
                        item(key = "retired-header") { SectionHeader("NOT IN RANKED RIGHT NOW") }
                        items(retired, key = { it.map }) { MapCard(it, onClick = { onOpenMap(it) }) }
                    }
                    item(key = "footer") {
                        Text(
                            "${guide.rankFilter} ranked games from ${guide.sourceName}, updated ${readableDate(guide.updated)}.",
                            color = Muted,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                    }
                }
            }
        }
    }
}

/** The map's art and name over its best comp and how often that comp won. Opens the map's page. */
@Composable
private fun MapCard(map: MapGuide, onClick: () -> Unit) {
    val content = LocalGameContent.current
    val art = content?.mapPathNamed(map.map)?.let { content.map(it) }?.splash
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(InkRaised)
            .clickable(role = Role.Button, onClickLabel = "See the best comp for ${map.map}", onClick = onClick),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(104.dp),
        ) {
            AsyncImage(model = art, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, InkRaised))))
            Text(
                map.map.uppercase(),
                fontFamily = Display,
                fontSize = 26.sp,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(horizontal = 14.dp),
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .height(52.dp)
                .padding(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 12.dp),
        ) {
            CompFaces(map.best.agents, size = 34.dp)
            Spacer(Modifier.weight(1f))
            Text("${map.best.winPercent.roundToInt()}% WIN", fontFamily = Mono, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = WinGreen)
        }
    }
}

private val MAP_CARD_HEIGHT = 162.dp
