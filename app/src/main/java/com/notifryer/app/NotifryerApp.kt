package com.notifryer.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import com.notifryer.app.NotificationConstants.CHANNEL_ID_IMPORTANT
import com.notifryer.app.NotificationConstants.CHANNEL_ID_NORMAL
import com.notifryer.app.NotificationConstants.CHANNEL_ID_ONGOING
import com.notifryer.app.R
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.core.app.NotificationManagerCompat
import com.notifryer.app.data.AppSettingsRepository
import com.notifryer.app.service.TopicSubscriptionWorker
import kotlinx.coroutines.runBlocking
import kotlin.properties.Delegates

private const val DATA_STORE_NAME = "notifryer_settings"

val Context.dataStore by preferencesDataStore(name = DATA_STORE_NAME)

object PreferenceKeys {
    val foregroundEnabled = booleanPreferencesKey("foreground_enabled")
    val lastStatusTopic = stringPreferencesKey("last_status_topic")
    val legacyLastStatusTitle = stringPreferencesKey("last_status_title")
    val lastStatusMessage = stringPreferencesKey("last_status_message")
    val lastStatusUpdatedAt = longPreferencesKey("last_status_updated_at")
    val lastStatusSequence = longPreferencesKey("last_status_sequence")
    val lastStatusTimeoutSeconds = longPreferencesKey("last_status_timeout_seconds")
    val statusEntries = stringPreferencesKey("status_entries")
    val wearableHeadline = stringPreferencesKey("wearable_headline")
    val wearableBody = stringPreferencesKey("wearable_body")
    val allowedTags = stringSetPreferencesKey("allowed_tags")
    val blockedTags = stringSetPreferencesKey("blocked_tags")
    val lastPayload = stringPreferencesKey("last_payload")
    val lastPayloadTimestamp = longPreferencesKey("last_payload_timestamp")
    val alertOnMissingStatus = booleanPreferencesKey("alert_on_missing_status")
}

class NotifryerApp : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        NotificationManagerCompat.from(this).cancelAll()
        runBlocking {
            AppSettingsRepository(applicationContext).clearAllStatuses()
        }
        createNotificationChannels()
        TopicSubscriptionWorker.schedulePeriodic(applicationContext)
        TopicSubscriptionWorker.runImmediate(applicationContext)
    }

    private fun createNotificationChannels() {
        val manager = getSystemService(NotificationManager::class.java)
        val ongoingChannel = NotificationChannel(
            CHANNEL_ID_ONGOING,
            getString(R.string.notification_channel_name_ongoing),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_description_ongoing)
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }

        val normalChannel = NotificationChannel(
            CHANNEL_ID_NORMAL,
            getString(R.string.notification_channel_name_normal),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_description_normal)
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }

        val importantChannel = NotificationChannel(
            CHANNEL_ID_IMPORTANT,
            getString(R.string.notification_channel_name_important),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = getString(R.string.notification_channel_description_important)
            enableVibration(true)
        }

        manager.createNotificationChannels(listOf(ongoingChannel, normalChannel, importantChannel))
    }

    companion object {
        private var instance: NotifryerApp by Delegates.notNull()
        val appContext: Context
            get() = instance
    }
}
