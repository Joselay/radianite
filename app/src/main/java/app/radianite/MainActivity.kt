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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.radianite.data.account.SavedAccount
import app.radianite.data.store.FeaturedBundle
import app.radianite.data.store.featuredBundles
import app.radianite.ui.AppUiState
import app.radianite.ui.AppViewModel
import app.radianite.ui.home.HomeScreen
import app.radianite.ui.signin.CompletingSignInScreen
import app.radianite.ui.signin.RiotSignInScreen
import app.radianite.ui.signin.SignedOutScreen
import app.radianite.ui.theme.RadianiteTheme
import app.radianite.ui.update.AvailableUpdate
import app.radianite.ui.update.LocalAvailableUpdate
import app.radianite.ui.update.UpdateDialog
import app.radianite.ui.update.UpdateViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as RadianiteApp).container
        setContent {
            RadianiteTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    RadianiteRoot(
                        container,
                        viewModel { AppViewModel(container.account, featuredBundles = { container.device.featuredBundles() }) },
                        viewModel { UpdateViewModel(container.updates, container::install) },
                    )
                }
            }
        }
    }
}

@Composable
private fun RadianiteRoot(container: AppContainer, viewModel: AppViewModel, updates: UpdateViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val featuredBundles by viewModel.bundles.collectAsStateWithLifecycle()
    val accounts by viewModel.accounts.collectAsStateWithLifecycle()
    val update by updates.state.collectAsStateWithLifecycle()
    // Each time the app comes to the front, so an update announced while it was in the background is offered.
    LifecycleStartEffect(updates) {
        updates.check()
        onStopOrDispose { }
    }
    val available = update.release?.let { AvailableUpdate(it.title, updates::ask) }
    CompositionLocalProvider(LocalAvailableUpdate provides available) {
        Screens(container, viewModel, state, featuredBundles, accounts)
    }
    if (update.asking) UpdateDialog(update, onUpdate = updates::update, onPutOff = updates::putOff)
}

@Composable
private fun Screens(
    container: AppContainer,
    viewModel: AppViewModel,
    state: AppUiState,
    featuredBundles: List<FeaturedBundle>,
    accounts: List<SavedAccount>,
) {
    when (val s = state) {
        AppUiState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        is AppUiState.SignedOut -> SignedOutScreen(
            message = s.message,
            featuredBundles = featuredBundles,
            accounts = accounts,
            onSignIn = viewModel::startSignIn,
            onPickAccount = viewModel::switchTo,
        )
        AppUiState.SigningIn -> RiotSignInScreen(onTokens = viewModel::onSignInRedirect, onCancel = viewModel::cancelSignIn)
        AppUiState.CompletingSignIn -> CompletingSignInScreen()
        is AppUiState.SignedIn -> HomeScreen(
            container = container,
            state = s,
            accounts = accounts,
            onSwitchAccount = viewModel::switchTo,
            onAddAccount = viewModel::startSignIn,
            onSignOut = viewModel::signOut,
        )
    }
}
