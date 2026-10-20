package app.radianite.ui.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.radianite.BuildConfig
import app.radianite.ui.theme.InkRaised
import app.radianite.ui.theme.Muted
import app.radianite.ui.theme.ValorantRed

/** A newer build the user put off, for screens that offer it again; null when there's none. */
data class AvailableUpdate(val title: String, val open: () -> Unit)

val LocalAvailableUpdate = compositionLocalOf<AvailableUpdate?> { null }

/** Says what's new in the newer build, and downloads and installs it on request. */
@Composable
fun UpdateDialog(state: UpdateUiState, onUpdate: () -> Unit, onPutOff: () -> Unit) {
    val release = state.release ?: return
    val downloading = state.progress != null
    AlertDialog(
        onDismissRequest = { if (!downloading) onPutOff() },
        containerColor = InkRaised,
        title = { Text("${release.title} is out") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("You have v${BuildConfig.VERSION_NAME}.", color = Muted)
                if (release.notes.isNotEmpty()) {
                    Text(
                        release.notes,
                        modifier = Modifier
                            .heightIn(max = 240.dp)
                            .verticalScroll(rememberScrollState()),
                    )
                }
                state.progress?.let { done ->
                    if (done > 0f) {
                        LinearProgressIndicator(progress = { done }, color = ValorantRed, modifier = Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(color = ValorantRed, modifier = Modifier.fillMaxWidth())
                    }
                }
                state.message?.let { Text(it, color = ValorantRed) }
            }
        },
        confirmButton = {
            TextButton(onClick = onUpdate, enabled = !downloading) {
                Text(if (downloading) "Downloading…" else "Update", color = if (downloading) Muted else ValorantRed)
            }
        },
        dismissButton = { TextButton(onClick = onPutOff) { Text("Later", color = Muted) } },
    )
}
