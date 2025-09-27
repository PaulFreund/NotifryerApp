package com.notifryer.app.data

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.notifryer.app.PreferenceKeys
import com.notifryer.app.dataStore
import com.notifryer.notification.NotificationEventType
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

data class AppSettings(
    val keepForeground: Boolean,
    val statusEntries: Map<NotificationEventType, Map<String, StatusSnapshot>>,
    val lastStatusTopic: String?,
    val lastStatusMessage: String?,
    val lastUpdated: Instant?,
    val lastStatusSequence: Long?,
    val lastStatusTimeoutSeconds: Long?,
    val wearableHeadline: String?,
    val wearableBody: String?,
    val allowedTags: Set<String>,
    val blockedTags: Set<String>,
    val lastPayload: PayloadLog?,
    val alertOnMissingStatus: Boolean
)

data class StatusSnapshot(
    val topic: String,
    val message: String?,
    val updatedAt: Instant?,
    val timeoutSeconds: Long?,
    val sequence: Long?
)

data class PayloadLog(
    val payload: String,
    val receivedAt: Instant
)

class AppSettingsRepository(private val context: Context) {

    @Serializable
    private data class StoredPayloadLog(
        val payload: String,
        val receivedAt: Long
    )

    @Serializable
    private data class StoredStatusEntry(
        val message: String? = null,
        val updatedAt: Long? = null,
        val timeoutSeconds: Long? = null,
        val sequence: Long? = null
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val legacyPayloadListSerializer = ListSerializer(StoredPayloadLog.serializer())
    private val statusEntriesSerializer = MapSerializer(
        String.serializer(),
        MapSerializer(String.serializer(), StoredStatusEntry.serializer())
    )
    private val legacyPayloadHistoryKey = stringPreferencesKey("payload_history")

    val settingsFlow: Flow<AppSettings> = context.dataStore.data.map { preferences ->
        val convertedEntries = convertStatusEntries(preferences)

        val ongoingPrimary = convertedEntries[NotificationEventType.ONGOING]
            ?.values
            ?.maxByOrNull { it.sequence ?: it.updatedAt?.toEpochMilli() ?: 0L }

        val lastPayload = runCatching {
            val payload = preferences[PreferenceKeys.lastPayload]
            val timestamp = preferences[PreferenceKeys.lastPayloadTimestamp]
            if (payload != null && timestamp != null) {
                val instant = runCatching { Instant.ofEpochMilli(timestamp) }.getOrNull()
                instant?.let { PayloadLog(payload, it) }
            } else {
                val legacy = preferences[legacyPayloadHistoryKey]
                if (legacy != null) {
                    json.decodeFromString(legacyPayloadListSerializer, legacy)
                        .firstOrNull()
                        ?.let { entry ->
                            val instant = runCatching { Instant.ofEpochMilli(entry.receivedAt) }.getOrNull()
                            instant?.let { PayloadLog(entry.payload, it) }
                        }
                } else {
                    null
                }
            }
        }.getOrNull()

        AppSettings(
            keepForeground = preferences[PreferenceKeys.foregroundEnabled] ?: true,
            statusEntries = convertedEntries,
            lastStatusTopic = ongoingPrimary?.topic,
            lastStatusMessage = ongoingPrimary?.message,
            lastUpdated = ongoingPrimary?.updatedAt,
            lastStatusSequence = ongoingPrimary?.sequence,
            lastStatusTimeoutSeconds = ongoingPrimary?.timeoutSeconds,
            wearableHeadline = preferences[PreferenceKeys.wearableHeadline],
            wearableBody = preferences[PreferenceKeys.wearableBody],
            allowedTags = (preferences[PreferenceKeys.allowedTags] ?: setOf("*")).mapNotNull { value ->
                val trimmed = value.trim()
                if (trimmed.isNotEmpty()) trimmed else null
            }.toSet().ifEmpty { setOf("*") },
            blockedTags = (preferences[PreferenceKeys.blockedTags] ?: emptySet()).mapNotNull { value ->
                val trimmed = value.trim()
                if (trimmed.isNotEmpty()) trimmed else null
            }.toSet(),
            lastPayload = lastPayload,
            alertOnMissingStatus = preferences[PreferenceKeys.alertOnMissingStatus] ?: true
        )
    }

    suspend fun getAllStatusSnapshots(): Map<NotificationEventType, Map<String, StatusSnapshot>> {
        val preferences = context.dataStore.data.first()
        return convertStatusEntries(preferences)
    }

    suspend fun getStatusSnapshot(type: NotificationEventType, topic: String): StatusSnapshot? {
        val preferences = context.dataStore.data.first()
        return convertStatusEntries(preferences)[type]?.get(topic)
    }

    suspend fun isAlertOnMissingStatusEnabled(): Boolean {
        val preferences = context.dataStore.data.first()
        return preferences[PreferenceKeys.alertOnMissingStatus] ?: true
    }

    suspend fun setForegroundPreference(enabled: Boolean) {
        if (!enabled) return
        context.dataStore.edit { prefs ->
            prefs[PreferenceKeys.foregroundEnabled] = true
        }
    }

    suspend fun updateStatus(
        eventType: NotificationEventType,
        statusTopic: String?,
        statusMessage: String?,
        updatedAt: Instant?,
        timeoutSeconds: Long?
    ) {
        context.dataStore.edit { prefs ->
            val entries = readStatusEntriesMutable(prefs)
            val sanitizedTopic = statusTopic?.trim()?.takeIf { it.isNotEmpty() }
            when {
                sanitizedTopic == null -> entries.remove(eventType)
                statusMessage == null && updatedAt == null -> entries[eventType]?.remove(sanitizedTopic)
                else -> {
                    val topicMap = entries.getOrPut(eventType) { mutableMapOf() }
                    topicMap[sanitizedTopic] = StoredStatusEntry(
                        message = statusMessage,
                        updatedAt = updatedAt?.toEpochMilli(),
                        timeoutSeconds = timeoutSeconds,
                        sequence = System.currentTimeMillis()
                    )
                }
            }
            pruneEmptyEventTypes(entries)
            writeStatusEntries(prefs, entries)
            updateLegacyFieldsFrom(prefs, entries)
        }
    }

    suspend fun removeStatus(eventType: NotificationEventType, topic: String) {
        val sanitized = topic.trim()
        if (sanitized.isEmpty()) return
        context.dataStore.edit { prefs ->
            val entries = readStatusEntriesMutable(prefs)
            entries[eventType]?.remove(sanitized)
            pruneEmptyEventTypes(entries)
            writeStatusEntries(prefs, entries)
            updateLegacyFieldsFrom(prefs, entries)
        }
    }

    suspend fun updateWearableStatus(headline: String?, body: String?) {
        context.dataStore.edit { prefs ->
            if (headline == null) {
                prefs -= PreferenceKeys.wearableHeadline
            } else {
                prefs[PreferenceKeys.wearableHeadline] = headline
            }
            if (body == null) {
                prefs -= PreferenceKeys.wearableBody
            } else {
                prefs[PreferenceKeys.wearableBody] = body
            }
        }
    }

    suspend fun setAllowedTags(tags: Set<String>) {
        val sanitized = tags.mapNotNull { it.trim().takeIf { trimmed -> trimmed.isNotEmpty() } }
        val normalized = when {
            sanitized.isEmpty() -> setOf("*")
            sanitized.contains("*") -> setOf("*")
            else -> sanitized.toSet()
        }
        context.dataStore.edit { prefs ->
            prefs[PreferenceKeys.allowedTags] = normalized
        }
    }

    suspend fun addAllowedTag(tag: String) {
        val trimmed = tag.trim()
        if (trimmed.isEmpty()) return
        context.dataStore.edit { prefs ->
            val current = prefs[PreferenceKeys.allowedTags] ?: setOf("*")
            val updated = if (trimmed == "*") {
                setOf("*")
            } else {
                val sanitized = (current - "*").filterNot { it.equals(trimmed, ignoreCase = true) }.toMutableSet()
                sanitized.add(trimmed)
                sanitized.toSet()
            }
            prefs[PreferenceKeys.allowedTags] = updated
        }
    }

    suspend fun removeAllowedTag(tag: String) {
        context.dataStore.edit { prefs ->
            val current = prefs[PreferenceKeys.allowedTags] ?: setOf("*")
            val updated = (current - tag).takeIf { it.isNotEmpty() } ?: setOf("*")
            prefs[PreferenceKeys.allowedTags] = updated
        }
    }

    suspend fun setBlockedTags(tags: Set<String>) {
        val sanitized = mutableSetOf<String>()
        tags.mapNotNull { it.trim().takeIf { trimmed -> trimmed.isNotEmpty() } }
            .forEach { candidate ->
                sanitized.removeAll { it.equals(candidate, ignoreCase = true) }
                sanitized.add(candidate)
            }
        context.dataStore.edit { prefs ->
            prefs[PreferenceKeys.blockedTags] = sanitized
        }
    }

    suspend fun addBlockedTag(tag: String) {
        val trimmed = tag.trim()
        if (trimmed.isEmpty()) return
        context.dataStore.edit { prefs ->
            val current = prefs[PreferenceKeys.blockedTags] ?: emptySet()
            val sanitized = current.filterNot { it.equals(trimmed, ignoreCase = true) }.toMutableSet()
            sanitized.add(trimmed)
            prefs[PreferenceKeys.blockedTags] = sanitized
        }
    }

    suspend fun removeBlockedTag(tag: String) {
        context.dataStore.edit { prefs ->
            val current = prefs[PreferenceKeys.blockedTags] ?: emptySet()
            val sanitized = current.filterNot { it.equals(tag, ignoreCase = true) }.toSet()
            prefs[PreferenceKeys.blockedTags] = sanitized
        }
    }

    suspend fun appendPayload(rawPayload: String) {
        val trimmed = rawPayload.trim()
        if (trimmed.isEmpty()) return
        context.dataStore.edit { prefs ->
            prefs[PreferenceKeys.lastPayload] = trimmed
            prefs[PreferenceKeys.lastPayloadTimestamp] = System.currentTimeMillis()
            prefs -= legacyPayloadHistoryKey
        }
    }

    suspend fun setMissingStatusAlertPreference(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[PreferenceKeys.alertOnMissingStatus] = enabled
        }
    }

