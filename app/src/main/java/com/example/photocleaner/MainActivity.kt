package com.example.photocleaner

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.media.MediaScannerConnection
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
    private val protectedNames = setOf("dcim", "pictures", "download", "downloads", "documents", "movies", "music", "android")

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
        val delName = EditText(this).apply { hint = "ชื่อโฟลเดอร์ที่จะลบ เช่น Screenshots" }
        val delBtn = Button(this).apply { text = "ค้นหาแล้วลบทั้งโฟลเดอร์" }

        root.addView(label("โฟลเดอร์รูปที่สแกน (พิมพ์ path เต็ม หรือพิมพ์ชื่อแล้วกดค้นหา) ระวัง! ลบถาวร"))
        root.addView(path); root.addView(find)
        root.addView(label("ลบทุกกี่รูป (เช่น 1000)")); root.addView(n)
        root.addView(label("เลือกรูปที่จะลบ")); root.addView(grp)
        root.addView(start); root.addView(stop); root.addView(status)
        root.addView(label("──────── ลบทั้งโฟลเดอร์ (ถาวร) ────────"))
        root.addView(delName); root.addView(delBtn)
        setContentView(ScrollView(this).apply { addView(root) })

        find.setOnClickListener {
            val name = path.text.toString().trim().trimEnd('/').substringAfterLast('/')
            search(name) { path.setText(it) }
        }
        delBtn.setOnClickListener {
            val name = delName.text.toString().trim().trimEnd('/').substringAfterLast('/')
            search(name) { confirmDelete(File(it)) }
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

    // ค้นหาโฟลเดอร์จากชื่อ แล้วแสดงรายการพร้อมจำนวนรูป เลือกแล้วเรียก onPick(path)
    private fun search(name: String, onPick: (String) -> Unit) {
        if (name.isEmpty()) { Toast.makeText(this, "พิมพ์ชื่อโฟลเดอร์ก่อน", Toast.LENGTH_SHORT).show(); return }
        if (!hasAccess()) { askAccess(); return }
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
                        .setItems(items) { _, i -> onPick(found[i]) }.show()
                }
            }
        }.start()
    }

    private fun confirmDelete(dir: File) {
        if (isProtected(dir)) {
            AlertDialog.Builder(this).setTitle("ลบไม่ได้")
                .setMessage("โฟลเดอร์นี้เป็นโฟลเดอร์หลักของระบบ แอปไม่อนุญาตให้ลบ\n${dir.absolutePath}")
                .setPositiveButton("ตกลง", null).show()
            return
        }
        Thread {
            var imgs = 0; var all = 0; var bytes = 0L
            dir.walkTopDown().filter { it.isFile }.forEach {
                all++; bytes += it.length()
                if (it.extension.lowercase() in exts) imgs++
            }
            runOnUiThread {
                AlertDialog.Builder(this).setTitle("ยืนยันลบทั้งโฟลเดอร์?")
                    .setMessage("${dir.absolutePath}\n\nรูปภาพ $imgs รูป\nไฟล์ทั้งหมด $all ไฟล์ (${bytes / 1024 / 1024} MB)\n\nลบถาวร กู้คืนไม่ได้")
                    .setPositiveButton("ลบเลย") { _, _ -> doDelete(dir) }
                    .setNegativeButton("ยกเลิก", null).show()
            }
        }.start()
    }

    private fun doDelete(dir: File) {
        Toast.makeText(this, "กำลังลบ...", Toast.LENGTH_SHORT).show()
        Thread {
            val paths = dir.walkTopDown().filter { it.isFile }.map { it.absolutePath }.toList()
            dir.deleteRecursively()
            val left = if (dir.exists()) dir.walkTopDown().count { it.isFile } else 0
            if (paths.isNotEmpty()) MediaScannerConnection.scanFile(this, paths.toTypedArray(), null, null)
            runOnUiThread {
                val msg = if (!dir.exists()) "ลบโฟลเดอร์เรียบร้อย (${paths.size} ไฟล์)"
                else "ลบไม่หมด เหลือ $left ไฟล์ (ลบแล้ว ${paths.size - left})"
                AlertDialog.Builder(this).setTitle("ผลการลบ").setMessage(msg).setPositiveButton("ตกลง", null).show()
            }
        }.start()
    }

    private fun isProtected(d: File): Boolean {
        val base = Environment.getExternalStorageDirectory().absolutePath
        val parent = d.parentFile?.absolutePath ?: return true
        if (d.absolutePath == base || parent == "/storage" || parent == "/") return true
        return parent == base && d.name.lowercase() in protectedNames
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
