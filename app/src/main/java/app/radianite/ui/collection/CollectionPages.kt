package app.radianite.ui.collection

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.radianite.data.player.EquippedWeapon
import app.radianite.data.player.PlayerCollection
import app.radianite.data.store.StoreSkin
import app.radianite.ui.common.BackButton
import app.radianite.ui.common.plural
import app.radianite.ui.theme.Muted

/** The skins owned for one weapon, rarest first, with the equipped one marked. */
@Composable
fun WeaponSkinsScreen(weapon: EquippedWeapon, viewModel: CollectionViewModel, onOpenSkin: (StoreSkin) -> Unit, onBack: () -> Unit) {
    CollectionPage(viewModel, onBack, title = weapon.weaponName, subtitle = { plural(it.skinsFor(weapon.weaponId).size, "skin", "skins") }) { collection ->
        // The loadout may have changed since the tile was tapped; the fresh one decides what's equipped.
        val equipped = collection.loadout.find { it.weaponId == weapon.weaponId }?.skin?.skinUuid
        OwnedSkinGrid(collection.skinsFor(weapon.weaponId), onOpenSkin, empty = "No ${weapon.weaponName} skins yet.", equipped = equipped)
    }
}

/** Owned skins with levels or chromas still locked, most Radianite to spend first. */
@Composable
fun UpgradesScreen(viewModel: CollectionViewModel, onOpenSkin: (StoreSkin) -> Unit, onBack: () -> Unit) {
    CollectionPage(
        viewModel,
        onBack,
        title = "Skins to upgrade",
        subtitle = { c ->
            listOfNotNull(c.radianiteToMax?.let { "%,d RP to max".format(it) }, c.radianite?.let { "you have %,d".format(it) }).joinToString(" · ")
        },
    ) { collection ->
        OwnedSkinGrid(collection.toUpgrade, onOpenSkin, empty = "Every skin is fully upgraded.")
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CollectionPage(
    viewModel: CollectionViewModel,
    onBack: () -> Unit,
    title: String,
    subtitle: (PlayerCollection) -> String,
    content: @Composable (PlayerCollection) -> Unit,
) {
    BackHandler(onBack = onBack)
    val state by viewModel.state.collectAsStateWithLifecycle()
    val collection = state.collection
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(title, fontWeight = FontWeight.Bold)
                        collection?.let { Text(subtitle(it), style = MaterialTheme.typography.labelMedium, color = Muted) }
                    }
                },
                navigationIcon = { BackButton(onBack) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Box(
            Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            if (collection == null) CircularProgressIndicator(Modifier.align(Alignment.Center)) else content(collection)
        }
    }
}
