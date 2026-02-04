package com.greenart7c3.morganite

import android.app.Application
import android.util.Log
import com.greenart7c3.morganite.service.AndroidFileStore

class Morganite: Application() {
    lateinit var httpServer: CustomHttpServer

    override fun onCreate() {
        super.onCreate()

        Log.d(TAG, "onCreate")

        instance = this
        httpServer = CustomHttpServer(AndroidFileStore(this))
        httpServer.start()
    }

    companion object {
        const val TAG = "Morganite"

        lateinit var instance: Morganite
            private set
    }
}
