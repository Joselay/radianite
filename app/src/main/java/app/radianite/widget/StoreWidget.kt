package app.radianite.widget

import android.content.Context
import android.graphics.Bitmap
import android.text.format.DateFormat
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import app.radianite.MainActivity
import app.radianite.RadianiteApp
import app.radianite.data.account.AccountState
import app.radianite.data.store.DailyStore
import app.radianite.data.store.StoreSkin
import app.radianite.ui.theme.Bone
import app.radianite.ui.theme.Ink
import app.radianite.ui.theme.InkRaised
import app.radianite.ui.theme.Muted
import app.radianite.ui.theme.ValorantRed
import app.radianite.ui.theme.rgbaColor
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
import kotlinx.coroutines.CancellationException
import java.util.Date

/**
 * Today's store for the shown account on the home screen, from the last store fetched, whether by the app
 * or the background sync. Redrawn after every fetch and account switch; tapping it opens the app.
 */
class StoreWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val container = (context.applicationContext as RadianiteApp).container
        container.account.ready()
        val shown = container.account.state.value as? AccountState.SignedIn
        val store = shown?.let { container.accountData(it.puuid).store.cached()?.store }
        val art = store?.skins.orEmpty().associate { it.levelUuid to bitmap(context, it.iconUrl) }
        val resetsAt = store?.let { DateFormat.getTimeFormat(context).format(Date(it.expiresAtMillis)) }
        provideContent { StoreWidgetContent(signedIn = shown != null, store = store, art = art, resetsAt = resetsAt) }
    }

    private suspend fun bitmap(context: Context, url: String?): Bitmap? {
        url ?: return null
        val request = ImageRequest.Builder(context)
            .data(url)
            .size(ART_WIDTH_PX, ART_HEIGHT_PX)
            // The widget host draws in another process, which can't use hardware bitmaps.
            .allowHardware(false)
            .build()
        return SingletonImageLoader.get(context).execute(request).image?.toBitmap()
    }

    companion object {
        private const val ART_WIDTH_PX = 320
        private const val ART_HEIGHT_PX = 160

        /** Redraws every placed widget; a widget that can't be drawn isn't worth failing the caller over. */
        suspend fun refresh(context: Context) {
            try {
                StoreWidget().updateAll(context)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("Radianite", "Store widget update failed", e)
            }
        }
    }
}

class StoreWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = StoreWidget()
}

@Composable
private fun StoreWidgetContent(signedIn: Boolean, store: DailyStore?, art: Map<String, Bitmap?>, resetsAt: String?) {
    val tall = LocalSize.current.height >= TALL_HEIGHT
    Column(
        GlanceModifier
            .fillMaxSize()
            .cornerRadius(16.dp)
            .background(Ink)
            .padding(12.dp)
            .clickable(actionStartActivity<MainActivity>()),
    ) {
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("TODAY'S STORE", style = TextStyle(color = ColorProvider(ValorantRed), fontSize = 11.sp, fontWeight = FontWeight.Bold))
            Spacer(GlanceModifier.defaultWeight())
            resetsAt?.let { Text("Resets $it", style = TextStyle(color = ColorProvider(Muted), fontSize = 11.sp)) }
        }
        Spacer(GlanceModifier.height(8.dp))
        val skins = store?.skins.orEmpty()
        if (skins.isEmpty()) {
            Box(GlanceModifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    if (signedIn) "Open Radianite to load today's store" else "Sign in to Radianite to see your store",
                    style = TextStyle(color = ColorProvider(Muted), fontSize = 13.sp),
                )
            }
        } else if (tall) {
            // Two rows of two, with names.
            skins.chunked(2).forEachIndexed { row, pair ->
                if (row > 0) Spacer(GlanceModifier.height(8.dp))
                Row(GlanceModifier.fillMaxWidth().defaultWeight()) {
                    pair.forEachIndexed { i, skin ->
                        if (i > 0) Spacer(GlanceModifier.width(8.dp))
                        SkinTile(skin, art[skin.levelUuid], showName = true, modifier = GlanceModifier.defaultWeight())
                    }
                }
            }
        } else {
            // One row of four: art and price only.
            Row(GlanceModifier.fillMaxWidth().defaultWeight()) {
                skins.forEachIndexed { i, skin ->
                    if (i > 0) Spacer(GlanceModifier.width(6.dp))
                    SkinTile(skin, art[skin.levelUuid], showName = false, modifier = GlanceModifier.defaultWeight())
                }
            }
        }
    }
}

@Composable
private fun SkinTile(skin: StoreSkin, art: Bitmap?, showName: Boolean, modifier: GlanceModifier) {
    val tierColor = rgbaColor(skin.tierColor)?.copy(alpha = 1f) ?: Bone
    Column(
        modifier.fillMaxSize().cornerRadius(10.dp).background(InkRaised).padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(GlanceModifier.fillMaxWidth().defaultWeight(), contentAlignment = Alignment.Center) {
            if (art != null) {
                Image(ImageProvider(art), contentDescription = skin.name, contentScale = ContentScale.Fit, modifier = GlanceModifier.fillMaxSize())
            }
        }
        if (showName) {
            Text(skin.name, maxLines = 1, style = TextStyle(color = ColorProvider(Bone), fontSize = 11.sp, fontWeight = FontWeight.Medium))
        }
        skin.priceVp?.let { Text("%,d VP".format(it), maxLines = 1, style = TextStyle(color = ColorProvider(tierColor), fontSize = 11.sp, fontWeight = FontWeight.Bold)) }
    }
}

private val TALL_HEIGHT = 170.dp
