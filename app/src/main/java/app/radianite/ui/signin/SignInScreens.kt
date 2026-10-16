package app.radianite.ui.signin

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.provider.Settings
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import app.radianite.R
import app.radianite.data.riot.AuthTokens
import app.radianite.data.riot.RiotAuth
import app.radianite.data.store.FeaturedBundle
import app.radianite.ui.theme.Bone
import app.radianite.ui.theme.Display
import app.radianite.ui.theme.Ink
import app.radianite.ui.theme.InkRaised
import app.radianite.ui.theme.Mono
import app.radianite.ui.theme.Muted
import app.radianite.ui.theme.ValorantRed
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.sin

private val ValorantRedPressed = Color(0xFFE03A48)

/** Shown until the first store fetch saves the live featured bundles. */
private val FallbackFeaturedBundles = listOf(
    FeaturedBundle("Popmonz", "https://media.valorant-api.com/bundles/f67ab55a-4480-f305-9891-9bac1b17459f/verticalpromoimage.png"),
    FeaturedBundle("Champions 2026", "https://media.valorant-api.com/bundles/82a19267-4739-d2d0-2a6a-5db2e69e3d89/verticalpromoimage.png"),
)

private const val AUTO_ADVANCE_MILLIS = 5_000

private const val RIOT_LOGIN_ORIGIN = "https://authenticate.riotgames.com"

/**
 * Android WebView paints the login page's fixed, z-index:-1 background art over the sign-in card,
 * hiding the card, the social buttons' fills and the black text; Chrome stacks it correctly. Giving
 * the art's siblings their own layer restores Chrome's rendering. Style only: nothing reads the form.
 */
private const val RIOT_LOGIN_LAYER_FIX = """
    (function () {
      var style = document.createElement('style');
      style.textContent = '.page-background ~ * { position: relative; z-index: 0; }';
      (document.head || document.documentElement).appendChild(style);
    })();
"""

@Composable
fun SignedOutScreen(message: String?, featuredBundles: List<FeaturedBundle>, onSignIn: () -> Unit) {
    val motion = rememberMotionEnabled()
    val appear = remember { Animatable(if (motion) 0f else 1f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, tween(1_000, easing = FastOutSlowInEasing)) }

    // Staggered entrance: each step fades in and rises slightly after the one before.
    fun Modifier.reveal(step: Int) = graphicsLayer {
        val t = ((appear.value - step * 0.12f) / 0.52f).coerceIn(0f, 1f)
        alpha = t
        translationY = (1f - t) * 24.dp.toPx()
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Ink)
            .hudGrid(),
    ) {
        val compact = maxHeight < 720.dp
        // Fills the screen on tall phones, scrolls on short ones or with large font scales.
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .heightIn(min = maxHeight)
                .safeDrawingPadding()
                .padding(horizontal = 20.dp, vertical = 16.dp),
        ) {
            Row(Modifier.reveal(0), verticalAlignment = Alignment.CenterVertically) {
                BrandMark(size = 28)
                Spacer(Modifier.width(10.dp))
                Text("RADIANITE", fontFamily = Display, fontSize = 18.sp, letterSpacing = 2.sp, color = Bone)
                Spacer(Modifier.weight(1f))
                ResetChip(motion)
            }
            Spacer(Modifier.height(20.dp))
            FeaturedViewport(
                bundles = featuredBundles.ifEmpty { FallbackFeaturedBundles },
                motion = motion,
                bracketProgress = { ((appear.value - 0.1f) / 0.5f).coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .heightIn(min = 220.dp)
                    .reveal(1),
            )
            Spacer(Modifier.height(24.dp))
            Text(
                buildAnnotatedString {
                    append("CHECK YOUR\nSTORE")
                    withStyle(SpanStyle(color = ValorantRed)) { append(".") }
                },
                fontFamily = Display,
                fontSize = if (compact) 52.sp else 64.sp,
                lineHeight = if (compact) 54.sp else 66.sp,
                color = Bone,
                modifier = Modifier.reveal(2),
            )
            Spacer(Modifier.height(24.dp))
            Column(Modifier.reveal(3)) {
                message?.let {
                    ErrorBanner(it)
                    Spacer(Modifier.height(16.dp))
                }
                HudButton("SIGN IN WITH RIOT", onSignIn)
            }
        }
    }
}

