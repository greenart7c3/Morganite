package com.greenart7c3.morganite

import android.app.Application
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.greenart7c3.morganite.logs.LogCleanupWorker
import com.greenart7c3.morganite.logs.LogDatabase
import com.greenart7c3.morganite.logs.LogEntry
import com.greenart7c3.morganite.logs.MorganiteLog
import com.greenart7c3.morganite.models.SettingsManager
import com.greenart7c3.morganite.service.AndroidFileStore
import com.greenart7c3.morganite.service.HttpServerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class Morganite : Application() {
    lateinit var httpServer: CustomHttpServer
    lateinit var settingsManager: SettingsManager
    lateinit var logDatabase: LogDatabase
    val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    /**
     * Last [LOG_VIEWER_LIMIT] log lines from the local database, oldest first so
     * the UI can auto-scroll to the most recent entry.
     */
    val logs: StateFlow<List<String>> by lazy {
        logDatabase.logDao().recent(LOG_VIEWER_LIMIT).map { entries ->
            entries.asReversed().map { it.format() }
        }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyList())
    }

    override fun onCreate() {
        super.onCreate()

        instance = this
        logDatabase = LogDatabase.getInstance(this)

        MorganiteLog.d(TAG, "onCreate")

        settingsManager = SettingsManager(this)
        scheduleLogCleanup()
        startService()
        httpServer = CustomHttpServer(AndroidFileStore(this), settingsManager)
        scope.launch {
            httpServer.start()
        }
    }

    private fun scheduleLogCleanup() {
        val request = PeriodicWorkRequestBuilder<LogCleanupWorker>(1, TimeUnit.HOURS).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            LogCleanupWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    private fun LogEntry.format(): String =
        "${timeFormat.format(Date(timestamp))} $level/$tag: $message"

    fun startService() {
        try {
            ContextCompat.startForegroundService(
                this,
                Intent(this, HttpServerService::class.java),
            )
        } catch (e: Exception) {
            MorganiteLog.e(TAG, "Failed to start HttpServerService", e)
        }
    }

    companion object {
        const val TAG = "Morganite"
        const val LOG_VIEWER_LIMIT = 500

        lateinit var instance: Morganite
            private set
    }
}
