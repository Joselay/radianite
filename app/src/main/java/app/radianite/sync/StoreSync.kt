package app.radianite.sync

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.compose.ui.graphics.toArgb
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.radianite.MainActivity
import app.radianite.R
import app.radianite.RadianiteApp
import app.radianite.data.Failure
import app.radianite.data.account.SavedAccount
import app.radianite.data.store.StoreSkin
import app.radianite.data.store.millisUntilStoreReset
import app.radianite.data.toFailure
import app.radianite.ui.theme.ValorantRed
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

/**
 * Fetches every signed-in account's store in the background once a day, a few minutes after it rotates, so
 * the home-screen widget shows today's offers and a wishlisted skin in any of them is announced. Once is
 * enough: daily offers only change at the rotation, and a Night Market runs for days.
 */
object StoreSync {
    private const val WORK_NAME = "store-sync-daily"

    // Builds before the daily schedule ran every six hours under this name.
    private const val OLD_WORK_NAME = "store-sync"
    private const val CHANNEL_ID = "store_alerts"
    private const val AFTER_RESET_MILLIS = 5 * 60 * 1000L

    /**
     * Runs daily a few minutes after 00:00 UTC, when the store rotates, with a network and enough battery.
     * Keeps an existing schedule, so opening the app doesn't move it.
     */
    fun schedule(context: Context) {
        val work = WorkManager.getInstance(context)
        work.cancelUniqueWork(OLD_WORK_NAME)
        val request = PeriodicWorkRequestBuilder<StoreSyncWorker>(1, TimeUnit.DAYS)
            .setInitialDelay(millisUntilStoreReset(System.currentTimeMillis()) + AFTER_RESET_MILLIS, TimeUnit.MILLISECONDS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .setRequiresBatteryNotLow(true)
                    .build(),
            )
            .build()
        work.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).run {
            cancelUniqueWork(WORK_NAME)
            cancelUniqueWork(OLD_WORK_NAME)
        }
    }

    fun createChannel(context: Context) {
        val channel = NotificationChannel(CHANNEL_ID, "Store alerts", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "When a wishlisted skin is in one of your stores"
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    /** Tells the user [skins] from their wishlist are on offer for [account]. Naming the account matters only when there are several. */
    fun announce(context: Context, account: SavedAccount, skins: List<StoreSkin>, nameAccount: Boolean) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val title = if (skins.size == 1) "${skins.single().name} is in your store" else "${skins.size} wishlisted skins are in your store"
        val where = if (nameAccount) " for ${account.riotId}" else ""
        val text = if (skins.size == 1) "On offer today$where." else skins.joinToString(", ") { it.name } + where
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ValorantRed.toArgb())
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        // One notification per account, replaced by its next one.
        NotificationManagerCompat.from(context).notify(account.puuid.hashCode(), notification)
    }
}

class StoreSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as RadianiteApp).container
        container.account.ready()
        val accounts = container.account.saved.value
        if (accounts.isEmpty()) return Result.success()
        container.wishlist.load()

        var retry = false
        for (account in accounts) {
            val snapshot = try {
                container.accountData(account.puuid).store.refresh()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // An expired session waits for the user to open the app; a passing problem is worth another try.
                val failure = e.toFailure()
                if (failure == Failure.Offline || failure == Failure.RateLimited || failure == Failure.Maintenance) retry = true
                Log.w("Radianite", "Background store fetch for ${account.riotId} failed: $failure")
                continue
            }
            val skins = container.wishlist.unannounced(snapshot.store)
            if (skins.isNotEmpty()) StoreSync.announce(applicationContext, account, skins, nameAccount = accounts.size > 1)
        }
        return if (retry && runAttemptCount < MAX_RETRIES) Result.retry() else Result.success()
    }

    private companion object {
        const val MAX_RETRIES = 3
    }
}