    suspend fun clearAllStatuses() {
        context.dataStore.edit { prefs ->
            prefs -= PreferenceKeys.statusEntries
            prefs -= PreferenceKeys.lastStatusTopic
            prefs -= PreferenceKeys.legacyLastStatusTitle
            prefs -= PreferenceKeys.lastStatusMessage
            prefs -= PreferenceKeys.lastStatusUpdatedAt
            prefs -= PreferenceKeys.lastStatusSequence
            prefs -= PreferenceKeys.lastStatusTimeoutSeconds
        }
    }

    private fun convertStatusEntries(preferences: Preferences): Map<NotificationEventType, Map<String, StatusSnapshot>> {
        val raw = readStatusEntries(preferences)
        return raw.mapValues { (_, topicMap) ->
            topicMap.mapValues { (topic, entry) ->
                StatusSnapshot(
                    topic = topic,
                    message = entry.message,
                    updatedAt = entry.updatedAt?.let(Instant::ofEpochMilli),
                    timeoutSeconds = entry.timeoutSeconds,
                    sequence = entry.sequence
                )
            }
        }
    }

    private fun readStatusEntries(preferences: Preferences): MutableMap<NotificationEventType, MutableMap<String, StoredStatusEntry>> {
        val stored = preferences[PreferenceKeys.statusEntries]
        if (stored.isNullOrEmpty()) {
            return migrateLegacyStatus(preferences)
        }
        val raw = runCatching { json.decodeFromString(statusEntriesSerializer, stored) }.getOrDefault(emptyMap())
        if (raw.isEmpty()) {
            return migrateLegacyStatus(preferences)
        }
        val result = mutableMapOf<NotificationEventType, MutableMap<String, StoredStatusEntry>>()
        raw.forEach { (typeKey, topics) ->
            val type = NotificationEventType.fromRaw(typeKey)
            result[type] = topics.toMutableMap()
        }
        return result
    }

