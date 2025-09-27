package com.notifryer.app.service

import android.util.Log
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.notifryer.app.MessagingConstants
import com.notifryer.app.R
import com.notifryer.app.data.AppSettingsRepository
import java.time.Instant
import com.notifryer.notification.NotificationCoordinator
import com.notifryer.notification.NotificationEvent
import com.notifryer.notification.NotificationEventType
import com.notifryer.app.service.AppMissingStatusScheduler
import com.notifryer.app.service.createAppNotificationConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

class NotifryerMessagingService : FirebaseMessagingService() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val json = Json { ignoreUnknownKeys = true }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.i(TAG, "FCM token refreshed; ensuring topic subscription")
        serviceScope.launch {
            runCatching {
                FirebaseMessaging.getInstance().subscribeToTopic(MessagingConstants.STATUS_TOPIC).await()
                Log.i(TAG, "Subscribed to topic ${MessagingConstants.STATUS_TOPIC} after token refresh")
            }.onFailure {
                Log.e(TAG, "Failed to re-subscribe to topic after token refresh", it)
            }
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val payloadRaw = message.data["payload"]
        val events = parseEvents(payloadRaw)
        if (events.isEmpty()) {
            Log.w(TAG, "No events in payload: ${message.data}")
            return
        }

        serviceScope.launch {
            val repository = AppSettingsRepository(applicationContext)
            val settings = repository.settingsFlow.first()
            payloadRaw?.let { repository.appendPayload(it) }
            repository.setForegroundPreference(true)
            val allowedTags = settings.allowedTags.map { it.trim().lowercase() }.toSet()
            val blockedTags = settings.blockedTags.map { it.trim().lowercase() }.toSet()
            val notifications = NotificationCoordinator(
                applicationContext,
                createAppNotificationConfig(applicationContext)
            )

            events.forEach { event ->
                if (event.remove) {
                    val missingNotificationTitle = applicationContext.getString(
                        R.string.missing_status_notification_title,
                        formatTimeoutTopic(event.eventType, event.topic)
                    )
                    notifications.cancelEvent(NotificationEventType.IMPORTANT, missingNotificationTitle)
                    notifications.cancelTopic(event.topic)
                    AppMissingStatusScheduler.cancelTopic(event.topic)
                    repository.removeStatus(event.eventType, event.topic)
                    repository.updateWearableStatus(null, null)
                    return@forEach
                }
                if (!event.shouldDisplay(allowedTags, blockedTags)) {
                    Log.d(TAG, "Skipping event '${event.topic}' due to tag filter")
                    return@forEach
                }

                if (event.topic.isBlank()) {
                    Log.w(TAG, "Skipping event with empty topic")
                    return@forEach
                }

                val eventInstant = if (event.timestampMillis > 0L) Instant.ofEpochMilli(event.timestampMillis) else Instant.now()
                val wearableHeadline = event.wearable?.headline ?: event.topic
                val resolvedBody = event.wearable?.body ?: event.text.ifBlank { event.topic }
                val statusBody = event.text.ifBlank { event.topic }

                val missingNotificationTitle = applicationContext.getString(
                    R.string.missing_status_notification_title,
                    formatTimeoutTopic(event.eventType, event.topic)
                )
                notifications.cancelEvent(NotificationEventType.IMPORTANT, missingNotificationTitle)

                repository.updateStatus(event.eventType, event.topic, statusBody, eventInstant, event.timeout)
                repository.updateWearableStatus(wearableHeadline, resolvedBody)

                val silent = event.eventType != NotificationEventType.IMPORTANT
                notifications.postEvent(event.eventType, event, silent)

                AppMissingStatusScheduler.schedule(
                    context = applicationContext,
                    type = event.eventType,
                    topic = event.topic,
                    timeoutSeconds = event.timeout,
                    updatedAtMillis = eventInstant.toEpochMilli()
                )
            }
        }
    }

    private fun formatTimeoutTopic(type: NotificationEventType, topic: String): String {
        return "${type.name.lowercase()}: ${topic}".trim()
    }

    private fun parseEvents(payload: String?): List<NotificationEvent> {
        if (payload.isNullOrBlank()) return emptyList()
        return runCatching {
            json.decodeFromString(ListSerializer(NotificationEvent.serializer()), payload)
        }.getOrElse {
            Log.e(TAG, "Failed to decode payload list", it)
            emptyList()
        }
    }

    companion object {
        private const val TAG = "NotifryerFCM"
    }
}
