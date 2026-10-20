package app.radianite.update

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
import androidx.core.content.FileProvider
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
import app.radianite.data.update.AppRelease
import app.radianite.ui.theme.ValorantRed
import kotlinx.coroutines.CancellationException
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Looks for a new build of the app once a day and notifies about each one once, so friends running a build
 * sent to them hear about the next. Only release builds check: a debug build is a separate app.
 */
object UpdateCheck {
    private const val WORK_NAME = "update-check-daily"
    private const val CHANNEL_ID = "app_updates"
    private const val NOTIFICATION_ID = 1

    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<UpdateCheckWorker>(1, TimeUnit.DAYS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun createChannel(context: Context) {
        val channel = NotificationChannel(CHANNEL_ID, "App updates", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "When a new version of Radianite is out"
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    /** Says [release] is out; opening the app offers to install it. Quiet when notifications aren't allowed. */
    fun announce(context: Context, release: AppRelease) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val open = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = release.notes.ifEmpty { "Open Radianite to update." }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ValorantRed.toArgb())
            .setContentTitle("${release.title} is out")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }

    /** Hands a downloaded APK to Android's installer, which asks the user to confirm. */
    fun install(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}

class UpdateCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val updates = (applicationContext as RadianiteApp).container.updates ?: return Result.success()
        return try {
            updates.newer()?.let { release -> if (updates.firstAnnouncement(release)) UpdateCheck.announce(applicationContext, release) }
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Tomorrow's check will do.
            Log.w("Radianite", "Update check failed", e)
            Result.success()
        }
    }
}
