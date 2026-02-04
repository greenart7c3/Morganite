package com.greenart7c3.morganite

import android.app.Application
import android.util.Log

class Morganite: Application() {
    override fun onCreate() {
        super.onCreate()

        Log.d(TAG, "onCreate")

        instance = this
    }

    companion object {
        const val TAG = "Morganite"

        lateinit var instance: Morganite
            private set
    }
}
