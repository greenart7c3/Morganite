package com.greenart7c3.morganite.logs

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * Periodically deletes log entries older than [LOG_RETENTION_MS] (1 week).
 * Scheduled as unique periodic work from [com.greenart7c3.morganite.Morganite].
 */
class LogCleanupWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        return try {
            val cutoff = System.currentTimeMillis() - LOG_RETENTION_MS
            LogDatabase.getInstance(applicationContext).logDao().deleteOlderThan(cutoff)
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
        const val WORK_NAME = "log_cleanup"
        const val LOG_RETENTION_MS = 7L * 24 * 60 * 60 * 1000
    }
}
