package com.notifryer.notification

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

object NotificationActionExecutor {

    suspend fun invokeGet(rawUrl: String) {
        val normalized = normalizeUrl(rawUrl) ?: return
        withContext(Dispatchers.IO) {
            var connection: HttpURLConnection? = null
            try {
                connection = (URL(normalized).openConnection() as? HttpURLConnection)?.apply {
                    requestMethod = "GET"
                    connectTimeout = DEFAULT_TIMEOUT_MS
                    readTimeout = DEFAULT_TIMEOUT_MS
                    useCaches = false
                }
                connection?.let { conn ->
                    conn.connect()
                    val code = runCatching { conn.responseCode }.getOrDefault(-1)
                    val stream = when {
                        code in 200..299 -> conn.inputStream
                        else -> conn.errorStream
                    }
                    stream?.let {
                        try {
                            BufferedInputStream(it).use { buffered ->
                                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                                while (buffered.read(buffer) != -1) {
                                    // Drain response
                                }
                            }
                        } catch (_: IOException) {
                            // Ignore
                        }
                    }
                }
            } catch (_: IOException) {
                // Swallow network errors; the action is best-effort.
            } finally {
                connection?.disconnect()
            }
        }
    }

    private fun normalizeUrl(rawUrl: String): String? {
        return runCatching {
            val uri = URI(rawUrl)
            if (uri.scheme.equals("http", ignoreCase = true) || uri.scheme.equals("https", ignoreCase = true)) {
                uri.toString()
            } else {
                null
            }
        }.getOrNull()
    }

    private const val DEFAULT_TIMEOUT_MS = 10_000
}
