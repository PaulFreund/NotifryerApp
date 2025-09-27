package com.notifryer.wear

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.core.app.NotificationManagerCompat
import com.notifryer.wear.R

private const val DATA_STORE_NAME = "wear_notifryer"
const val WEAR_CHANNEL_NORMAL = "wear_channel_normal"
const val WEAR_CHANNEL_IMPORTANT = "wear_channel_important"

val Context.wearDataStore by preferencesDataStore(name = DATA_STORE_NAME)

object WearPreferenceKeys {
    val headline = stringPreferencesKey("headline")
    val body = stringPreferencesKey("body")
    val updatedAt = longPreferencesKey("updated_at")
    val vibrate = booleanPreferencesKey("vibrate")
    val allowlist = stringSetPreferencesKey("allowlist")
    val blocklist = stringSetPreferencesKey("blocklist")
    val showOngoingNotifications = booleanPreferencesKey("show_ongoing_notifications")
    val alertOnMissingStatus = booleanPreferencesKey("alert_on_missing_status")
    val ongoingStatuses = stringPreferencesKey("ongoing_statuses")
    val suppressSilentWhenConnected = booleanPreferencesKey("suppress_silent_when_connected")
    val suppressImportantWhenConnected = booleanPreferencesKey("suppress_important_when_connected")
}

class WearApp : Application() {
    override fun onCreate() {
        super.onCreate()
        NotificationManagerCompat.from(this).cancelAll()
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        val manager = getSystemService(NotificationManager::class.java)
        val normalChannel = NotificationChannel(
            WEAR_CHANNEL_NORMAL,
            getString(R.string.wear_channel_name_normal),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            enableVibration(false)
            setSound(null, null)
        }
        val importantChannel = NotificationChannel(
            WEAR_CHANNEL_IMPORTANT,
            getString(R.string.wear_channel_name_important),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            enableVibration(true)
        }
        manager.createNotificationChannels(listOf(normalChannel, importantChannel))
    }
}
