package com.notifryer.app.service

import android.content.Context
import android.net.Uri
import androidx.core.app.NotificationCompat
import com.notifryer.app.NotificationConstants
import com.notifryer.app.R
import com.notifryer.notification.NotificationCoordinator
import com.notifryer.notification.NotificationEvent
import com.notifryer.notification.NotificationEventType
import kotlin.math.absoluteValue

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
        actionsProvider = { type: NotificationEventType, event: NotificationEvent ->
            event.actions
                .asSequence()
                .filter { it.name.isNotBlank() && it.url.isNotBlank() }
                .filter { isHttpUrl(it.url) }
                .take(MAX_ACTIONS)
                .mapIndexed { index, action ->
                    NotificationCompat.Action.Builder(
                        R.drawable.ic_notification_status,
                        action.name.trim(),
                        NotificationActionReceiver.createPendingIntent(
                            context,
                            buildRequestCode(type, event.topic, index),
                            action.url
                        )
                    ).build()
                }
                .toList()
        },
        builderExtras = { _: NotificationEventType, _: NotificationEvent, _: Boolean, builder: NotificationCompat.Builder ->
            builder.setOnlyAlertOnce(true)
        }
    )
}

private const val GROUP_KEY = "notifryer:status"
private const val MAX_ACTIONS = 3

private fun buildRequestCode(type: NotificationEventType, topic: String, index: Int): Int {
    val seed = 31 * type.name.hashCode() + 7 * topic.hashCode() + index
    return seed.absoluteValue
}

private fun isHttpUrl(url: String): Boolean {
    val scheme = runCatching { Uri.parse(url).scheme.orEmpty() }.getOrDefault("")
    return scheme.equals("http", ignoreCase = true) || scheme.equals("https", ignoreCase = true)
}
