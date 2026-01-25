package com.notifryer.notification

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class NotificationEvent(
    val timestamp: Long = 0L,
    @SerialName("topic")
    private val topicField: String? = null,
    @SerialName("title")
    private val legacyTitleField: String? = null,
    val remove: Boolean = false,
    val permanent: Boolean = false,
    val type: String = NotificationEventType.ONGOING.rawValue,
    val tags: List<String> = emptyList(),
    val text: String = "",
    @SerialName("vibrate_pattern")
    val vibratePattern: List<Long>? = null,
    val wearable: WearPayload? = null,
    val actions: List<NotificationAction> = emptyList(),
    val timeout: Long? = null
) {
    val topic: String
        get() = (topicField ?: legacyTitleField ?: "").trim()

    val eventType: NotificationEventType
        get() = NotificationEventType.fromRaw(type)

    val timestampMillis: Long
        get() = when {
            timestamp <= 0L -> 0L
            timestamp < 1_000_000_000_000L -> timestamp * 1_000
            else -> timestamp
        }

    fun shouldDisplay(allowed: Set<String>, blocked: Set<String> = emptySet()): Boolean {
        val normalizedTags = tags.mapNotNull { it.trim().takeIf(String::isNotEmpty)?.lowercase() }

        val passesAllowlist = when {
            allowed.isEmpty() || allowed.contains("*") -> true
            tags.isEmpty() -> true
            tags.any { it.trim() == "*" } -> true
            else -> normalizedTags.any { allowed.contains(it) }
        }
        if (!passesAllowlist) return false

        if (blocked.contains("*")) return false
        if (blocked.isNotEmpty() && normalizedTags.any { blocked.contains(it) }) {
            return false
        }
        return true
    }
}

@Serializable
data class WearPayload(
    val headline: String? = null,
    val body: String? = null
)

@Serializable
data class NotificationAction(
    val name: String,
    val url: String
)

enum class NotificationEventType(val rawValue: String) {
    ONGOING("ongoing"),
    NORMAL("normal"),
    IMPORTANT("important");

    companion object {
        fun fromRaw(raw: String?): NotificationEventType {
            if (raw.equals("silent", ignoreCase = true) || raw.equals("quiet", ignoreCase = true)) {
                return NORMAL
            }
            if (raw.equals("loud", ignoreCase = true)) {
                return IMPORTANT
            }
            return values()
                .firstOrNull { it.rawValue.equals(raw, ignoreCase = true) }
                ?: ONGOING
        }
    }
}
