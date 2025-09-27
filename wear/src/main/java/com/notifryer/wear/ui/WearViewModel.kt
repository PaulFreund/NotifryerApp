package com.notifryer.wear.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.messaging.FirebaseMessaging
import com.notifryer.wear.MessagingConstants
import com.notifryer.wear.data.WearOngoingTopic
import com.notifryer.wear.data.WearStatusRepository
import com.notifryer.wear.worker.MissingStatusScheduler
import java.time.Instant
import kotlin.comparisons.compareBy
import kotlin.comparisons.thenBy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

data class WearUiState(
    val headline: String? = null,
    val body: String? = null,
    val updatedAt: Instant? = null,
    val vibrate: Boolean = false,
    val allowlist: List<String> = listOf("*"),
    val blocklist: List<String> = emptyList(),
    val showOngoingNotifications: Boolean = false,
    val alertOnMissingStatus: Boolean = true,
    val suppressSilentWhenConnected: Boolean = true,
    val suppressImportantWhenConnected: Boolean = true,
    val ongoingTopics: List<WearOngoingTopic> = emptyList()
)

class WearViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = WearStatusRepository(application.applicationContext)
    private val _uiState = MutableStateFlow(WearUiState())
    val uiState: StateFlow<WearUiState> = _uiState.asStateFlow()
    private var lastAlertOnMissingStatus: Boolean? = null
    private var lastSuppressImportantWhenConnected: Boolean? = null

    init {
        ensureTopicSubscription()
        viewModelScope.launch {
            repository.wearStatus.collectLatest { status ->
                _uiState.update {
                    it.copy(
                        headline = status.headline,
                        body = status.body,
                        updatedAt = status.updatedAt,
                        vibrate = status.vibrate,
                        allowlist = status.allowlist.sortedWith(compareBy<String> { if (it == "*") 0 else 1 }.thenBy { it.lowercase() }),
                        blocklist = status.blocklist.sortedBy { it.lowercase() },
                        showOngoingNotifications = status.showOngoingNotifications,
                        alertOnMissingStatus = status.alertOnMissingStatus,
                        suppressSilentWhenConnected = status.suppressSilentWhenConnected,
                        suppressImportantWhenConnected = status.suppressImportantWhenConnected,
                        ongoingTopics = status.ongoingTopics
                    )
                }
                val previousAlertState = lastAlertOnMissingStatus
                if (previousAlertState != status.alertOnMissingStatus) {
                    lastAlertOnMissingStatus = status.alertOnMissingStatus
                    if (status.alertOnMissingStatus) {
                        MissingStatusScheduler.rescheduleAll(getApplication(), repository)
                    } else {
                        MissingStatusScheduler.cancelAll()
                    }
                }
                val previousImportantSuppress = lastSuppressImportantWhenConnected
                if (previousImportantSuppress != status.suppressImportantWhenConnected) {
                    lastSuppressImportantWhenConnected = status.suppressImportantWhenConnected
                    if (status.suppressImportantWhenConnected) {
                        MissingStatusScheduler.cancelAll()
                    } else if (status.alertOnMissingStatus) {
                        MissingStatusScheduler.rescheduleAll(
                            context = getApplication(),
                            repository = repository
                        )
                    }
                }
            }
        }
    }

    private fun ensureTopicSubscription() {
        viewModelScope.launch {
            runCatching {
                FirebaseMessaging.getInstance().subscribeToTopic(MessagingConstants.STATUS_TOPIC).await()
            }
        }
    }

    fun addAllowTag(tag: String) {
        val trimmed = tag.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            repository.addAllowTag(trimmed)
        }
    }

    fun removeAllowTag(tag: String) {
        viewModelScope.launch {
            repository.removeAllowTag(tag)
        }
    }

    fun addBlockTag(tag: String) {
        val trimmed = tag.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            repository.addBlockTag(trimmed)
        }
    }

    fun removeBlockTag(tag: String) {
        viewModelScope.launch {
            repository.removeBlockTag(tag)
        }
    }

    fun updateShowOngoing(enabled: Boolean) {
        viewModelScope.launch {
            repository.setShowOngoingNotifications(enabled)
        }
    }

    fun updateMissingAlerts(enabled: Boolean) {
        viewModelScope.launch {
            repository.setAlertOnMissingStatus(enabled)
            if (!enabled) {
                MissingStatusScheduler.cancelAll()
            } else {
                MissingStatusScheduler.rescheduleAll(
                    context = getApplication(),
                    repository = repository
                )
            }
        }
    }

    fun updateSuppressSilentWhenConnected(enabled: Boolean) {
        viewModelScope.launch {
            repository.setSuppressSilentWhenConnected(enabled)
        }
    }

    fun updateSuppressImportantWhenConnected(enabled: Boolean) {
        viewModelScope.launch {
            repository.setSuppressImportantWhenConnected(enabled)
            if (enabled) {
                MissingStatusScheduler.cancelAll()
            } else {
                MissingStatusScheduler.rescheduleAll(
                    context = getApplication(),
                    repository = repository
                )
            }
        }
    }
}