/**
 * Swipeable featured-bundle art in a HUD frame. With several bundles it advances on its own with
 * story-style progress bars and a slow zoom, pauses while the user drags, and holds still when
 * system animations are off.
 */
@Composable
private fun FeaturedViewport(
    bundles: List<FeaturedBundle>,
    motion: Boolean,
    bracketProgress: () -> Float,
    modifier: Modifier,
) {
    val count = bundles.size
    val cycles = count > 1
    // A huge page count with a centred start lets the pager loop in both directions.
    val pager = rememberPagerState(
        initialPage = if (cycles) Int.MAX_VALUE / 2 - (Int.MAX_VALUE / 2) % count else 0,
    ) { if (cycles) Int.MAX_VALUE else count }
    val dragged by pager.interactionSource.collectIsDraggedAsState()
    // Progress of the settled page. A drag pauses it; it restarts only once a new page settles.
    val progress = remember { Animatable(0f) }
    var progressPage by remember { mutableIntStateOf(pager.settledPage) }
    LaunchedEffect(pager.settledPage, dragged, motion) {
        if (!cycles || !motion || dragged) return@LaunchedEffect
        if (progressPage != pager.settledPage) {
            progressPage = pager.settledPage
            progress.snapTo(0f)
        }
        val remaining = ((1f - progress.value) * AUTO_ADVANCE_MILLIS).toInt()
        progress.animateTo(1f, tween(remaining, easing = LinearEasing))
        pager.animateScrollToPage(pager.currentPage + 1, animationSpec = tween(700, easing = FastOutSlowInEasing))
    }
    val active = pager.currentPage % count

    Box(
        modifier
            .cornerBrackets(Bone.copy(alpha = 0.7f), bracketProgress)
            .padding(6.dp)
            .clip(CutCornerShape(bottomEnd = 24.dp))
            .background(InkRaised),
    ) {
        // Shows through if artwork can't load, so the frame never looks broken.
        Image(
            painterResource(R.drawable.ic_launcher_foreground),
            contentDescription = null,
            alpha = 0.1f,
            modifier = Modifier
                .size(200.dp)
                .align(Alignment.Center),
        )
        HorizontalPager(pager, beyondViewportPageCount = 1, userScrollEnabled = cycles, modifier = Modifier.fillMaxSize()) { page ->
            val bundle = bundles[page % count]
            AsyncImage(
                model = bundle.artUrl,
                contentDescription = "${bundle.name} bundle artwork",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        // Zoom only the page the progress belongs to, so pages don't jump scale mid-swipe.
                        val zoom = if (page == progressPage) 1f + 0.06f * progress.value else 1f
                        scaleX = zoom
                        scaleY = zoom
                    },
            )
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to Ink.copy(alpha = 0.92f))),
        )
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(14.dp),
        ) {
            Text(
                "FEATURED // ${bundles[active].name.uppercase()}",
                fontFamily = Mono,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = Bone,
            )
            if (cycles) {
                Spacer(Modifier.height(10.dp))
                ProgressBars(count, active) {
                    when {
                        !motion -> 1f
                        // Until the progress has been reset for the incoming page, its bar stays empty
                        // rather than briefly showing the previous page's fill.
                        pager.currentPage == progressPage -> progress.value
                        else -> 0f
                    }
                }
            }
        }
    }
}

/** One bar per bundle: earlier ones full, the active one filling, later ones empty. */
@Composable
private fun ProgressBars(count: Int, active: Int, activeFill: () -> Float) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(count) { i ->
            Box(
                Modifier
                    .weight(1f)
                    .height(2.dp)
                    .background(Bone.copy(alpha = 0.25f)),
            ) {
                Box(
                    Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(
                            when {
                                i < active -> 1f
                                i == active -> activeFill()
                                else -> 0f
                            },
                        )
                        .background(if (i == active) ValorantRed else Bone),
                )
            }
        }
    }
}

