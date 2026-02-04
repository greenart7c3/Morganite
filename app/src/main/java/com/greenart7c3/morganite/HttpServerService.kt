package com.greenart7c3.morganite

import android.app.Service
import android.content.Intent
import android.os.IBinder

class HttpServerService : Service() {

    override fun onBind(intent: Intent): IBinder {
        TODO("Return the communication channel to the service.")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }
}
