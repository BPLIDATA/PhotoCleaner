package com.example.photocleaner

import android.app.*
import android.content.Intent
import android.media.MediaScannerConnection
import android.os.Build
import android.os.IBinder
import java.io.File

class CleanerService : Service() {
    @Volatile private var running = false
    private val exts = setOf("jpg", "jpeg", "png", "bmp", "webp")

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nb = if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel("pc", "Photo Cleaner", NotificationManager.IMPORTANCE_LOW))
            Notification.Builder(this, "pc")
        } else Notification.Builder(this)
        startForeground(1, nb.setContentTitle("Photo Cleaner").setContentText("กำลังเฝ้าดูโฟลเดอร์รูป")
            .setSmallIcon(android.R.drawable.ic_menu_delete).build())
        if (!running) {
            running = true
            Thread {
                while (running) {
                    try { clean() } catch (_: Exception) {}
                    try { Thread.sleep(10_000) } catch (_: InterruptedException) {}
                }
            }.start()
        }
        return START_STICKY
    }

    private fun clean() {
        val p = getSharedPreferences("cfg", MODE_PRIVATE)
        val n = p.getInt("n", 1000)
        val newest = p.getBoolean("newest", true)
        val files = File(p.getString("path", "")!!).listFiles { f ->
            f.isFile && f.extension.lowercase() in exts
        } ?: return
        if (files.size <= n) return
        val now = System.currentTimeMillis()
        val sorted = if (newest) files.sortedByDescending { it.lastModified() } else files.sortedBy { it.lastModified() }
        val deleted = ArrayList<String>()
        for (f in sorted.take(n)) {
            if (now - f.lastModified() < 5000) continue // ข้ามไฟล์ที่เพิ่งเขียนอยู่
            if (f.delete()) deleted.add(f.absolutePath)
        }
        if (deleted.isNotEmpty())
            MediaScannerConnection.scanFile(this, deleted.toTypedArray(), null, null)
    }

    override fun onDestroy() { running = false; super.onDestroy() }
    override fun onBind(i: Intent?): IBinder? = null
}
