package com.notifryer.app.service

import android.content.Context
import com.notifryer.app.R
import com.notifryer.app.data.AppSettingsRepository
import com.notifryer.notification.NotificationCoordinator
import com.notifryer.notification.NotificationEvent
import com.notifryer.notification.NotificationEventType
import com.notifryer.app.service.createAppNotificationConfig
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

object AppMissingStatusScheduler {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val jobs = ConcurrentHashMap<StatusKey, Job>()

    fun schedule(
        context: Context,
        type: NotificationEventType,
        topic: String,
        timeoutSeconds: Long?,
        updatedAtMillis: Long
    ) {
        val key = StatusKey(type, topic)
        jobs.remove(key)?.cancel()
        if (timeoutSeconds == null || timeoutSeconds <= 0) return

        val appContext = context.applicationContext
        val remaining = timeoutSeconds * 1_000 - (System.currentTimeMillis() - updatedAtMillis)
        val job = scope.launch {
            if (remaining > 0) {
                delay(remaining)
            }
            checkAndNotify(appContext, key, updatedAtMillis, timeoutSeconds)
            jobs.remove(key)
        }
        jobs[key] = job
    }

    fun cancel(type: NotificationEventType, topic: String) {
        jobs.remove(StatusKey(type, topic))?.cancel()
    }

    fun cancelTopic(topic: String) {
        NotificationEventType.values().forEach { cancel(it, topic) }
    }

    fun cancelAll() {
        jobs.values.forEach { it.cancel() }
        jobs.clear()
    }

    fun rescheduleAll(context: Context) {
        val appContext = context.applicationContext
        val repository = AppSettingsRepository(appContext)
        scope.launch {
            cancelAll()
            val snapshots = repository.getAllStatusSnapshots()
            val alertsEnabled = repository.isAlertOnMissingStatusEnabled()
            if (!alertsEnabled) return@launch
            val now = System.currentTimeMillis()
            snapshots.forEach { (type, topics) ->
                topics.forEach { (topic, snapshot) ->
                    val timeout = snapshot.timeoutSeconds ?: return@forEach
                    if (timeout <= 0L) return@forEach
                    val updatedAt = snapshot.updatedAt?.toEpochMilli() ?: return@forEach
                    val remaining = timeout * 1_000 - (now - updatedAt)
                    val job = scope.launch {
                        if (remaining > 0) delay(remaining)
                        checkAndNotify(appContext, StatusKey(type, topic), updatedAt, timeout)
                        jobs.remove(StatusKey(type, topic))
                    }
                    jobs[StatusKey(type, topic)] = job
                }
            }
        }
    }

    private suspend fun checkAndNotify(
        context: Context,
        key: StatusKey,
        expectedUpdatedAt: Long,
        timeoutSeconds: Long
    ) {
        val repository = AppSettingsRepository(context)
        if (!repository.isAlertOnMissingStatusEnabled()) return

        val snapshot = repository.getStatusSnapshot(key.type, key.topic) ?: return
        val lastUpdated = snapshot.updatedAt?.toEpochMilli() ?: return
        if (lastUpdated != expectedUpdatedAt) return

        val effectiveTimeout = snapshot.timeoutSeconds ?: timeoutSeconds
        if (effectiveTimeout <= 0L) return

        val elapsed = System.currentTimeMillis() - lastUpdated
        if (elapsed < effectiveTimeout * 1_000) return

        val coordinator = NotificationCoordinator(context, createAppNotificationConfig(context))
        val title = context.getString(R.string.missing_status_notification_title, formatTopic(key))
        val body = context.getString(
            R.string.missing_status_notification_body,
            formatTopic(key),
            effectiveTimeout
        )
        val event = NotificationEvent(
            timestamp = System.currentTimeMillis(),
            topicField = title,
            type = NotificationEventType.IMPORTANT.rawValue,
            text = body
        )
        coordinator.postEvent(NotificationEventType.IMPORTANT, event, silent = false)
    }

    private fun formatTopic(key: StatusKey): String = "${key.type.name.lowercase()}: ${key.topic}".trim()

    private data class StatusKey(val type: NotificationEventType, val topic: String)
}
