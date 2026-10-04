package com.example.photocleaner

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.text.InputType
import android.widget.*
import java.io.File

class MainActivity : Activity() {
    private val exts = setOf("jpg", "jpeg", "png", "bmp", "webp")

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val p = getSharedPreferences("cfg", MODE_PRIVATE)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 40, 40, 40) }
        fun label(t: String) = TextView(this).apply { text = t; setPadding(0, 24, 0, 4) }

        val path = EditText(this).apply { setText(p.getString("path", "/storage/emulated/0/DCIM/Camera")) }
        val find = Button(this).apply { text = "ค้นหาโฟลเดอร์จากชื่อ (เช่น Camera)" }
        val n = EditText(this).apply { inputType = InputType.TYPE_CLASS_NUMBER; setText(p.getInt("n", 1000).toString()) }
        val rNew = RadioButton(this).apply { text = "ลบรูปใหม่ล่าสุด"; id = 1 }
        val rOld = RadioButton(this).apply { text = "ลบรูปเก่าสุด"; id = 2 }
        val grp = RadioGroup(this).apply { addView(rNew); addView(rOld) }
        grp.check(if (p.getBoolean("newest", true)) 1 else 2)
        val status = TextView(this).apply { text = if (p.getBoolean("enabled", false)) "สถานะ: ทำงานอยู่" else "สถานะ: หยุด" }
        val start = Button(this).apply { text = "เริ่มทำงาน (พื้นหลัง)" }
        val stop = Button(this).apply { text = "หยุด" }

        root.addView(label("โฟลเดอร์รูปที่สแกน (พิมพ์ path เต็ม หรือพิมพ์ชื่อแล้วกดค้นหา) ระวัง! ลบถาวร"))
        root.addView(path); root.addView(find)
        root.addView(label("ลบทุกกี่รูป (เช่น 1000)")); root.addView(n)
        root.addView(label("เลือกรูปที่จะลบ")); root.addView(grp)
        root.addView(start); root.addView(stop); root.addView(status)
        setContentView(ScrollView(this).apply { addView(root) })

        find.setOnClickListener {
            val name = path.text.toString().trim().trimEnd('/').substringAfterLast('/')
            if (name.isEmpty()) { Toast.makeText(this, "พิมพ์ชื่อโฟลเดอร์ก่อน", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            if (!hasAccess()) { askAccess(); return@setOnClickListener }
            Toast.makeText(this, "กำลังค้นหา...", Toast.LENGTH_SHORT).show()
            Thread {
                val found = ArrayList<String>()
                val roots = ArrayList<File>()
                roots.add(Environment.getExternalStorageDirectory())
                File("/storage").listFiles()?.filter { it.isDirectory && it.name != "emulated" && it.name != "self" }?.let { roots.addAll(it) }
                for (r in roots) findDirs(r, name, 0, found)
                runOnUiThread {
                    if (found.isEmpty()) {
                        Toast.makeText(this, "ไม่พบโฟลเดอร์ชื่อ $name", Toast.LENGTH_LONG).show()
                    } else {
                        val items = found.map { "$it  (${countImages(File(it))} รูป)" }.toTypedArray()
                        AlertDialog.Builder(this).setTitle("เลือกโฟลเดอร์")
                            .setItems(items) { _, i -> path.setText(found[i]) }.show()
                    }
                }
            }.start()
        }

        start.setOnClickListener {
            val v = n.text.toString().toIntOrNull() ?: 0
            if (v < 1) { Toast.makeText(this, "จำนวนไม่ถูกต้อง", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            if (!hasAccess()) { askAccess(); return@setOnClickListener }
            if (!File(path.text.toString().trim()).isDirectory) {
                Toast.makeText(this, "ไม่พบโฟลเดอร์นี้ ลองกดค้นหาโฟลเดอร์", Toast.LENGTH_LONG).show(); return@setOnClickListener
            }
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

    private fun findDirs(dir: File, name: String, depth: Int, out: MutableList<String>) {
        if (depth > 5 || out.size >= 20) return
        val subs = dir.listFiles { f -> f.isDirectory && !f.name.startsWith(".") } ?: return
        for (d in subs) {
            if (d.name.equals(name, true)) out.add(d.absolutePath)
            if (d.name != "Android") findDirs(d, name, depth + 1, out)
        }
    }

    private fun countImages(d: File) = d.listFiles { f -> f.isFile && f.extension.lowercase() in exts }?.size ?: 0

    private fun hasAccess() =
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == 0

    private fun askAccess() {
        if (Build.VERSION.SDK_INT >= 30)
            startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
        else requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 1)
        Toast.makeText(this, "อนุญาตสิทธิ์แล้วกดอีกครั้ง", Toast.LENGTH_LONG).show()
    }
}
