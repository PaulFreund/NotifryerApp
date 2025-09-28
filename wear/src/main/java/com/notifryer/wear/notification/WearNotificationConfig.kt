package com.notifryer.wear.notification

import android.content.Context
import android.net.Uri
import androidx.core.app.NotificationCompat
import com.notifryer.notification.NotificationCoordinator
import com.notifryer.notification.NotificationEvent
import com.notifryer.notification.NotificationEventType
import com.notifryer.wear.R
import com.notifryer.wear.WEAR_CHANNEL_IMPORTANT
import com.notifryer.wear.WEAR_CHANNEL_NORMAL
import kotlin.math.absoluteValue

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
        actionsProvider = { type: NotificationEventType, event: NotificationEvent ->
            event.actions
                .asSequence()
                .filter { it.name.isNotBlank() && it.url.isNotBlank() }
                .filter { isHttpUrl(it.url) }
                .take(MAX_ACTIONS)
                .mapIndexed { index, action ->
                    NotificationCompat.Action.Builder(
                        R.drawable.ic_notification,
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
private const val MAX_ACTIONS = 3

private fun buildRequestCode(type: NotificationEventType, topic: String, index: Int): Int {
    val seed = 31 * type.name.hashCode() + 7 * topic.hashCode() + index
    return seed.absoluteValue
}

private fun isHttpUrl(url: String): Boolean {
    val scheme = runCatching { Uri.parse(url).scheme.orEmpty() }.getOrDefault("")
    return scheme.equals("http", ignoreCase = true) || scheme.equals("https", ignoreCase = true)
}
