package app.radianite.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp
import app.radianite.BuildConfig
import app.radianite.ui.theme.Mono
import app.radianite.ui.theme.Muted

/** Which build is installed, e.g. "v18 (291ea65)": its version code and commit. */
@Composable
fun VersionLabel(modifier: Modifier = Modifier) {
    Text("v${BuildConfig.VERSION_NAME}", fontFamily = Mono, fontSize = 10.sp, color = Muted.copy(alpha = 0.6f), modifier = modifier)
}
