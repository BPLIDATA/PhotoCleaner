package com.example.photocleaner

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object Updater {
    private const val REPO = "BPLIDATA/PhotoCleaner"

    fun currentVersion(a: Activity): Long {
        val i = a.packageManager.getPackageInfo(a.packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) i.longVersionCode else i.versionCode.toLong()
    }

    private fun toast(a: Activity, t: String) = Toast.makeText(a, t, Toast.LENGTH_LONG).show()

    fun check(a: Activity, silent: Boolean) {
        Thread {
            try {
                val c = URL("https://api.github.com/repos/$REPO/releases/latest").openConnection() as HttpURLConnection
                c.connectTimeout = 10000; c.readTimeout = 10000
                val j = JSONObject(c.inputStream.bufferedReader().readText())
                val latest = j.getString("tag_name").removePrefix("v").toLong()
                val url = j.getJSONArray("assets").getJSONObject(0).getString("browser_download_url")
                a.runOnUiThread {
                    if (latest > currentVersion(a)) {
                        AlertDialog.Builder(a).setTitle("มีเวอร์ชันใหม่ (v$latest)")
                            .setMessage("ต้องการอัปเดตตอนนี้ไหม?")
                            .setPositiveButton("อัปเดต") { _, _ -> download(a, url) }
                            .setNegativeButton("ไว้ทีหลัง", null).show()
                    } else if (!silent) toast(a, "เป็นเวอร์ชันล่าสุดแล้ว")
                }
            } catch (e: Exception) {
                if (!silent) a.runOnUiThread { toast(a, "ตรวจอัปเดตไม่สำเร็จ: ${e.message}") }
            }
        }.start()
    }

    private fun download(a: Activity, url: String) {
        if (Build.VERSION.SDK_INT >= 26 && !a.packageManager.canRequestPackageInstalls()) {
            a.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${a.packageName}")))
            toast(a, "อนุญาตให้ติดตั้งแอปจากแหล่งนี้ แล้วเปิดแอปใหม่เพื่ออัปเดตอีกครั้ง")
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
