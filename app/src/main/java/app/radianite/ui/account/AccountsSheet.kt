package app.radianite.ui.account

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.radianite.R
import app.radianite.data.account.SavedAccount
import app.radianite.data.content.CardArt
import app.radianite.ui.VersionLabel
import app.radianite.ui.common.SectionHeader
import app.radianite.ui.theme.Display
import app.radianite.ui.theme.Ink
import app.radianite.ui.theme.InkRaised
import app.radianite.ui.theme.Muted
import app.radianite.ui.theme.ValorantRed
import coil3.compose.AsyncImage

/**
 * The signed-in accounts: tap one to show it, add another, or sign the shown one out. Signing out asks
 * first, since signing back in goes through Riot's page again.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountsSheet(
    accounts: List<SavedAccount>,
    shownPuuid: String,
    onSwitch: (puuid: String) -> Unit,
    onAddAccount: () -> Unit,
    onSignOut: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var confirmingSignOut by remember { mutableStateOf(false) }
    val shown = accounts.find { it.puuid == shownPuuid }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = InkRaised) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp),
        ) {
            SectionHeader("SIGNED IN ON THIS DEVICE")
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                accounts.forEach { account ->
                    val selected = account.puuid == shownPuuid
                    AccountRow(
                        riotId = account.riotId,
                        cardId = account.cardId,
                        selected = selected,
                        onClick = {
                            if (!selected) onSwitch(account.puuid)
                            onDismiss()
                        },
                    )
                }
                SheetAction(R.drawable.ic_add, "Add another account") {
                    onDismiss()
                    onAddAccount()
                }
            }
            if (shown != null) {
                HorizontalDivider(Modifier.padding(vertical = 12.dp), color = Muted.copy(alpha = 0.2f))
                TextButton(onClick = { confirmingSignOut = true }) {
                    Text("Sign out of ${shown.riotId}", color = ValorantRed, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            VersionLabel(Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp))
        }
    }

    if (confirmingSignOut && shown != null) {
        val others = accounts.size > 1
        AlertDialog(
            onDismissRequest = { confirmingSignOut = false },
            containerColor = InkRaised,
            title = { Text("Sign out of ${shown.riotId}?") },
            text = {
                Text(
                    "Its saved store and matches are removed from this device." +
                        if (others) " Radianite switches to your next account." else "",
                    color = Muted,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmingSignOut = false
                    onDismiss()
                    onSignOut()
                }) { Text("Sign out", color = ValorantRed) }
            },
            dismissButton = { TextButton(onClick = { confirmingSignOut = false }) { Text("Cancel", color = Muted) } },
        )
    }
}

@Composable
private fun AccountRow(riotId: String, cardId: String?, selected: Boolean, onClick: () -> Unit) {
    val (name, tag) = riotId.split('#', limit = 2).let { it[0] to it.getOrNull(1) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) ValorantRed.copy(alpha = 0.14f) else Ink)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .semantics { this.selected = selected }
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        // The account's player card, over its initial, which shows until the card loads or if it can't.
        Box(
            Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (selected) ValorantRed else InkRaised)
                .then(if (selected) Modifier.border(2.dp, ValorantRed, RoundedCornerShape(6.dp)) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            Text(name.take(1).uppercase(), fontFamily = Display, fontSize = 18.sp)
            AsyncImage(model = CardArt.small(cardId), contentDescription = null, modifier = Modifier.matchParentSize())
        }
        Spacer(Modifier.width(12.dp))
        Row(Modifier.weight(1f), verticalAlignment = Alignment.Bottom) {
            Text(name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            tag?.let { Text(" #$it", color = Muted, fontSize = 13.sp) }
        }
        if (selected) Icon(painterResource(R.drawable.ic_check), contentDescription = null, tint = ValorantRed, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun SheetAction(icon: Int, label: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
            Icon(painterResource(icon), contentDescription = null, tint = Muted)
        }
        Spacer(Modifier.width(12.dp))
        Text(label)
    }
}
