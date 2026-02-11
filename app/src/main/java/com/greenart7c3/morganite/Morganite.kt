package com.greenart7c3.morganite

import android.app.AlarmManager
import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import android.util.Log
import com.greenart7c3.morganite.models.SettingsManager
import com.greenart7c3.morganite.service.AndroidFileStore
import com.greenart7c3.morganite.service.HttpServerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class Morganite: Application() {
    lateinit var httpServer: CustomHttpServer
    lateinit var settingsManager: SettingsManager
    val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    val logStream = MutableStateFlow<List<String>>(emptyList())

    override fun onCreate() {
        super.onCreate()

        Log.d(TAG, "onCreate")

        instance = this
        settingsManager = SettingsManager(this)
        startService()
        httpServer = CustomHttpServer(AndroidFileStore(this), settingsManager)
        scope.launch {
            httpServer.start()
        }
        startLogStream()
    }

    private fun startLogStream() {
        scope.launch(Dispatchers.IO) {
            try {
                Runtime.getRuntime().exec("logcat -c")
                val process = Runtime.getRuntime().exec("logcat -v time")
                val reader = process.inputStream.bufferedReader()
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.contains(TAG)) {
                        logStream.value = (logStream.value + line).takeLast(100)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start log stream", e)
            }
        }
    }

    fun startService() {
        try {
            val operation = PendingIntent.getForegroundService(
                this,
                10,
                Intent(this, HttpServerService::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            val alarmManager = this.getSystemService(ALARM_SERVICE) as AlarmManager
            alarmManager.set(AlarmManager.RTC_WAKEUP, 1000, operation)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start HttpServerService", e)
        }
    }

    companion object {
        const val TAG = "Morganite"

        lateinit var instance: Morganite
            private set
    }
}
