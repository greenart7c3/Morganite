package com.greenart7c3.morganite

import android.app.Application
import android.util.Log
import com.greenart7c3.morganite.service.AndroidFileStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class Morganite: Application() {
    lateinit var httpServer: CustomHttpServer
    val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onCreate() {
        super.onCreate()

        Log.d(TAG, "onCreate")

        instance = this
        httpServer = CustomHttpServer(AndroidFileStore(this))
        scope.launch {
            httpServer.start()
        }
    }

    companion object {
        const val TAG = "Morganite"

        lateinit var instance: Morganite
            private set
    }
}
