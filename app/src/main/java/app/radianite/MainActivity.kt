package app.radianite

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.radianite.ui.signin.CompletingSignInScreen
import app.radianite.ui.signin.RiotSignInScreen
import app.radianite.ui.signin.SignedOutScreen
import app.radianite.ui.skin.SkinDetailScreen
import app.radianite.ui.skin.SkinDetailViewModel
import app.radianite.ui.store.AppUiState
import app.radianite.ui.store.StoreScreen
import app.radianite.ui.store.StoreViewModel
import app.radianite.ui.theme.RadianiteTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as RadianiteApp).container
        setContent {
            RadianiteTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    RadianiteRoot(container, viewModel { StoreViewModel(container.storeRepository) })
                }
            }
        }
    }
}

@Composable
private fun RadianiteRoot(container: AppContainer, viewModel: StoreViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var openSkinLevel by rememberSaveable { mutableStateOf<String?>(null) }
    when (val s = state) {
        AppUiState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        is AppUiState.SignedOut -> SignedOutScreen(s.message, onSignIn = viewModel::startSignIn)
        AppUiState.SigningIn -> RiotSignInScreen(onTokens = viewModel::onSignInRedirect, onCancel = viewModel::cancelSignIn)
        AppUiState.CompletingSignIn -> CompletingSignInScreen()
        is AppUiState.Store -> {
            val openSkin = s.store?.skins?.find { it.levelUuid == openSkinLevel }
            val skinUuid = openSkin?.skinUuid
            if (openSkin != null && skinUuid != null) {
                SkinDetailScreen(
                    skin = openSkin,
                    viewModel = viewModel(key = skinUuid) { SkinDetailViewModel(container.skinDetailRepository, skinUuid) },
                    newPlayer = container::newVideoPlayer,
                    onBack = { openSkinLevel = null },
                )
            } else {
                StoreScreen(
                    s,
                    onRefresh = viewModel::refresh,
                    onSignOut = viewModel::signOut,
                    onOpenSkin = { openSkinLevel = it.levelUuid },
                )
            }
        }
    }
}
