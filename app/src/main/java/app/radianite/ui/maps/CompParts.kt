package app.radianite.ui.maps

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.radianite.data.content.GameContent
import app.radianite.ui.common.AgentIcon
import app.radianite.ui.common.LocalGameContent
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** A comp's agents by role, duelists first, as teams line up in the agent select. */
fun GameContent?.byRole(agents: List<String>): List<String> =
    agents.sortedBy { name -> ROLES.indexOf(this?.agentIdNamed(name)?.let { agent(it)?.role }).let { if (it < 0) ROLES.size else it } }

/** A comp as a row of agent icons, read out by name. */
@Composable
fun CompFaces(agents: List<String>, size: Dp, modifier: Modifier = Modifier) {
    val content = LocalGameContent.current
    val ordered = content.byRole(agents)
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier.semantics(mergeDescendants = true) { contentDescription = ordered.joinToString() },
    ) {
        ordered.forEach { AgentIcon(content?.agentIdNamed(it), size) }
    }
}

/** "20 Oct 2026" from "2026-10-20"; the text as it is when it isn't a date. */
fun readableDate(iso: String): String =
    runCatching { LocalDate.parse(iso).format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)) }.getOrDefault(iso)

private val ROLES = listOf("Duelist", "Initiator", "Controller", "Sentinel")