    private fun migrateLegacyStatus(preferences: Preferences): MutableMap<NotificationEventType, MutableMap<String, StoredStatusEntry>> {
        val legacyTopic = preferences[PreferenceKeys.lastStatusTopic]
            ?: preferences[PreferenceKeys.legacyLastStatusTitle]
            ?: return mutableMapOf()
        val entry = StoredStatusEntry(
            message = preferences[PreferenceKeys.lastStatusMessage],
            updatedAt = preferences[PreferenceKeys.lastStatusUpdatedAt],
            timeoutSeconds = preferences[PreferenceKeys.lastStatusTimeoutSeconds],
            sequence = preferences[PreferenceKeys.lastStatusSequence]
        )
        return mutableMapOf(
            NotificationEventType.ONGOING to mutableMapOf(legacyTopic to entry)
        )
    }

    private fun readStatusEntriesMutable(preferences: MutablePreferences): MutableMap<NotificationEventType, MutableMap<String, StoredStatusEntry>> =
        readStatusEntries(preferences as Preferences)

    private fun writeStatusEntries(
        preferences: MutablePreferences,
        entries: MutableMap<NotificationEventType, MutableMap<String, StoredStatusEntry>>
    ) {
        if (entries.isEmpty()) {
            preferences -= PreferenceKeys.statusEntries
        } else {
            val payload = entries.mapKeys { it.key.rawValue }
                .mapValues { it.value }
            preferences[PreferenceKeys.statusEntries] = json.encodeToString(statusEntriesSerializer, payload)
        }
    }

