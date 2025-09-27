package com.notifryer.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        TopicSubscriptionWorker.schedulePeriodic(context)
        TopicSubscriptionWorker.runImmediate(context)
        AppMissingStatusScheduler.rescheduleAll(context)
    }
}
