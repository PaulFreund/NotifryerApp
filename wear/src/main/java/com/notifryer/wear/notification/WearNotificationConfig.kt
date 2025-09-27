package com.notifryer.wear.notification

import android.content.Context
import androidx.core.app.NotificationCompat
import com.notifryer.notification.NotificationCoordinator
import com.notifryer.notification.NotificationEvent
import com.notifryer.notification.NotificationEventType
import com.notifryer.wear.R
import com.notifryer.wear.WEAR_CHANNEL_IMPORTANT
import com.notifryer.wear.WEAR_CHANNEL_NORMAL

fun createWearNotificationConfig(context: Context): NotificationCoordinator.Config {
    return NotificationCoordinator.Config(
        smallIconRes = R.drawable.ic_notification,
        groupKey = GROUP_KEY,
        channelForType = { type ->
            when (type) {
                NotificationEventType.IMPORTANT -> WEAR_CHANNEL_IMPORTANT
                NotificationEventType.NORMAL, NotificationEventType.ONGOING -> WEAR_CHANNEL_NORMAL
            }
        },
        categoryForType = { type ->
            when (type) {
                NotificationEventType.IMPORTANT -> NotificationCompat.CATEGORY_ALARM
                NotificationEventType.NORMAL, NotificationEventType.ONGOING -> NotificationCompat.CATEGORY_STATUS
            }
        },
        defaultTitle = { context.getString(R.string.tile_headline) },
        defaultBody = { context.getString(R.string.tile_headline) },
        builderExtras = { type: NotificationEventType, event: NotificationEvent, silent: Boolean, builder: NotificationCompat.Builder ->
            builder.setLocalOnly(true)
            builder.setPriority(
                if (type == NotificationEventType.IMPORTANT) NotificationCompat.PRIORITY_HIGH
                else NotificationCompat.PRIORITY_DEFAULT
            )
            if (!silent && type == NotificationEventType.IMPORTANT && event.vibratePattern.isNullOrEmpty()) {
                builder.setVibrate(DEFAULT_VIBRATION)
            }
        }
    )
}

private const val GROUP_KEY = "notifryer:wear"
private val DEFAULT_VIBRATION = longArrayOf(0, 150, 80, 150)
