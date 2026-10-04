package com.example.photocleaner

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.text.InputType
import android.widget.*

class MainActivity : Activity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val p = getSharedPreferences("cfg", MODE_PRIVATE)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 40, 40, 40) }
        fun label(t: String) = TextView(this).apply { text = t; setPadding(0, 24, 0, 4) }

        val path = EditText(this).apply { setText(p.getString("path", "/storage/emulated/0/DCIM/Camera")) }
        val n = EditText(this).apply { inputType = InputType.TYPE_CLASS_NUMBER; setText(p.getInt("n", 1000).toString()) }
        val rNew = RadioButton(this).apply { text = "ลบรูปใหม่ล่าสุด"; id = 1 }
        val rOld = RadioButton(this).apply { text = "ลบรูปเก่าสุด"; id = 2 }
        val grp = RadioGroup(this).apply { addView(rNew); addView(rOld) }
        grp.check(if (p.getBoolean("newest", true)) 1 else 2)
        val status = TextView(this).apply { text = if (p.getBoolean("enabled", false)) "สถานะ: ทำงานอยู่" else "สถานะ: หยุด" }
        val start = Button(this).apply { text = "เริ่มทำงาน (พื้นหลัง)" }
        val stop = Button(this).apply { text = "หยุด" }

        root.addView(label("โฟลเดอร์รูปที่สแกน (ระวัง! ลบถาวร)")); root.addView(path)
        root.addView(label("ลบทุกกี่รูป (เช่น 1000)")); root.addView(n)
        root.addView(label("เลือกรูปที่จะลบ")); root.addView(grp)
        root.addView(start); root.addView(stop); root.addView(status)
        setContentView(ScrollView(this).apply { addView(root) })

        start.setOnClickListener {
            val v = n.text.toString().toIntOrNull() ?: 0
            if (v < 1) { Toast.makeText(this, "จำนวนไม่ถูกต้อง", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            if (!hasAccess()) { askAccess(); return@setOnClickListener }
            p.edit().putString("path", path.text.toString().trim()).putInt("n", v)
                .putBoolean("newest", grp.checkedRadioButtonId == 1).putBoolean("enabled", true).apply()
            val i = Intent(this, CleanerService::class.java)
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
            status.text = "สถานะ: ทำงานอยู่"
        }
        stop.setOnClickListener {
            p.edit().putBoolean("enabled", false).apply()
            stopService(Intent(this, CleanerService::class.java))
            status.text = "สถานะ: หยุด"
        }
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
    }

    private fun hasAccess() =
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == 0

    private fun askAccess() {
        if (Build.VERSION.SDK_INT >= 30)
            startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
        else requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 1)
        Toast.makeText(this, "อนุญาตสิทธิ์แล้วกดเริ่มอีกครั้ง", Toast.LENGTH_LONG).show()
    }
}
