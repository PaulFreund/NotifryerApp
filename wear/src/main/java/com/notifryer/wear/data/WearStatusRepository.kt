package com.notifryer.wear.data

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.notifryer.wear.WearPreferenceKeys
import com.notifryer.wear.wearDataStore
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

data class WearStatus(
    val headline: String?,
    val body: String?,
    val updatedAt: Instant?,
    val vibrate: Boolean,
    val allowlist: Set<String>,
    val blocklist: Set<String>,
    val showOngoingNotifications: Boolean,
    val alertOnMissingStatus: Boolean,
    val suppressSilentWhenConnected: Boolean,
    val suppressImportantWhenConnected: Boolean,
    val ongoingTopics: List<WearOngoingTopic>
)

data class WearOngoingTopic(
    val topic: String,
    val snapshot: WearStatusRepository.OngoingStatusSnapshot
)

class WearStatusRepository(private val context: Context) {

    @Serializable
    data class OngoingStatusSnapshot(
        val message: String? = null,
        val updatedAt: Long? = null,
        val timeoutSeconds: Long? = null
    )

    private val json = Json { ignoreUnknownKeys = true }


    val wearStatus: Flow<WearStatus> = context.wearDataStore.data.map { preferences ->
        WearStatus(
            headline = preferences[WearPreferenceKeys.headline],
            body = preferences[WearPreferenceKeys.body],
            updatedAt = preferences[WearPreferenceKeys.updatedAt]?.let(Instant::ofEpochMilli),
            vibrate = preferences[WearPreferenceKeys.vibrate] ?: false,
            allowlist = (preferences[WearPreferenceKeys.allowlist] ?: setOf("*")).mapNotNull { value ->
                val trimmed = value.trim()
                if (trimmed.isNotEmpty()) trimmed else null
            }.toSet().ifEmpty { setOf("*") },
            blocklist = (preferences[WearPreferenceKeys.blocklist] ?: emptySet()).mapNotNull { value ->
                val trimmed = value.trim()
                if (trimmed.isNotEmpty()) trimmed else null
            }.toSet(),
            showOngoingNotifications = preferences[WearPreferenceKeys.showOngoingNotifications] ?: false,
            alertOnMissingStatus = preferences[WearPreferenceKeys.alertOnMissingStatus] ?: true,
            suppressSilentWhenConnected = preferences[WearPreferenceKeys.suppressSilentWhenConnected] ?: true,
            suppressImportantWhenConnected = preferences[WearPreferenceKeys.suppressImportantWhenConnected] ?: true,
            ongoingTopics = readOngoingStatuses(preferences)
                .map { (topic, snapshot) -> WearOngoingTopic(topic, snapshot) }
                .sortedByDescending { it.snapshot.updatedAt ?: 0L }
        )
    }

    suspend fun updateStatus(
        headline: String?,
        body: String?,
        updatedAt: Instant?,
        vibrate: Boolean,
        topic: String? = null,
        timeoutSeconds: Long? = null
    ) {
        context.wearDataStore.edit { prefs ->
            if (headline == null) prefs.remove(WearPreferenceKeys.headline) else prefs[WearPreferenceKeys.headline] = headline
            if (body == null) prefs.remove(WearPreferenceKeys.body) else prefs[WearPreferenceKeys.body] = body
            if (updatedAt == null) {
                prefs.remove(WearPreferenceKeys.updatedAt)
            } else {
                prefs[WearPreferenceKeys.updatedAt] = updatedAt.toEpochMilli()
            }
            prefs[WearPreferenceKeys.vibrate] = vibrate
            topic?.takeIf { it.isNotBlank() }?.let { topicKey ->
                val statuses = readOngoingStatuses(prefs)
                statuses[topicKey] = OngoingStatusSnapshot(
                    message = body,
                    updatedAt = updatedAt?.toEpochMilli(),
                    timeoutSeconds = timeoutSeconds
                )
                writeOngoingStatuses(prefs, statuses)
            }
        }
    }

    suspend fun addAllowTag(tag: String) {
        val trimmed = tag.trim()
        if (trimmed.isEmpty()) return
        context.wearDataStore.edit { prefs ->
            val current = prefs[WearPreferenceKeys.allowlist] ?: setOf("*")
            val updated = if (trimmed == "*") {
                setOf("*")
            } else {
                val sanitized = (current - "*").filterNot { it.equals(trimmed, ignoreCase = true) }.toMutableSet()
                sanitized.add(trimmed)
                sanitized.toSet()
            }
            prefs[WearPreferenceKeys.allowlist] = updated
        }
    }

    suspend fun removeAllowTag(tag: String) {
        context.wearDataStore.edit { prefs ->
            val current = prefs[WearPreferenceKeys.allowlist] ?: setOf("*")
            val updated = (current - tag).takeIf { it.isNotEmpty() } ?: setOf("*")
            prefs[WearPreferenceKeys.allowlist] = updated
        }
    }

