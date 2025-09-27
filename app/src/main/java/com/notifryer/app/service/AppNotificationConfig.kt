package com.notifryer.app.service

import android.content.Context
import androidx.core.app.NotificationCompat
import com.notifryer.app.NotificationConstants
import com.notifryer.app.R
import com.notifryer.notification.NotificationCoordinator
import com.notifryer.notification.NotificationEvent
import com.notifryer.notification.NotificationEventType

fun createAppNotificationConfig(context: Context): NotificationCoordinator.Config {
    return NotificationCoordinator.Config(
        smallIconRes = context.applicationInfo.icon,
        groupKey = GROUP_KEY,
        channelForType = { type ->
            when (type) {
                NotificationEventType.IMPORTANT -> NotificationConstants.CHANNEL_ID_IMPORTANT
                NotificationEventType.NORMAL -> NotificationConstants.CHANNEL_ID_NORMAL
                NotificationEventType.ONGOING -> NotificationConstants.CHANNEL_ID_NORMAL
            }
        },
        categoryForType = { type ->
            when (type) {
                NotificationEventType.IMPORTANT -> NotificationCompat.CATEGORY_ALARM
                NotificationEventType.NORMAL, NotificationEventType.ONGOING -> NotificationCompat.CATEGORY_STATUS
            }
        },
        defaultTitle = { context.getString(R.string.app_name) },
        defaultBody = { context.getString(R.string.status_placeholder_waiting) },
        builderExtras = { _: NotificationEventType, _: NotificationEvent, _: Boolean, builder: NotificationCompat.Builder ->
            builder.setOnlyAlertOnce(true)
        }
    )
}

private const val GROUP_KEY = "notifryer:status"
