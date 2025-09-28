package com.notifryer.wear.notification

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.notifryer.notification.NotificationActionExecutor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class NotificationActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_TRIGGER) {
            Log.w(TAG, "Ignoring notification action due to mismatched intent action")
            return
        }

        val url = intent.getStringExtra(EXTRA_URL)
        if (url.isNullOrBlank()) {
            Log.w(TAG, "Ignoring notification action due to missing url")
            return
        }

        val pendingResult = goAsync()
        receiverScope.launch {
            try {
                NotificationActionExecutor.invokeGet(url)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val TAG = "WearAction"
        private const val ACTION_TRIGGER = "com.notifryer.wear.action.NOTIFICATION_TRIGGER"
        private const val EXTRA_URL = "extra_url"
        private val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        fun createPendingIntent(context: Context, requestCode: Int, url: String): PendingIntent {
            val intent = Intent(context, NotificationActionReceiver::class.java).apply {
                action = ACTION_TRIGGER
                putExtra(EXTRA_URL, url)
            }
            return PendingIntent.getBroadcast(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
    }
}

