package com.notifryer.wear.fcm

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import com.google.firebase.messaging.FirebaseMessaging
import com.notifryer.wear.MessagingConstants
import com.notifryer.wear.R
import com.notifryer.wear.connectivity.CompanionConnectionChecker
import com.notifryer.wear.data.WearStatusRepository
import com.notifryer.notification.NotificationCoordinator
import com.notifryer.notification.NotificationEvent
import com.notifryer.notification.NotificationEventType
import com.notifryer.wear.notification.createWearNotificationConfig
import com.notifryer.wear.worker.MissingStatusScheduler
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

class NotifryerWearMessagingService : FirebaseMessagingService() {

    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val coordinator by lazy {
        NotificationCoordinator(applicationContext, createWearNotificationConfig(applicationContext))
    }
    private val largeIcon by lazy {
        val rawBitmap = BitmapFactory.decodeResource(resources, R.drawable.notifryer_logo)
        if (rawBitmap.width > 192 || rawBitmap.height > 192) {
            val scaled = Bitmap.createScaledBitmap(rawBitmap, 192, 192, true)
            rawBitmap.recycle()
            scaled
        } else {
            rawBitmap
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val events = parseEvents(message)
        if (events.isEmpty()) {
            Log.w(TAG, "Missing payload in message: ${message.data}")
            return
        }

        scope.launch {
            val repository = WearStatusRepository(applicationContext)
            val (allowlistRaw, blocklistRaw) = repository.getFilters()
            val allowlist = allowlistRaw.map { it.trim().lowercase() }.toSet()
            val blocklist = blocklistRaw.map { it.trim().lowercase() }.toSet()
            val showOngoing = repository.shouldShowOngoing()
            val alertsEnabled = repository.isAlertOnMissingStatusEnabled()
            val suppressSilentWhenConnected = repository.isSuppressSilentWhenConnectedEnabled()
            val suppressImportantWhenConnected = repository.isSuppressImportantWhenConnectedEnabled()
            val connectionActive = (suppressSilentWhenConnected || suppressImportantWhenConnected) &&
                CompanionConnectionChecker.isCompanionConnected(applicationContext)

            events.forEach { event ->
                if (event.remove) {
                    coordinator.cancelTopic(event.topic)
                    if (event.eventType == NotificationEventType.ONGOING) {
                        repository.updateStatus(null, null, null, false)
                        repository.removeOngoingStatus(event.topic)
                    }
                    MissingStatusScheduler.cancel(event.topic)
                    return@forEach
                }

                if (event.topic.isBlank()) {
                    Log.w(TAG, "Skipping event with empty topic")
                    return@forEach
                }

                if (!event.shouldDisplay(allowlist, blocklist)) {
                    Log.d(TAG, "Skipping event '${event.topic}' due to tag filter")
                    return@forEach
                }

                val statusText = event.wearable?.body ?: event.text.ifBlank { event.topic }
                val headline = event.wearable?.headline ?: event.topic
                val updatedAt = if (event.timestampMillis > 0L) Instant.ofEpochMilli(event.timestampMillis) else Instant.now()

                coordinator.cancelEvent(
                    NotificationEventType.IMPORTANT,
                    applicationContext.getString(R.string.missing_status_title, event.topic)
                )

                val suppressNotification = if (!connectionActive) {
                    false
                } else {
                    when (event.eventType) {
                        NotificationEventType.IMPORTANT -> suppressImportantWhenConnected
                        NotificationEventType.NORMAL, NotificationEventType.ONGOING -> suppressSilentWhenConnected
                    }
                }
                val shouldRender = !suppressNotification
                when (event.eventType) {
                    NotificationEventType.ONGOING -> {
                        repository.updateStatus(
                            headline = headline,
                            body = statusText,
                            updatedAt = updatedAt,
                            vibrate = event.vibratePattern?.isNotEmpty() == true,
                            topic = event.topic,
                            timeoutSeconds = event.timeout
                        )
                        if (showOngoing) {
                            if (shouldRender) {
                                coordinator.postEvent(
                                    type = NotificationEventType.ONGOING,
                                    event = event.copy(text = statusText),
                                    silent = true,
                                    largeIcon = largeIcon
                                )
                            } else {
                                Log.d(TAG, "Skipping ongoing notification '${event.topic}' while connected to phone")
                            }
                        }
                        val timeout = event.timeout
                        if (alertsEnabled && timeout != null && timeout > 0L && shouldRender) {
                            MissingStatusScheduler.schedule(
                                context = applicationContext,
                                repository = repository,
                                topic = event.topic,
                                updatedAt = updatedAt,
                                timeoutSeconds = timeout,
                                alertsEnabled = true
                            )
                        } else {
                            MissingStatusScheduler.cancel(event.topic)
                        }
                    }
                    NotificationEventType.NORMAL -> {
                        if (shouldRender) {
                            coordinator.postEvent(
                                type = NotificationEventType.NORMAL,
                                event = event.copy(text = statusText),
                                silent = true,
                                largeIcon = largeIcon
                            )
                        } else {
                            Log.d(TAG, "Skipping normal notification '${event.topic}' while connected to phone")
                        }
                    }
                    NotificationEventType.IMPORTANT -> {
                        if (shouldRender) {
                            coordinator.postEvent(
                                type = NotificationEventType.IMPORTANT,
                                event = event.copy(text = statusText),
                                silent = false,
                                largeIcon = largeIcon
                            )
                            val pattern = event.vibratePattern?.takeIf { it.isNotEmpty() } ?: DEFAULT_VIBRATION.toList()
                            vibrate(pattern)
                        } else {
                            Log.d(TAG, "Skipping important notification '${event.topic}' while connected to phone")
                        }
                        MissingStatusScheduler.cancel(event.topic)
                    }
                }
            }
        }
    }

    override fun onNewToken(token: String) {
        Log.i(TAG, "Wear FCM token refreshed; ensuring topic subscription")
        scope.launch {
            runCatching {
                FirebaseMessaging.getInstance().subscribeToTopic(MessagingConstants.STATUS_TOPIC).await()
                Log.i(TAG, "Wear subscribed to topic ${MessagingConstants.STATUS_TOPIC} after token refresh")
            }.onFailure {
                Log.e(TAG, "Wear failed to re-subscribe to topic after token refresh", it)
            }
        }
    }

    private fun parseEvents(message: RemoteMessage): List<NotificationEvent> {
        val payload = message.data["payload"] ?: return emptyList()
        return runCatching {
            json.decodeFromString(ListSerializer(NotificationEvent.serializer()), payload)
        }.getOrElse {
            Log.e(TAG, "Failed to decode payload list", it)
            emptyList()
        }
    }

    private fun vibrate(pattern: List<Long>) {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = getSystemService(VibratorManager::class.java)
            manager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(VIBRATOR_SERVICE) as? Vibrator
        }
        vibrator?.let {
            val vibrationEffect = if (pattern.size >= 2) {
                VibrationEffect.createWaveform(pattern.toLongArray(), -1)
            } else {
                VibrationEffect.createOneShot(200, VibrationEffect.DEFAULT_AMPLITUDE)
            }
            it.vibrate(vibrationEffect)
        }
    }

    companion object {
        private const val TAG = "WearFCM"
        private val DEFAULT_VIBRATION = longArrayOf(0, 150, 80, 150)
    }
}
