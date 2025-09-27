package com.notifryer.app.service

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.firebase.messaging.FirebaseMessaging
import com.notifryer.app.MessagingConstants
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.tasks.await

class TopicSubscriptionWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return runCatching {
            FirebaseMessaging.getInstance().subscribeToTopic(MessagingConstants.STATUS_TOPIC).await()
        }.fold(
            onSuccess = {
                Log.i(TAG, "Topic subscription verified for ${MessagingConstants.STATUS_TOPIC}")
                Result.success()
            },
            onFailure = {
                Log.e(TAG, "Topic subscription refresh failed", it)
                if (runAttemptCount < MAX_RETRY_ATTEMPTS) {
                    Result.retry()
                } else {
                    Result.failure()
                }
            }
        )
    }

    companion object {
        private const val TAG = "TopicSubscription"
        private const val UNIQUE_WORK_NAME = "refresh_status_topic_subscription"
        private const val UNIQUE_IMMEDIATE_NAME = "refresh_status_topic_subscription_now"
        private const val MAX_RETRY_ATTEMPTS = 3
        private val PERIODIC_INTERVAL_HOURS = 6L

        fun schedulePeriodic(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<TopicSubscriptionWorker>(
                PERIODIC_INTERVAL_HOURS,
                TimeUnit.HOURS
            )
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }

        fun runImmediate(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = OneTimeWorkRequestBuilder<TopicSubscriptionWorker>()
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_IMMEDIATE_NAME,
                ExistingWorkPolicy.REPLACE,
                request
            )
        }
    }
}
