package app.radianite.sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager

/**
 * Debug builds only: runs the background store sync now rather than at its next slot, to try store
 * alerts and the widget. `adb shell am broadcast -n app.radianite.debug/app.radianite.sync.RunStoreSyncReceiver`
 */
class RunStoreSyncReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        WorkManager.getInstance(context).enqueue(OneTimeWorkRequestBuilder<StoreSyncWorker>().build())
    }
}
