package com.greenart7c3.morganite.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.greenart7c3.morganite.Morganite

class BootBroadcastReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_PACKAGE_REPLACED && Build.VERSION.SDK_INT < Build.VERSION_CODES.S && intent.dataString?.contains("com.greenart7c3.morganite") == true) {
            Morganite.instance.startService()
        } else if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            Morganite.instance.startService()
        } else if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Morganite.instance.startService()
        }
    }
}
