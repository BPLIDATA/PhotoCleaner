package com.example.photocleaner

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (c.getSharedPreferences("cfg", Context.MODE_PRIVATE).getBoolean("enabled", false)) {
            val s = Intent(c, CleanerService::class.java)
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(s) else c.startService(s)
        }
    }
}
