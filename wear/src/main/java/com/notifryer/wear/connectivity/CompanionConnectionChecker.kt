package com.notifryer.wear.connectivity

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.tasks.await

object CompanionConnectionChecker {

    suspend fun isCompanionConnected(context: Context): Boolean {
        val nodes = runCatching {
            Wearable.getNodeClient(context).connectedNodes.await()
        }.getOrElse { error ->
            Log.w(TAG, "Unable to query connected nodes", error)
            return false
        }
        if (nodes.isEmpty()) {
            return false
        }
        return nodes.any { it.isNearby }
    }

    private const val TAG = "CompanionChecker"
}
