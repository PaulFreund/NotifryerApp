package com.notifryer.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.messaging.FirebaseMessaging
import com.notifryer.app.MessagingConstants
import com.notifryer.app.data.AppSettingsRepository
import com.notifryer.app.data.PayloadLog
import com.notifryer.app.data.StatusSnapshot
import com.notifryer.notification.NotificationEvent
import com.notifryer.notification.NotificationEventType
import com.notifryer.notification.NotificationCoordinator
import com.notifryer.app.service.createAppNotificationConfig
import com.notifryer.app.service.AppMissingStatusScheduler
import com.notifryer.app.R
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.flow.first
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlin.comparisons.compareBy
import kotlin.comparisons.thenBy

data class SettingsUiState(
    val statuses: List<StatusSnapshot> = emptyList(),
    val wearableHeadline: String? = null,
    val wearableBody: String? = null,
    val allowlist: List<String> = listOf("*"),
    val blocklist: List<String> = emptyList(),
    val debugPayloadInput: String = "",
    val debugError: String? = null,
    val lastPayload: PayloadLog? = null,
    val alertOnMissingStatus: Boolean = true
)

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = AppSettingsRepository(application.applicationContext)
    private val notifications = NotificationCoordinator(
        application.applicationContext,
        createAppNotificationConfig(application.applicationContext)
    )
    private val json = Json { ignoreUnknownKeys = true }
    private val eventsSerializer = ListSerializer(NotificationEvent.serializer())
    private val defaultDebugPayload = application.getString(R.string.debug_default_payload)
    private val appContext = application.applicationContext

    private val _uiState = MutableStateFlow(SettingsUiState(debugPayloadInput = defaultDebugPayload))
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.setForegroundPreference(true)
        }
        viewModelScope.launch {
            repository.settingsFlow.collectLatest { settings ->
                _uiState.update { current ->
                    val sortedStatuses = settings.statusEntries[NotificationEventType.ONGOING]
                        ?.values
                        ?.sortedByDescending { it.sequence ?: it.updatedAt?.toEpochMilli() ?: 0L }
                        ?: emptyList()
                    current.copy(
                        statuses = sortedStatuses,
                        wearableHeadline = settings.wearableHeadline,
                        wearableBody = settings.wearableBody,
                        allowlist = settings.allowedTags.sortedWith(compareBy<String> { if (it == "*") 0 else 1 }.thenBy { it.lowercase() }),
                        blocklist = settings.blockedTags.sortedBy { it.lowercase() },
                        lastPayload = settings.lastPayload,
                        alertOnMissingStatus = settings.alertOnMissingStatus
                    )
                }
            }
        }
        ensureTopicSubscription()
    }

    private fun ensureTopicSubscription() {
        viewModelScope.launch {
            runCatching {
                FirebaseMessaging.getInstance().subscribeToTopic(MessagingConstants.STATUS_TOPIC).await()
            }
        }
    }

    fun addAllowedTag(tag: String) {
        val trimmed = tag.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            repository.addAllowedTag(trimmed)
        }
    }

    fun removeAllowedTag(tag: String) {
        viewModelScope.launch {
            repository.removeAllowedTag(tag)
        }
    }

    fun addBlockedTag(tag: String) {
        val trimmed = tag.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            repository.addBlockedTag(trimmed)
        }
    }

    fun removeBlockedTag(tag: String) {
        viewModelScope.launch {
            repository.removeBlockedTag(tag)
        }
    }

    fun updateMissingStatusAlert(enabled: Boolean) {
        viewModelScope.launch {
            repository.setMissingStatusAlertPreference(enabled)
            if (!enabled) {
                AppMissingStatusScheduler.cancelAll()
            } else {
                AppMissingStatusScheduler.rescheduleAll(appContext)
            }
        }
    }

    fun updateDebugPayloadInput(value: String) {
        _uiState.update { it.copy(debugPayloadInput = value, debugError = null) }
    }

    fun sendDebugPayload() {
        val payloadText = uiState.value.debugPayloadInput.trim()
        if (payloadText.isEmpty()) {
            val message = getApplication<Application>().getString(R.string.debug_payload_error_empty)
            _uiState.update { it.copy(debugError = message) }
            return
        }
        val parsedEvents = runCatching { json.decodeFromString(eventsSerializer, payloadText) }
            .getOrElse { primaryError ->
                val singleEvent = runCatching { json.decodeFromString(NotificationEvent.serializer(), payloadText) }
                    .getOrNull()
                if (singleEvent != null) {
                    listOf(singleEvent)
                } else {
                    val detail = primaryError.localizedMessage?.takeIf { it.isNotBlank() }
                        ?: primaryError.message?.takeIf { it.isNotBlank() }
                    val message = if (detail == null) {
                        getApplication<Application>().getString(R.string.debug_payload_error_unknown)
                    } else {
                        getApplication<Application>().getString(R.string.debug_payload_error_generic, detail)
                    }
                    _uiState.update { it.copy(debugError = message) }
                    return
                }
            }
        viewModelScope.launch {
            repository.appendPayload(payloadText)
            processEvents(parsedEvents)
            _uiState.update { it.copy(debugError = null) }
        }
    }

    private suspend fun processEvents(events: List<NotificationEvent>) {
        if (events.isEmpty()) return
        val settings = repository.settingsFlow.first()
        val allowedTags = settings.allowedTags.map { it.trim().lowercase() }.toSet()
        val blockedTags = settings.blockedTags.map { it.trim().lowercase() }.toSet()

        events.forEach { event ->
            if (event.remove) {
                notifications.cancelTopic(event.topic)
                AppMissingStatusScheduler.cancelTopic(event.topic)
                repository.removeStatus(event.eventType, event.topic)
                return@forEach
            }
            if (!event.shouldDisplay(allowedTags, blockedTags)) return@forEach

            val eventInstant = if (event.timestampMillis > 0L) Instant.ofEpochMilli(event.timestampMillis) else Instant.now()
            val wearableHeadline = event.wearable?.headline ?: event.topic
            val resolvedBody = event.wearable?.body ?: event.text.ifBlank { event.topic }
            val statusBody = event.text.ifBlank { event.topic }

            val missingNotificationTitle = appContext.getString(
                R.string.missing_status_notification_title,
                formatTimeoutTopic(event.eventType, event.topic)
            )
            notifications.cancelEvent(NotificationEventType.IMPORTANT, missingNotificationTitle)

            repository.updateStatus(event.eventType, event.topic, statusBody, eventInstant, event.timeout)
            repository.updateWearableStatus(wearableHeadline, resolvedBody)

            val silent = event.eventType != NotificationEventType.IMPORTANT
            notifications.postEvent(event.eventType, event, silent)

            AppMissingStatusScheduler.schedule(
                context = appContext,
                type = event.eventType,
                topic = event.topic,
                timeoutSeconds = event.timeout,
                updatedAtMillis = eventInstant.toEpochMilli()
            )
        }
    }

    private fun formatTimeoutTopic(type: NotificationEventType, topic: String): String {
        return "${type.name.lowercase()}: ${topic}".trim()
    }
}
