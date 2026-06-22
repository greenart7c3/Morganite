package com.greenart7c3.morganite.logs

import android.util.Log
import com.greenart7c3.morganite.BuildConfig
import com.greenart7c3.morganite.Morganite
import kotlinx.coroutines.launch

/**
 * Logging entry point for the app. Persists every log line to the local Room
 * database (the source of truth for the in-app log viewer) and, in debug builds
 * only, also forwards to [android.util.Log] so `adb logcat` keeps working.
 */
object MorganiteLog {
    fun d(tag: String, message: String) = log("D", tag, message, null)

    fun i(tag: String, message: String) = log("I", tag, message, null)

    fun w(tag: String, message: String, throwable: Throwable? = null) = log("W", tag, message, throwable)

    fun e(tag: String, message: String, throwable: Throwable? = null) = log("E", tag, message, throwable)

    private fun log(level: String, tag: String, message: String, throwable: Throwable?) {
        if (BuildConfig.DEBUG) {
            when (level) {
                "E" -> Log.e(tag, message, throwable)
                "W" -> Log.w(tag, message, throwable)
                "I" -> Log.i(tag, message)
                else -> Log.d(tag, message)
            }
        }

        val fullMessage = if (throwable != null) {
            "$message\n${Log.getStackTraceString(throwable)}"
        } else {
            message
        }

        val app = Morganite.instance
        val dao = LogDatabase.getInstance(app).logDao()
        app.scope.launch {
            try {
                dao.insert(
                    LogEntry(
                        timestamp = System.currentTimeMillis(),
                        level = level,
                        tag = tag,
                        message = fullMessage,
                    ),
                )
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) Log.e(tag, "Failed to persist log entry", e)
            }
        }
    }
}