/** Time until the daily offers rotate, which happens at 00:00 UTC. */
@Composable
private fun ResetChip(motion: Boolean) {
    val remaining by produceState(msUntilUtcMidnight()) {
        while (true) {
            delay(1_000)
            value = msUntilUtcMidnight()
        }
    }
    val s = remaining / 1000
    Row(
        Modifier
            .border(1.dp, Bone.copy(alpha = 0.15f))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val blink by rememberInfiniteTransition(label = "blink").animateFloat(
            initialValue = 1f,
            targetValue = if (motion) 0.25f else 1f,
            animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
            label = "dot",
        )
        Box(
            Modifier
                .size(6.dp)
                .graphicsLayer { alpha = blink }
                .background(ValorantRed, CircleShape),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            "RESET %02d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60),
            fontFamily = Mono,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = Bone,
        )
    }
}

private fun msUntilUtcMidnight(): Long {
    val day = 24L * 60 * 60 * 1000
    return day - System.currentTimeMillis() % day
}

/** Shown while the tokens from Riot's redirect are exchanged for an account and the store. */
@Composable
fun CompletingSignInScreen() {
    val motion = rememberMotionEnabled()
    Box(
        Modifier
            .fillMaxSize()
            .background(Ink)
            .hudGrid(),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Radar(
                motion,
                Modifier
                    .fillMaxWidth(0.72f)
                    .widthIn(max = 320.dp)
                    .aspectRatio(1f),
            )
            Spacer(Modifier.height(40.dp))
            Text("SIGNING IN", fontFamily = Display, fontSize = 32.sp, letterSpacing = 3.sp, color = Bone)
            Spacer(Modifier.height(6.dp))
            Text(
                "// finding your account and today's store",
                fontFamily = Mono,
                fontSize = 12.sp,
                color = Muted,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** Minimap-style radar. The blips are decoration, not the user's offers. */
@Composable
private fun Radar(motion: Boolean, modifier: Modifier) {
    val sweep by rememberInfiniteTransition(label = "radar").animateFloat(
        initialValue = 0f,
        targetValue = if (motion) 360f else 0f,
        animationSpec = infiniteRepeatable(tween(2_400, easing = LinearEasing)),
        label = "sweep",
    )
    val blips = remember { listOf(35f to 0.62f, 145f to 0.45f, 215f to 0.7f, 310f to 0.35f) }
    Canvas(modifier) {
        val c = center
        val r = size.minDimension / 2
        listOf(1f, 0.66f, 0.33f).forEach { drawCircle(Bone.copy(alpha = 0.14f), r * it, c, style = Stroke(1.dp.toPx())) }
        drawLine(Bone.copy(alpha = 0.08f), Offset(c.x - r, c.y), Offset(c.x + r, c.y))
        drawLine(Bone.copy(alpha = 0.08f), Offset(c.x, c.y - r), Offset(c.x, c.y + r))
        rotate(sweep - 90f, c) {
            drawCircle(
                Brush.sweepGradient(
                    0f to Color.Transparent,
                    0.25f to ValorantRed.copy(alpha = 0.45f),
                    0.2501f to Color.Transparent,
                    1f to Color.Transparent,
                    center = c,
                ),
                r,
                c,
            )
        }
        val a = Math.toRadians(sweep.toDouble())
        drawLine(ValorantRed, c, Offset(c.x + r * cos(a).toFloat(), c.y + r * sin(a).toFloat()), 2.dp.toPx())
        for ((angle, distance) in blips) {
            val sinceSwept = ((sweep - angle) % 360f + 360f) % 360f
            val glow = (1f - sinceSwept / 240f).coerceIn(0.15f, 1f)
            val b = Math.toRadians(angle.toDouble())
            val p = Offset(c.x + r * distance * cos(b).toFloat(), c.y + r * distance * sin(b).toFloat())
            drawCircle(ValorantRed.copy(alpha = glow * 0.3f), 10.dp.toPx(), p)
            drawCircle(ValorantRed.copy(alpha = glow), 4.dp.toPx(), p)
        }
        drawCircle(Bone, 4.dp.toPx(), c)
    }
}

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RiotSignInScreen(onTokens: (AuthTokens) -> Unit, onCancel: () -> Unit) {
    val currentOnTokens by rememberUpdatedState(onTokens)
    val webViewHolder = remember { arrayOfNulls<WebView>(1) }
    var progress by remember { mutableIntStateOf(0) }
    var pageVisible by remember { mutableStateOf(false) }
    var loadFailed by remember { mutableStateOf(false) }
    var currentUrl by remember { mutableStateOf(RiotAuth.AUTHORIZE_URL) }

    BackHandler {
        val webView = webViewHolder[0]
        if (webView != null && webView.canGoBack() && !loadFailed) webView.goBack() else onCancel()
    }

    Scaffold(
        containerColor = Ink,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(painterResource(R.drawable.ic_close), contentDescription = "Cancel sign-in")
                    }
                },
                title = { AddressTitle(currentUrl) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Ink),
            )
        },
    ) { padding ->
        Box(
            Modifier
                .padding(padding)
                .consumeWindowInsets(padding)
                // Keeps Riot's username and password fields above the keyboard.
                .imePadding()
                .fillMaxSize(),
        ) {
            // A failed load drops the WebView; retrying composes a fresh one. That also covers a
            // crashed renderer, after which the old WebView can't be used again.
            if (!loadFailed) AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    WebView(context).apply {
                        webViewHolder[0] = this
                        // Matches the app background so there's no white flash before Riot's page paints.
                        setBackgroundColor(Ink.toArgb())
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                            WebViewCompat.addDocumentStartJavaScript(this, RIOT_LOGIN_LAYER_FIX, setOf(RIOT_LOGIN_ORIGIN))
                        }
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView, newProgress: Int) {
                                progress = newProgress
                            }
                        }
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
                                if (intercept(url)) {
                                    view.stopLoading()
                                } else {
                                    currentUrl = url
                                    super.onPageStarted(view, url, favicon)
                                }
                            }

                            override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                                if (!RiotAuth.isRedirect(url)) currentUrl = url
                            }

                            override fun onPageCommitVisible(view: WebView, url: String) {
                                pageVisible = true
                            }

                            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                                // The intercepted redirect is aborted on purpose; that isn't a failure.
                                if (request.isForMainFrame && !handled && !RiotAuth.isRedirect(request.url.toString())) {
                                    loadFailed = true
                                }
                            }

                            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                                loadFailed = true
                                return true
                            }
                        }
                        loadUrl(RiotAuth.AUTHORIZE_URL)
                    }
                },
                onRelease = { webView ->
                    webViewHolder[0] = null
                    webView.destroy()
                },
            )

            when {
                loadFailed -> LoadFailedPane(
                    onRetry = {
                        progress = 0
                        pageVisible = false
                        loadFailed = false
                    },
                    onCancel = onCancel,
                )
                !pageVisible -> Box(
                    Modifier
                        .fillMaxSize()
                        .background(Ink),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = ValorantRed, strokeWidth = 3.dp, modifier = Modifier.size(32.dp))
                        Spacer(Modifier.height(16.dp))
                        Text("// opening riot sign-in", fontFamily = Mono, fontSize = 12.sp, color = Muted)
                    }
                }
            }

            if (progress < 100 && !loadFailed) {
                LinearProgressIndicator(
                    progress = { progress / 100f },
                    color = ValorantRed,
                    trackColor = Color.Transparent,
                    drawStopIndicator = {},
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .align(Alignment.TopCenter),
                )
            }
        }
    }
}

