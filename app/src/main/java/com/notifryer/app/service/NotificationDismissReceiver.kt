package com.notifryer.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.notifryer.app.data.AppSettingsRepository
import com.notifryer.notification.NotificationEventType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class NotificationDismissReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_DISMISS_STATUS) return
        val topic = intent.getStringExtra(EXTRA_TOPIC)?.trim()?.takeIf { it.isNotEmpty() } ?: return
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val eventType = NotificationEventType.fromRaw(intent.getStringExtra(EXTRA_EVENT_TYPE))
                val repository = AppSettingsRepository(context.applicationContext)
                repository.removeStatus(eventType, topic)
                if (eventType == NotificationEventType.ONGOING) {
                    repository.updateWearableStatus(null, null)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val ACTION_DISMISS_STATUS = "com.notifryer.app.action.DISMISS_STATUS"
        const val EXTRA_EVENT_TYPE = "extra_event_type"
        const val EXTRA_TOPIC = "extra_topic"
    }
}