    private fun updateLegacyFieldsFrom(
        preferences: MutablePreferences,
        entries: MutableMap<NotificationEventType, MutableMap<String, StoredStatusEntry>>
    ) {
        val ongoing = entries[NotificationEventType.ONGOING]
        if (ongoing.isNullOrEmpty()) {
            preferences -= PreferenceKeys.lastStatusTopic
            preferences -= PreferenceKeys.legacyLastStatusTitle
            preferences -= PreferenceKeys.lastStatusMessage
            preferences -= PreferenceKeys.lastStatusUpdatedAt
            preferences -= PreferenceKeys.lastStatusSequence
            preferences -= PreferenceKeys.lastStatusTimeoutSeconds
            return
        }
        val primary = ongoing.entries.maxByOrNull { it.value.sequence ?: it.value.updatedAt ?: 0L }
        if (primary == null) {
            preferences -= PreferenceKeys.lastStatusTopic
            preferences -= PreferenceKeys.legacyLastStatusTitle
            preferences -= PreferenceKeys.lastStatusMessage
            preferences -= PreferenceKeys.lastStatusUpdatedAt
            preferences -= PreferenceKeys.lastStatusSequence
            preferences -= PreferenceKeys.lastStatusTimeoutSeconds
            return
        }
        preferences[PreferenceKeys.lastStatusTopic] = primary.key
        preferences -= PreferenceKeys.legacyLastStatusTitle
        primary.value.message?.let { preferences[PreferenceKeys.lastStatusMessage] = it } ?: run {
            preferences -= PreferenceKeys.lastStatusMessage
        }
        primary.value.updatedAt?.let { preferences[PreferenceKeys.lastStatusUpdatedAt] = it }
            ?: run { preferences -= PreferenceKeys.lastStatusUpdatedAt }
        primary.value.sequence?.let { preferences[PreferenceKeys.lastStatusSequence] = it }
            ?: run { preferences -= PreferenceKeys.lastStatusSequence }
        primary.value.timeoutSeconds?.let { preferences[PreferenceKeys.lastStatusTimeoutSeconds] = it }
            ?: run { preferences -= PreferenceKeys.lastStatusTimeoutSeconds }
    }

    private fun pruneEmptyEventTypes(entries: MutableMap<NotificationEventType, MutableMap<String, StoredStatusEntry>>) {
        val emptyKeys = entries.filterValues { it.isEmpty() }.keys
        emptyKeys.forEach { entries.remove(it) }
    }
}