/** Shows which site the user is typing into, so they can see it's Riot's own page. */
@Composable
private fun AddressTitle(url: String) {
    val uri = remember(url) { url.toUri() }
    Column {
        Text("RIOT SIGN-IN", fontFamily = Display, fontSize = 20.sp, letterSpacing = 1.5.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (uri.scheme == "https") {
                Icon(
                    painterResource(R.drawable.ic_lock),
                    contentDescription = "Secure connection",
                    tint = Muted,
                    modifier = Modifier.size(11.dp),
                )
                Spacer(Modifier.width(4.dp))
            }
            Text(uri.host.orEmpty(), fontFamily = Mono, fontSize = 11.sp, color = Muted)
        }
    }
}

@Composable
private fun LoadFailedPane(onRetry: () -> Unit, onCancel: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Ink)
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(painterResource(R.drawable.ic_error_outline), contentDescription = null, tint = ValorantRed, modifier = Modifier.size(40.dp))
        Spacer(Modifier.height(16.dp))
        Text("COULDN'T REACH RIOT", fontFamily = Display, fontSize = 28.sp, letterSpacing = 1.sp, color = Bone)
        Spacer(Modifier.height(4.dp))
        Text(
            "Check your connection and try again.",
            style = MaterialTheme.typography.bodyMedium,
            color = Muted,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        HudButton("TRY AGAIN", onRetry)
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onCancel) { Text("BACK", fontFamily = Mono, fontWeight = FontWeight.Bold, color = Bone) }
    }
}

