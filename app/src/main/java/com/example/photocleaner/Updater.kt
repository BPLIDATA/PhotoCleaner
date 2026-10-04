package com.example.photocleaner

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object Updater {
    private const val REPO = "BPLIDATA/PhotoCleaner"
    private const val APK_URL = "https://github.com/$REPO/releases/latest/download/PhotoCleaner.apk"

    fun currentVersion(c: Context): Long {
        val i = c.packageManager.getPackageInfo(c.packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) i.longVersionCode else i.versionCode.toLong()
    }

    fun versionName(c: Context): String =
        try { c.packageManager.getPackageInfo(c.packageName, 0).versionName ?: "?" } catch (e: Exception) { "?" }

    private fun toast(a: Activity, t: String) = Toast.makeText(a, t, Toast.LENGTH_LONG).show()

    // เลขเวอร์ชันล่าสุดจากหน้า Release (ไม่ใช้ API จึงไม่ติดลิมิต) ตรวจไม่ได้ = null ; เรียกจากเธรดพื้นหลังเท่านั้น
    fun latestVersion(): Long? = try {
        val c = URL("https://github.com/$REPO/releases/latest").openConnection() as HttpURLConnection
        c.instanceFollowRedirects = false
        c.connectTimeout = 10000; c.readTimeout = 10000
        c.getHeaderField("Location")?.substringAfterLast("/tag/")?.removePrefix("v")?.trim()?.toLong()
    } catch (e: Exception) { null }

    // onResult(latest, url): url = null ถ้าเป็นเวอร์ชันล่าสุดแล้ว ; ตรวจไม่ได้จะไม่เรียกอะไร
    fun check(a: Activity, onResult: (Long, String?) -> Unit) {
        Thread {
            val latest = latestVersion() ?: return@Thread
            a.runOnUiThread { onResult(latest, if (latest > currentVersion(a)) APK_URL else null) }
        }.start()
    }

    fun startUpdate(a: Activity, url: String) {
        if (Build.VERSION.SDK_INT >= 26 && !a.packageManager.canRequestPackageInstalls()) {
            a.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${a.packageName}")))
            toast(a, "อนุญาตให้ติดตั้งแอปจากแหล่งนี้ แล้วกลับมาแตะแถบอัปเดตอีกครั้ง")
            return
        }
        toast(a, "กำลังดาวน์โหลด...")
        Thread {
            try {
                val f = File(a.getExternalFilesDir(null), "update.apk")
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 15000; c.readTimeout = 30000
                c.inputStream.use { i -> f.outputStream().use { o -> i.copyTo(o) } }
                a.runOnUiThread {
                    val uri = FileProvider.getUriForFile(a, "${a.packageName}.fileprovider", f)
                    a.startActivity(Intent(Intent.ACTION_VIEW)
                        .setDataAndType(uri, "application/vnd.android.package-archive")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                }
            } catch (e: Exception) {
                a.runOnUiThread { toast(a, "ดาวน์โหลดไม่สำเร็จ: ${e.message}") }
            }
        }.start()
    }
}
