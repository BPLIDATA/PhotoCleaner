package com.example.photocleaner

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.ImageView
import kotlin.math.abs
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.provider.MediaStore
import java.io.File

class CleanerService : Service() {
    companion object {
        @Volatile var appVisible = false
        @Volatile var instance: CleanerService? = null
    }
    private var bubble: ImageView? = null
    private val ui = Handler(Looper.getMainLooper())
    @Volatile private var running = false
    private var total = 0
    private var lastUpdCheck = 0L
    private var lastErr = ""
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
        instance = this
        showBubble(!appVisible)
        if (!running) {
            running = true
            Thread {
                while (running) {
                    val msg = try { clean() } catch (e: Exception) { "error: ${e.message}" }
                    getSystemService(NotificationManager::class.java).notify(1, notif(msg))
                    if (System.currentTimeMillis() - lastUpdCheck > 3L * 3600 * 1000) { lastUpdCheck = System.currentTimeMillis(); checkUpdate() }
                    try { Thread.sleep(10_000) } catch (_: InterruptedException) {}
                }
            }.start()
        }
        return START_STICKY
    }

    // ตรวจเวอร์ชันใหม่ทุก 3 ชั่วโมง แล้วแจ้งเตือน (แจ้งครั้งเดียวต่อเวอร์ชัน)
    private fun checkUpdate() {
        try {
            val latest = Updater.latestVersion() ?: return
            if (latest <= Updater.currentVersion(this)) return
            val p = getSharedPreferences("cfg", MODE_PRIVATE)
            if (p.getLong("notifiedVer", 0) >= latest) return
            p.edit().putLong("notifiedVer", latest).apply()
            val nm = getSystemService(NotificationManager::class.java)
            val nb = if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(NotificationChannel("upd", "อัปเดตแอป", NotificationManager.IMPORTANCE_DEFAULT))
                Notification.Builder(this, "upd")
            } else Notification.Builder(this)
            val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            nm.notify(2, nb.setContentTitle("Photo Cleaner มีเวอร์ชันใหม่ 1.$latest")
                .setContentText("แตะเพื่อเปิดแอปแล้วกดอัปเดต")
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentIntent(pi).setAutoCancel(true).build())
        } catch (_: Exception) {}
    }

    private fun tryDelete(f: File): Boolean {
        if (f.delete()) return true
        if (Build.VERSION.SDK_INT >= 26) {
            try { java.nio.file.Files.delete(f.toPath()); return true }
            catch (e: Exception) { lastErr = "${e.javaClass.simpleName}: ${e.message}" }
        }
        try {
            contentResolver.delete(
                MediaStore.Files.getContentUri("external"),
                MediaStore.MediaColumns.DATA + "=?", arrayOf(f.absolutePath)
            )
        } catch (e: Exception) { lastErr += " | MS: ${e.javaClass.simpleName}" }
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
            msg += "\n$lastErr"
        }
        return msg
    }

    // ไอคอนลอย: แสดงเมื่อไม่ได้เปิดหน้าแอปอยู่ แตะเพื่อกลับเข้าแอป ลากเพื่อย้ายตำแหน่ง
    fun showBubble(show: Boolean) {
        ui.post {
            if (!show) { removeBubble(); return@post }
            if (bubble != null) return@post
            if (!getSharedPreferences("cfg", MODE_PRIVATE).getBoolean("bubble", false)) return@post
            if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) return@post
            try {
                val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
                val size = (56 * resources.displayMetrics.density).toInt()
                @Suppress("DEPRECATION")
                val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                           else WindowManager.LayoutParams.TYPE_PHONE
                val lp = WindowManager.LayoutParams(size, size, type,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT)
                lp.gravity = Gravity.TOP or Gravity.START
                lp.x = 20; lp.y = 300
                val v = ImageView(this)
                v.setImageResource(R.mipmap.ic_launcher_round)
                var sx = 0; var sy = 0; var tx = 0f; var ty = 0f; var moved = false
                v.setOnTouchListener { _, e ->
                    when (e.action) {
                        MotionEvent.ACTION_DOWN -> { sx = lp.x; sy = lp.y; tx = e.rawX; ty = e.rawY; moved = false }
                        MotionEvent.ACTION_MOVE -> {
                            val dx = (e.rawX - tx).toInt(); val dy = (e.rawY - ty).toInt()
                            if (abs(dx) > 10 || abs(dy) > 10) moved = true
                            if (moved) { lp.x = sx + dx; lp.y = sy + dy; wm.updateViewLayout(v, lp) }
                        }
                        MotionEvent.ACTION_UP -> if (!moved)
                            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                    true
                }
                wm.addView(v, lp)
                bubble = v
            } catch (_: Exception) {}
        }
    }

    private fun removeBubble() {
        bubble?.let {
            try { (getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(it) } catch (_: Exception) {}
        }
        bubble = null
    }

    override fun onDestroy() { running = false; instance = null; removeBubble(); super.onDestroy() }
    override fun onBind(i: Intent?): IBinder? = null
}
