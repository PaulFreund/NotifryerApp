package com.notifryer.wear.worker

import android.content.Context
import com.notifryer.wear.R
import com.notifryer.wear.connectivity.CompanionConnectionChecker
import com.notifryer.wear.data.WearStatusRepository
import com.notifryer.notification.NotificationCoordinator
import com.notifryer.notification.NotificationEvent
import com.notifryer.notification.NotificationEventType
import com.notifryer.wear.notification.createWearNotificationConfig
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

object MissingStatusScheduler {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val jobs = ConcurrentHashMap<String, Job>()

    fun schedule(
        context: Context,
        repository: WearStatusRepository,
        topic: String,
        updatedAt: Instant,
        timeoutSeconds: Long?,
        alertsEnabled: Boolean
    ) {
        cancel(topic)
        if (!alertsEnabled) return
        val appContext = context.applicationContext
        val job = scope.launch {
            val effectiveTimeout = timeoutSeconds ?: repository.getOngoingStatus(topic)?.timeoutSeconds
            if (effectiveTimeout == null || effectiveTimeout <= 0L) return@launch
            val elapsed = System.currentTimeMillis() - updatedAt.toEpochMilli()
            val remaining = effectiveTimeout * 1_000 - elapsed
            if (remaining > 0L) {
                delay(remaining)
            }
            checkAndNotify(appContext, repository, topic, updatedAt.toEpochMilli(), effectiveTimeout)
            jobs.remove(topic)
        }
        jobs[topic] = job
    }

    fun cancel(topic: String) {
        jobs.remove(topic)?.cancel()
    }

    fun cancelAll() {
        jobs.values.forEach { it.cancel() }
        jobs.clear()
    }

    fun rescheduleAll(context: Context, repository: WearStatusRepository) {
        val appContext = context.applicationContext
        scope.launch {
            cancelAll()
            val statuses = repository.getAllOngoingStatuses()
            val now = System.currentTimeMillis()
            val alertsEnabled = repository.isAlertOnMissingStatusEnabled()
            if (!alertsEnabled) return@launch
            statuses.forEach { (topic, snapshot) ->
                val timeout = snapshot.timeoutSeconds ?: return@forEach
                val updatedAtMillis = snapshot.updatedAt ?: return@forEach
                if (timeout <= 0L) return@forEach
                val elapsed = now - updatedAtMillis
                val remainingMillis = timeout * 1_000 - elapsed
                val delayMillis = if (remainingMillis > 0) remainingMillis else 0L
                val job = scope.launch {
                    if (delayMillis > 0L) {
                        delay(delayMillis)
                    }
                    checkAndNotify(appContext, repository, topic, updatedAtMillis, timeout)
                    jobs.remove(topic)
                }
                jobs[topic] = job
            }
        }
    }

    private suspend fun checkAndNotify(
        context: Context,
        repository: WearStatusRepository,
        topic: String,
        expectedUpdatedAt: Long,
        timeoutSeconds: Long
    ) {
        if (!repository.isAlertOnMissingStatusEnabled()) return
        if (repository.isSuppressImportantWhenConnectedEnabled() &&
            CompanionConnectionChecker.isCompanionConnected(context)
        ) {
            return
        }
        val snapshot = repository.getOngoingStatus(topic) ?: return
        val lastUpdated = snapshot.updatedAt ?: return
        if (lastUpdated != expectedUpdatedAt) {
            return
        }
        val effectiveTimeout = snapshot.timeoutSeconds ?: timeoutSeconds
        if (effectiveTimeout <= 0L) return
        val now = System.currentTimeMillis()
        val elapsed = now - lastUpdated
        if (elapsed < effectiveTimeout * 1_000) {
            return
        }
        val coordinator = NotificationCoordinator(context, createWearNotificationConfig(context))
        val notification = NotificationEvent(
            timestamp = now,
            topicField = context.getString(R.string.missing_status_title, topic),
            text = context.getString(R.string.missing_status_body, topic, effectiveTimeout),
            type = NotificationEventType.IMPORTANT.rawValue
        )
        coordinator.postEvent(NotificationEventType.IMPORTANT, notification, silent = false)
    }
}
