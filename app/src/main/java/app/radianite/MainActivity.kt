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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.radianite.ui.AppUiState
import app.radianite.ui.AppViewModel
import app.radianite.ui.home.HomeScreen
import app.radianite.ui.signin.CompletingSignInScreen
import app.radianite.ui.signin.RiotSignInScreen
import app.radianite.ui.signin.SignedOutScreen
import app.radianite.ui.theme.RadianiteTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as RadianiteApp).container
        setContent {
            RadianiteTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    RadianiteRoot(container, viewModel { AppViewModel(container.account, container.store) })
                }
            }
        }
    }
}

@Composable
private fun RadianiteRoot(container: AppContainer, viewModel: AppViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val featuredBundles by viewModel.featuredBundles.collectAsStateWithLifecycle()
    when (val s = state) {
        AppUiState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        is AppUiState.SignedOut -> SignedOutScreen(s.message, featuredBundles, onSignIn = viewModel::startSignIn)
        AppUiState.SigningIn -> RiotSignInScreen(onTokens = viewModel::onSignInRedirect, onCancel = viewModel::cancelSignIn)
        AppUiState.CompletingSignIn -> CompletingSignInScreen()
        is AppUiState.SignedIn -> HomeScreen(container, s, onSignOut = viewModel::signOut)
    }
}
