package com.greenart7c3.morganite

import android.app.Application
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.greenart7c3.morganite.models.SettingsManager
import com.greenart7c3.morganite.service.AndroidFileStore
import com.greenart7c3.morganite.service.HttpServerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class Morganite: Application() {
    lateinit var httpServer: CustomHttpServer
    lateinit var settingsManager: SettingsManager
    val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    val logStream = MutableStateFlow<List<String>>(emptyList())

    private var logStreamJob: Job? = null
    private var logStreamProcess: Process? = null

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
    }

    @Synchronized
    fun startLogStream() {
        if (logStreamJob?.isActive == true) return
        logStreamJob = scope.launch(Dispatchers.IO) {
            try {
                Runtime.getRuntime().exec("logcat -c")
                val process = Runtime.getRuntime().exec("logcat -v time")
                logStreamProcess = process
                process.inputStream.bufferedReader().use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.contains(TAG)) {
                            logStream.value = (logStream.value + line).takeLast(100)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start log stream", e)
            } finally {
                logStreamProcess = null
            }
        }
    }

    @Synchronized
    fun stopLogStream() {
        logStreamProcess?.destroy()
        logStreamProcess = null
        logStreamJob?.cancel()
        logStreamJob = null
    }

    fun startService() {
        try {
            ContextCompat.startForegroundService(
                this,
                Intent(this, HttpServerService::class.java),
            )
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
