package com.example.photocleaner

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.Process
import java.io.File
import java.util.Locale

// ลบโฟลเดอร์ในพื้นหลัง: โฟลเดอร์ถูกเปลี่ยนชื่อเป็นชื่อซ่อนทันที แล้วค่อยๆ ลบไฟล์ข้างใน
// รายการที่ค้างอยู่ถูกจำไว้ ถ้าแอปถูกปิดกลางคัน จะลบต่อให้ตอนเปิดแอปครั้งถัดไป
class TrashService : Service() {
    @Volatile private var running = false

    companion object {
        private const val KEY = "pendingDel"

        fun add(ctx: Context, path: String) {
            val p = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE)
            val set = HashSet(p.getStringSet(KEY, emptySet()) ?: emptySet())
            set.add(path)
            p.edit().putStringSet(KEY, set).apply()
        }

        fun hasPending(ctx: Context): Boolean =
            !ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE).getStringSet(KEY, emptySet()).isNullOrEmpty()

        fun start(ctx: Context) {
            val i = Intent(ctx, TrashService::class.java)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }
    }

    private fun notif(text: String, ongoing: Boolean): Notification {
        val nb = if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(NotificationChannel("trash", "ลบโฟลเดอร์", NotificationManager.IMPORTANCE_LOW))
            Notification.Builder(this, "trash")
        } else Notification.Builder(this)
        return nb.setContentTitle("Photo Cleaner").setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setSmallIcon(android.R.drawable.ic_menu_delete)
            .setOngoing(ongoing).setAutoCancel(!ongoing).build()
    }

    private fun post(text: String, ongoing: Boolean) =
        getSystemService(NotificationManager::class.java).notify(3, notif(text, ongoing))

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(3, notif("กำลังลบโฟลเดอร์ในพื้นหลัง...", true))
        if (!running) {
            running = true
            Thread {
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)  // ให้โปรแกรมสแกนใช้เครื่องก่อน
                val summary = try {
                    var s = ""
                    do { s = work() } while (hasPending(this@TrashService))
                    s
                } catch (e: Exception) { "ลบไม่สำเร็จ: ${e.message}" }
                running = false
                post(summary, false)
                if (Build.VERSION.SDK_INT >= 24) stopForeground(Service.STOP_FOREGROUND_DETACH) else stopForeground(false)
                stopSelf()
            }.start()
        }
        return START_NOT_STICKY
    }

    private fun work(): String {
        val p = getSharedPreferences("cfg", MODE_PRIVATE)
        val t0 = System.currentTimeMillis()
        var okAll = 0L; var failAll = 0L
        var leftover = ""
        while (true) {
            val set = p.getStringSet(KEY, emptySet()) ?: emptySet()
            val path = set.firstOrNull() ?: break
            val dir = File(path)
            var ok = 0L; var fail = 0L
            var lastNote = 0L
            if (dir.exists()) {
                for (f in dir.walkBottomUp()) {
                    val del = f.delete()
                    if (f != dir) { if (del) ok++ else fail++ }
                    val now = System.currentTimeMillis()
                    if (now - lastNote > 2000) {
                        lastNote = now
                        post("กำลังลบ: ลบแล้ว ${okAll + ok} ไฟล์ | ล้มเหลว ${failAll + fail} | ใช้เวลา ${(now - t0) / 1000} วินาที", true)
                    }
                }
            }
            if (dir.exists()) leftover = path
            okAll += ok; failAll += fail
            p.edit().putLong("totDel", p.getLong("totDel", 0) + ok)
                .putLong("totFail", p.getLong("totFail", 0) + fail).apply()
            val ns = HashSet(set); ns.remove(path)   // เอาออกเสมอ กันวนลบซ้ำไม่จบ
            p.edit().putStringSet(KEY, ns).apply()
        }
        val secs = String.format(Locale.US, "%.1f", (System.currentTimeMillis() - t0) / 1000.0)
        var msg = "ลบเสร็จ: ${okAll} ไฟล์ ใช้ $secs วินาที"
        if (failAll > 0) msg += "\nล้มเหลว $failAll ไฟล์" + (if (leftover.isNotEmpty()) " (ยังเหลือที่ $leftover)" else "")
        return msg
    }

    override fun onBind(i: Intent?): IBinder? = null
}
