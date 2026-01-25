package com.notifryer.notification

import android.content.Context
import android.graphics.Bitmap
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlin.math.absoluteValue

class NotificationCoordinator(
    private val context: Context,
    private val config: Config
) {

    data class Config(
        val smallIconRes: Int,
        val groupKey: String,
        val channelForType: (NotificationEventType) -> String,
        val categoryForType: (NotificationEventType) -> String,
        val defaultTitle: () -> String,
        val defaultBody: () -> String,
        val actionsProvider: (NotificationEventType, NotificationEvent) -> List<NotificationCompat.Action> = { _, _ -> emptyList() },
        val builderExtras: (NotificationEventType, NotificationEvent, Boolean, NotificationCompat.Builder) -> Unit = { _, _, _, _ -> }
    )

    private val manager = NotificationManagerCompat.from(context)

    fun postEvent(
        type: NotificationEventType,
        event: NotificationEvent,
        silent: Boolean,
        largeIcon: Bitmap? = null
    ) {
        val title = event.topic.ifBlank { config.defaultTitle() }
        val body = event.text.ifBlank { config.defaultBody() }
        val isPermanent = event.permanent
        val builder = NotificationCompat.Builder(context, config.channelForType(type))
            .setSmallIcon(config.smallIconRes)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .setBigContentTitle(title)
                    .bigText(body)
            )
            .setCategory(config.categoryForType(type))
            .setAutoCancel(!isPermanent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setWhen(event.timestampMillis.takeIf { it > 0L } ?: System.currentTimeMillis())
            .setShowWhen(true)
            .setGroup(config.groupKey)
            .setOngoing(isPermanent)

        if (silent) {
            builder.setSilent(true)
            builder.setSound(null)
            builder.setVibrate(LongArray(0))
            builder.setDefaults(0)
        } else {
            event.vibratePattern?.takeIf { it.isNotEmpty() }?.let { pattern ->
                builder.setVibrate(pattern.toLongArray())
            }
        }

        largeIcon?.let { builder.setLargeIcon(it) }

        config.actionsProvider(type, event)
            .takeIf { it.isNotEmpty() }
            ?.forEach { action -> builder.addAction(action) }

        config.builderExtras(type, event, silent, builder)

        manager.notify(eventTag(type, event.topic), EVENT_NOTIFICATION_ID, builder.build())
    }

    fun cancelEvent(type: NotificationEventType, topic: String) {
        manager.cancel(eventTag(type, topic), EVENT_NOTIFICATION_ID)
    }

    fun cancelTopic(topic: String) {
        NotificationEventType.values().forEach { cancelEvent(it, topic) }
    }

    fun cancelAll() {
        manager.cancelAll()
    }

    private fun eventTag(type: NotificationEventType, topic: String): String {
        return "${type.name}:${topic.hashCode().absoluteValue}"
    }

    companion object {
        private const val EVENT_NOTIFICATION_ID = 43
    }
}
