package com.example.photocleaner

import android.app.*
import android.content.Intent
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.provider.MediaStore
import java.io.File

class CleanerService : Service() {
    @Volatile private var running = false
    private var total = 0
    private val exts = setOf("jpg", "jpeg", "png", "bmp", "webp")

    private fun notif(text: String): Notification {
        val nb = if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(NotificationChannel("pc", "Photo Cleaner", NotificationManager.IMPORTANCE_LOW))
            Notification.Builder(this, "pc")
        } else Notification.Builder(this)
        return nb.setContentTitle("Photo Cleaner").setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setSmallIcon(android.R.drawable.ic_menu_delete).build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(1, notif("เริ่มทำงาน..."))
        if (!running) {
            running = true
            Thread {
                while (running) {
                    val msg = try { clean() } catch (e: Exception) { "error: ${e.message}" }
                    getSystemService(NotificationManager::class.java).notify(1, notif(msg))
                    try { Thread.sleep(10_000) } catch (_: InterruptedException) {}
                }
            }.start()
        }
        return START_STICKY
    }

    private fun tryDelete(f: File): Boolean {
        if (f.delete()) return true
        try {
            contentResolver.delete(
                MediaStore.Files.getContentUri("external"),
                MediaStore.MediaColumns.DATA + "=?", arrayOf(f.absolutePath)
            )
        } catch (_: Exception) {}
        return !f.exists()
    }

    private fun clean(): String {
        val p = getSharedPreferences("cfg", MODE_PRIVATE)
        val n = p.getInt("n", 1000)
        val newest = p.getBoolean("newest", true)
        val path = p.getString("path", "") ?: ""
        val dir = File(path)
        if (!dir.isDirectory) return "ไม่พบโฟลเดอร์: $path"
        val files = dir.listFiles { f -> f.isFile && f.extension.lowercase() in exts }
            ?: return "อ่านโฟลเดอร์ไม่ได้ (ไม่มีสิทธิ์?): $path"
        if (files.size <= n) return "พบ ${files.size} รูป (จะลบเมื่อเกิน $n) ลบสะสม $total\n$path"
        val now = System.currentTimeMillis()
        val sorted = if (newest) files.sortedByDescending { it.lastModified() } else files.sortedBy { it.lastModified() }
        val deleted = ArrayList<String>()
        var fail = 0
        var firstFail: File? = null
        for (f in sorted.take(n)) {
            if (now - f.lastModified() < 5000) continue
            if (tryDelete(f)) deleted.add(f.absolutePath) else { fail++; if (firstFail == null) firstFail = f }
        }
        total += deleted.size
        if (deleted.isNotEmpty())
            MediaScannerConnection.scanFile(this, deleted.toTypedArray(), null, null)
        var msg = "ลบรอบนี้ ${deleted.size} ล้มเหลว $fail ลบสะสม $total\n$path"
        if (fail > 0 && firstFail != null) {
            val allFiles = if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager().toString() else "n/a"
            msg += "\nAPI=${Build.VERSION.SDK_INT} allFiles=$allFiles canWrite=${firstFail.canWrite()} dirWrite=${dir.canWrite()}"
        }
        return msg
    }

    override fun onDestroy() { running = false; super.onDestroy() }
    override fun onBind(i: Intent?): IBinder? = null
}