    suspend fun addBlockTag(tag: String) {
        val trimmed = tag.trim()
        if (trimmed.isEmpty()) return
        context.wearDataStore.edit { prefs ->
            val current = prefs[WearPreferenceKeys.blocklist] ?: emptySet()
            val sanitized = current.filterNot { it.equals(trimmed, ignoreCase = true) }.toMutableSet()
            sanitized.add(trimmed)
            prefs[WearPreferenceKeys.blocklist] = sanitized
        }
    }

    suspend fun removeBlockTag(tag: String) {
        context.wearDataStore.edit { prefs ->
            val current = prefs[WearPreferenceKeys.blocklist] ?: emptySet()
            prefs[WearPreferenceKeys.blocklist] = current.filterNot { it.equals(tag, ignoreCase = true) }.toSet()
        }
    }

    suspend fun getFilters(): Pair<Set<String>, Set<String>> {
        val prefs = context.wearDataStore.data.first()
        val allowlist = (prefs[WearPreferenceKeys.allowlist] ?: setOf("*")).mapNotNull { value ->
            val trimmed = value.trim()
            if (trimmed.isNotEmpty()) trimmed else null
        }.toSet().ifEmpty { setOf("*") }
        val blocklist = (prefs[WearPreferenceKeys.blocklist] ?: emptySet()).mapNotNull { value ->
            val trimmed = value.trim()
            if (trimmed.isNotEmpty()) trimmed else null
        }.toSet()
        return allowlist to blocklist
    }

    suspend fun setShowOngoingNotifications(enabled: Boolean) {
        context.wearDataStore.edit { prefs ->
            prefs[WearPreferenceKeys.showOngoingNotifications] = enabled
        }
    }

    suspend fun shouldShowOngoing(): Boolean {
        val prefs = context.wearDataStore.data.first()
        return prefs[WearPreferenceKeys.showOngoingNotifications] ?: false
    }

    suspend fun setAlertOnMissingStatus(enabled: Boolean) {
        context.wearDataStore.edit { prefs ->
            prefs[WearPreferenceKeys.alertOnMissingStatus] = enabled
        }
    }

    suspend fun isAlertOnMissingStatusEnabled(): Boolean {
        val prefs = context.wearDataStore.data.first()
        return prefs[WearPreferenceKeys.alertOnMissingStatus] ?: true
    }

    suspend fun setSuppressSilentWhenConnected(enabled: Boolean) {
        context.wearDataStore.edit { prefs ->
            prefs[WearPreferenceKeys.suppressSilentWhenConnected] = enabled
        }
    }

    suspend fun isSuppressSilentWhenConnectedEnabled(): Boolean {
        val prefs = context.wearDataStore.data.first()
        return prefs[WearPreferenceKeys.suppressSilentWhenConnected] ?: true
    }

    suspend fun setSuppressImportantWhenConnected(enabled: Boolean) {
        context.wearDataStore.edit { prefs ->
            prefs[WearPreferenceKeys.suppressImportantWhenConnected] = enabled
        }
    }

    suspend fun isSuppressImportantWhenConnectedEnabled(): Boolean {
        val prefs = context.wearDataStore.data.first()
        return prefs[WearPreferenceKeys.suppressImportantWhenConnected] ?: true
    }

    suspend fun removeOngoingStatus(topic: String) {
        val trimmed = topic.trim()
        if (trimmed.isEmpty()) return
        context.wearDataStore.edit { prefs ->
            val statuses = readOngoingStatuses(prefs)
            if (statuses.remove(trimmed) != null) {
                writeOngoingStatuses(prefs, statuses)
            }
        }
    }

    suspend fun getOngoingStatus(topic: String): OngoingStatusSnapshot? {
        val prefs = context.wearDataStore.data.first()
        return readOngoingStatuses(prefs)[topic]
    }

    suspend fun getAllOngoingStatuses(): Map<String, OngoingStatusSnapshot> {
        val prefs = context.wearDataStore.data.first()
        return readOngoingStatuses(prefs)
    }

    private fun readOngoingStatuses(preferences: Preferences): MutableMap<String, OngoingStatusSnapshot> {
        val stored = preferences[WearPreferenceKeys.ongoingStatuses] ?: return mutableMapOf()
        return runCatching { json.decodeFromString<Map<String, OngoingStatusSnapshot>>(stored) }
            .getOrElse { emptyMap() }
            .toMutableMap()
    }

    private fun writeOngoingStatuses(prefs: MutablePreferences, statuses: Map<String, OngoingStatusSnapshot>) {
        if (statuses.isEmpty()) {
            prefs.remove(WearPreferenceKeys.ongoingStatuses)
        } else {
            prefs[WearPreferenceKeys.ongoingStatuses] = json.encodeToString(statuses)
        }
    }
}
