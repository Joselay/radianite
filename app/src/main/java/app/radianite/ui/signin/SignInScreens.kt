package app.radianite.ui.signin

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.radianite.data.riot.AuthTokens
import app.radianite.data.riot.RiotAuth
import app.radianite.ui.theme.Muted
import app.radianite.ui.theme.ValorantRed

@Composable
fun SignedOutScreen(message: String?, onSignIn: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("RADIANITE", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Black, color = ValorantRed)
        Spacer(Modifier.height(8.dp))
        Text("Your VALORANT daily store, without launching the game.", textAlign = TextAlign.Center)
        Spacer(Modifier.height(32.dp))
        message?.let {
            Text(it, color = ValorantRed, textAlign = TextAlign.Center)
            Spacer(Modifier.height(16.dp))
        }
        Button(
            onClick = onSignIn,
            colors = ButtonDefaults.buttonColors(containerColor = ValorantRed),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Sign in with Riot") }
        Spacer(Modifier.height(16.dp))
        Text(
            "You sign in on Riot's own page; Radianite never reads or stores your password. " +
                "Store data comes from the game client's unofficial endpoints, which Riot doesn't " +
                "officially support, so use it at your own discretion.",
            style = MaterialTheme.typography.bodySmall,
            color = Muted,
            textAlign = TextAlign.Center,
        )
    }
}

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RiotSignInScreen(onTokens: (AuthTokens) -> Unit, onCancel: () -> Unit) {
    val currentOnTokens by rememberUpdatedState(onTokens)
    val webViewHolder = remember { arrayOfNulls<WebView>(1) }

    BackHandler {
        val webView = webViewHolder[0]
        if (webView != null && webView.canGoBack()) webView.goBack() else onCancel()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Riot sign-in") },
                actions = { TextButton(onClick = onCancel) { Text("Cancel", color = Muted) } },
            )
        },
    ) { padding ->
        AndroidView(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
            factory = { context ->
                WebView(context).apply {
                    webViewHolder[0] = this
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    webViewClient = object : WebViewClient() {
                        private var handled = false

                        private fun intercept(url: String): Boolean {
                            if (!RiotAuth.isRedirect(url)) return false
                            val tokens = RiotAuth.parseRedirect(url)
                            if (tokens != null && !handled) {
                                handled = true
                                currentOnTokens(tokens)
                            }
                            return true
                        }

                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                            request.isForMainFrame && intercept(request.url.toString())

                        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                            if (intercept(url)) view.stopLoading() else super.onPageStarted(view, url, favicon)
                        }
                    }
                    loadUrl(RiotAuth.AUTHORIZE_URL)
                }
            },
            onRelease = { webView ->
                webViewHolder[0] = null
                webView.stopLoading()
                webView.destroy()
            },
        )
    }
}