/** The in-game button: a thin outer frame around a solid red inset that tightens when pressed. */
@Composable
private fun HudButton(text: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val haptics = LocalHapticFeedback.current
    val frame by animateColorAsState(if (pressed) Bone else Bone.copy(alpha = 0.35f), label = "frame")
    val inset by animateDpAsState(if (pressed) 3.dp else 5.dp, label = "inset")
    Box(
        Modifier
            .fillMaxWidth()
            .height(64.dp)
            .border(1.dp, frame)
            .clickable(interaction, indication = null, role = Role.Button) {
                haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                onClick()
            }
            .padding(inset)
            .background(if (pressed) ValorantRedPressed else ValorantRed),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text, fontFamily = Display, fontSize = 22.sp, letterSpacing = 3.sp, color = Color.White)
            Spacer(Modifier.width(10.dp))
            Icon(painterResource(R.drawable.ic_arrow_forward), contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
        }
    }
}

/** The launcher icon's crystal on its red tile, so the screen matches what the user tapped. */
@Composable
private fun BrandMark(size: Int) {
    Box(
        Modifier
            .size(size.dp)
            .background(ValorantRed, CutCornerShape(bottomEnd = (size / 4).dp)),
    ) {
        Image(painterResource(R.drawable.ic_launcher_foreground), contentDescription = null, modifier = Modifier.fillMaxSize())
    }
}

@Composable
private fun ErrorBanner(message: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(ValorantRed.copy(alpha = 0.12f))
            .drawBehind { drawRect(ValorantRed, size = size.copy(width = 3.dp.toPx())) }
            .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 12.dp),
    ) {
        Column {
            Text("// ERROR", fontFamily = Mono, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = ValorantRed)
            Spacer(Modifier.height(2.dp))
            Text(message, style = MaterialTheme.typography.bodyMedium, color = Bone)
        }
    }
}

private fun Modifier.hudGrid(): Modifier = drawBehind {
    val step = 32.dp.toPx()
    val line = Color.White.copy(alpha = 0.035f)
    var x = 0f
    while (x < size.width) {
        drawLine(line, Offset(x, 0f), Offset(x, size.height))
        x += step
    }
    var y = 0f
    while (y < size.height) {
        drawLine(line, Offset(0f, y), Offset(size.width, y))
        y += step
    }
}

/** Corner brackets that grow from each corner as [progress] goes from 0 to 1. */
private fun Modifier.cornerBrackets(color: Color, progress: () -> Float, arm: Dp = 16.dp): Modifier = drawBehind {
    val length = arm.toPx() * progress()
    if (length <= 0f) return@drawBehind
    val stroke = 1.5.dp.toPx()
    val w = size.width
    val h = size.height
    listOf(
        Offset(0f, 0f) to Offset(1f, 1f),
        Offset(w, 0f) to Offset(-1f, 1f),
        Offset(0f, h) to Offset(1f, -1f),
        Offset(w, h) to Offset(-1f, -1f),
    ).forEach { (corner, dir) ->
        drawLine(color, corner, Offset(corner.x + dir.x * length, corner.y), stroke)
        drawLine(color, corner, Offset(corner.x, corner.y + dir.y * length), stroke)
    }
}

/** False when the user has turned system animations off; autoplay and loops then hold still. */
@Composable
private fun rememberMotionEnabled(): Boolean {
    val context = LocalContext.current
    return remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f
    }
}
