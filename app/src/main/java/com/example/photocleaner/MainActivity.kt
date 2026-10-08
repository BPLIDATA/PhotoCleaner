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
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import java.io.File

class MainActivity : Activity() {
    private lateinit var banner: TextView
    private lateinit var statsTv: TextView
    private val h = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() { refreshStats(); h.postDelayed(this, 2000) }
    }
    private val autoNames = setOf("success", "fail")
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
        val bubbleCb = CheckBox(this).apply { text = "แสดงไอคอนลอยเมื่อย่อแอป (แตะเพื่อกลับเข้าแอป)"; isChecked = p.getBoolean("bubble", false) }
        bubbleCb.setOnCheckedChangeListener { _, on ->
            p.edit().putBoolean("bubble", on).apply()
            if (on && Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "เปิดสวิตช์ \"แสดงทับแอปอื่น\" ให้ Photo Cleaner แล้วกลับมา", Toast.LENGTH_LONG).show()
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }
        }
        val delName = EditText(this).apply { hint = "ชื่อโฟลเดอร์ที่จะลบ เช่น Screenshots" }
        val delBtn = Button(this).apply { text = "ค้นหาแล้วลบทั้งโฟลเดอร์" }
        val autoBtn = Button(this).apply { text = "ลบโฟลเดอร์ success + fail ทั้งหมด" }

        root.addView(label("โฟลเดอร์รูปที่สแกน (พิมพ์ path เต็ม หรือพิมพ์ชื่อแล้วกดค้นหา) ระวัง! ลบถาวร"))
        root.addView(path); root.addView(find)
        root.addView(label("ลบทุกกี่รูป (เช่น 1000)")); root.addView(n)
        root.addView(label("เลือกรูปที่จะลบ")); root.addView(grp)
        root.addView(start); root.addView(stop); root.addView(bubbleCb); root.addView(status)
        root.addView(label("──────── ลบทั้งโฟลเดอร์ (ถาวร) ────────"))
        root.addView(delName); root.addView(delBtn); root.addView(autoBtn)

        val ver = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (e: Exception) { "?" }
        val verTv = TextView(this).apply {
            text = "เวอร์ชัน $ver  |  พัฒนาโดย ธนพงษ์ คิดประเสริฐ"
            textSize = 12f
            gravity = Gravity.END
        }
        statsTv = TextView(this).apply {
            textSize = 13f
            gravity = Gravity.START
            setTextColor(0xFF2E7D32.toInt())
            setOnLongClickListener {
                AlertDialog.Builder(this@MainActivity).setMessage("รีเซ็ตตัวเลข ลบแล้ว/ล้มเหลว เป็น 0 ?")
                    .setPositiveButton("รีเซ็ต") { _, _ ->
                        getSharedPreferences("cfg", MODE_PRIVATE).edit().putLong("totDel", 0).putLong("totFail", 0).apply()
                        refreshStats()
                    }.setNegativeButton("ยกเลิก", null).show()
                true
            }
        }
        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 14, 24, 14)
            setBackgroundColor(0xFFEEEEEE.toInt())
            addView(verTv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(statsTv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        banner = TextView(this).apply {
            visibility = View.GONE
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(24, 26, 24, 26)
            setBackgroundColor(0xFFE65100.toInt())
            setTextColor(0xFFFFFFFF.toInt())
        }
        val scroll = ScrollView(this).apply { addView(root) }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(banner, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(footer, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        })

        find.setOnClickListener {
            val name = path.text.toString().trim().trimEnd('/').substringAfterLast('/')
            search(name, find) { path.setText(it) }
        }
        delBtn.setOnClickListener {
            val name = delName.text.toString().trim().trimEnd('/').substringAfterLast('/')
            search(name, delBtn) { confirmDelete(File(it)) }
        }
        autoBtn.setOnClickListener { deleteAutoFolders(autoBtn) }

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
            askBattery()
        }
        stop.setOnClickListener {
            p.edit().putBoolean("enabled", false).apply()
            stopService(Intent(this, CleanerService::class.java))
            status.text = "สถานะ: หยุด"
        }
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
    }

    private fun refreshStats() {
        val p = getSharedPreferences("cfg", MODE_PRIVATE)
        statsTv.text = String.format(java.util.Locale.US, "ลบแล้ว %,d รูป | ล้มเหลว %,d", p.getLong("totDel", 0), p.getLong("totFail", 0))
    }

    override fun onResume() {
        super.onResume()
        CleanerService.appVisible = true
        CleanerService.instance?.showBubble(false)
        h.removeCallbacks(tick); h.post(tick)
        if (TrashService.hasPending(this)) TrashService.start(this)   // ลบต่อจากครั้งก่อนที่ค้างอยู่
        Updater.check(this) { latest, url ->
            if (url == null) {
                banner.visibility = View.GONE
            } else {
                val doUpdate = {
                    banner.text = "กำลังดาวน์โหลด 1.$latest ..."
                    Updater.startUpdate(this, url)
                }
                banner.text = "⬆ มีเวอร์ชันใหม่ 1.$latest — แตะที่นี่เพื่ออัปเดต"
                banner.visibility = View.VISIBLE
                banner.setOnClickListener { doUpdate() }
                val p = getSharedPreferences("cfg", MODE_PRIVATE)
                if (p.getLong("askedVer", 0) < latest) {
                    p.edit().putLong("askedVer", latest).apply()
                    AlertDialog.Builder(this).setTitle("มีเวอร์ชันใหม่ (1.$latest)")
                        .setMessage("ต้องการอัปเดตตอนนี้ไหม?\n(ถ้าไว้ทีหลัง จะมีแถบสีส้มด้านล่างให้กดอัปเดตได้)")
                        .setPositiveButton("อัปเดต") { _, _ -> doUpdate() }
                        .setNegativeButton("ไว้ทีหลัง", null).show()
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        CleanerService.appVisible = false
        CleanerService.instance?.showBubble(true)
        h.removeCallbacks(tick)
    }

    // ขอยกเว้นการประหยัดแบตเตอรี่ เพื่อไม่ให้ระบบปิดงานลบรูปในพื้นหลัง
    private fun askBattery() {
        if (Build.VERSION.SDK_INT >= 23) {
            val pm = getSystemService(PowerManager::class.java)
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
                } catch (_: Exception) {}
            }
        }
    }

    private fun search(name: String, btn: Button, onPick: (String) -> Unit) {
        if (name.isEmpty()) { Toast.makeText(this, "พิมพ์ชื่อโฟลเดอร์ก่อน", Toast.LENGTH_SHORT).show(); return }
        if (!hasAccess()) { askAccess(); return }
        val oldText = btn.text
        btn.text = "⏳ กำลังค้นหา..."
        btn.isEnabled = false
        Thread {
            val found = ArrayList<String>()
            var err: String? = null
            try {
                val deadline = System.currentTimeMillis() + 10_000
                val base = Environment.getExternalStorageDirectory()
                for (sub in listOf("", "DCIM", "Pictures", "Download", "Documents")) {
                    val d = if (sub.isEmpty()) File(base, name) else File(File(base, sub), name)
                    if (d.isDirectory && d.absolutePath !in found) found.add(d.absolutePath)
                }
                val roots = ArrayList<File>()
                roots.add(base)
                File("/storage").list()?.filter { it != "emulated" && it != "self" }?.forEach { roots.add(File("/storage/$it")) }
                for (r in roots) findDirs(r, name, 0, found, deadline)
            } catch (e: Exception) { err = e.message ?: e.javaClass.simpleName }
            runOnUiThread {
                btn.text = oldText
                btn.isEnabled = true
                if (err != null) {
                    AlertDialog.Builder(this).setTitle("ค้นหาไม่สำเร็จ").setMessage(err).setPositiveButton("ตกลง", null).show()
                } else if (found.isEmpty()) {
                    AlertDialog.Builder(this).setTitle("ไม่พบโฟลเดอร์")
                        .setMessage("ไม่พบโฟลเดอร์ชื่อ \"$name\"").setPositiveButton("ตกลง", null).show()
                } else {
                    // แสดงรายการทันที แล้วค่อยๆ เติมจำนวนรูปทีละโฟลเดอร์ (โฟลเดอร์ใหญ่ไม่ต้องรอ)
                    val labels = ArrayList(found.map { "$it  (กำลังนับรูป...)" })
                    val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)
                    AlertDialog.Builder(this).setTitle("เลือกโฟลเดอร์")
                        .setAdapter(adapter) { _, i -> onPick(found[i]) }.show()
                    Thread {
                        for (k in found.indices) {
                            val c = countImages(File(found[k]))
                            runOnUiThread { labels[k] = "${found[k]}  ($c รูป)"; adapter.notifyDataSetChanged() }
                        }
                    }.start()
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
        val wait = AlertDialog.Builder(this).setMessage("กำลังนับไฟล์ในโฟลเดอร์ ... (ถ้ามีไฟล์เยอะอาจใช้เวลาสักครู่)").create()
        wait.show()
        Thread {
            val (imgs, all) = countAll(dir)
            runOnUiThread {
                try { wait.dismiss() } catch (_: Exception) {}
                AlertDialog.Builder(this).setTitle("ยืนยันลบทั้งโฟลเดอร์?")
                    .setMessage("${dir.absolutePath}\n\nรูปภาพ $imgs รูป\nไฟล์ทั้งหมด $all ไฟล์\n\nลบถาวร กู้คืนไม่ได้")
                    .setPositiveButton("ลบเลย") { _, _ -> runPurge(listOf(dir.absolutePath)) }
                    .setNegativeButton("ยกเลิก", null).show()
            }
        }.start()
    }

    // ค้นหาโฟลเดอร์ชื่อ success และ fail ทั้งหมด แล้วลบทิ้ง (แสดงรายการให้ยืนยันก่อน)
    private fun deleteAutoFolders(btn: Button) {
        if (!hasAccess()) { askAccess(); return }
        val oldText = btn.text
        btn.text = "⏳ กำลังค้นหา..."
        btn.isEnabled = false
        Thread {
            val found = ArrayList<String>()
            var err: String? = null
            var infos: List<String> = emptyList()
            try {
                val deadline = System.currentTimeMillis() + 15_000
                val roots = ArrayList<File>()
                roots.add(Environment.getExternalStorageDirectory())
                File("/storage").list()?.filter { it != "emulated" && it != "self" }?.forEach { roots.add(File("/storage/$it")) }
                for (r in roots) findAuto(r, 0, found, deadline)
                infos = found.map { val (i, a) = countAll(File(it)); "$it\n   รูป $i | ไฟล์ทั้งหมด $a" }
            } catch (e: Exception) { err = e.message ?: e.javaClass.simpleName }
            runOnUiThread {
                btn.text = oldText
                btn.isEnabled = true
                if (err != null) {
                    AlertDialog.Builder(this).setTitle("ค้นหาไม่สำเร็จ").setMessage(err).setPositiveButton("ตกลง", null).show()
                } else if (found.isEmpty()) {
                    AlertDialog.Builder(this).setTitle("ไม่พบโฟลเดอร์")
                        .setMessage("ไม่พบโฟลเดอร์ชื่อ success หรือ fail").setPositiveButton("ตกลง", null).show()
                } else {
                    AlertDialog.Builder(this).setTitle("พบ ${found.size} โฟลเดอร์ ลบทั้งหมดเลยไหม?")
                        .setMessage(infos.joinToString("\n\n") + "\n\nลบถาวร กู้คืนไม่ได้")
                        .setPositiveButton("ลบทั้งหมด") { _, _ -> runPurge(found) }
                        .setNegativeButton("ยกเลิก", null).show()
                }
            }
        }.start()
    }

    private fun findAuto(dir: File, depth: Int, out: MutableList<String>, deadline: Long) {
        if (depth > 4 || out.size >= 50 || System.currentTimeMillis() > deadline) return
        val names = dir.list() ?: return
        for (nm in names) {
            if (nm.contains('.') || nm == "Android") continue
            val d = File(dir, nm)
            if (!d.isDirectory) continue
            if (nm.lowercase() in autoNames) {
                if (!isProtected(d) && d.absolutePath !in out) out.add(d.absolutePath)
                continue
            }
            findAuto(d, depth + 1, out, deadline)
        }
    }

    // ลบโฟลเดอร์ตามรายการ (ไม่สั่งสแกนแกลเลอรี เพื่อความเร็ว) แล้วบอกจำนวนและเวลาที่ใช้
    private fun runPurge(paths: List<String>) {
        val wait = AlertDialog.Builder(this).setMessage("กำลังลบ ... อย่าปิดแอป (ไฟล์เยอะอาจใช้เวลาสักครู่)").setCancelable(false).create()
        wait.show()
        Thread {
            val t0 = System.currentTimeMillis()
            val st = LongArray(4)   // [0]=ไฟล์ที่ลบ [1]=รูปที่ลบ [2]=ลบไม่ได้ [3]=โฟลเดอร์ที่ลบไม่หมด
            for (path in paths) purge(File(path), st)
            val p = getSharedPreferences("cfg", MODE_PRIVATE)
            p.edit().putLong("totDel", p.getLong("totDel", 0) + st[1])
                .putLong("totFail", p.getLong("totFail", 0) + st[2]).apply()
            val secs = String.format(java.util.Locale.US, "%.1f", (System.currentTimeMillis() - t0) / 1000.0)
            runOnUiThread {
                try { wait.dismiss() } catch (_: Exception) {}
                val msg = "ลบแล้ว ${st[0]} ไฟล์ (เป็นรูป ${st[1]})\nลบไม่ได้ ${st[2]} ไฟล์\nใช้เวลา $secs วินาที" +
                    (if (st[3] > 0) "\nมี ${st[3]} โฟลเดอร์ที่ลบไม่หมด" else "")
                AlertDialog.Builder(this).setTitle("ผลการลบ").setMessage(msg).setPositiveButton("ตกลง", null).show()
            }
        }.start()
    }

    private fun purge(dir: File, st: LongArray) {
        val names = dir.list() ?: return
        for (nm in names) {
            val f = File(dir, nm)
            if (!nm.contains('.') && f.isDirectory) { purge(f, st); continue }
            if (f.delete()) {
                st[0]++
                if (nm.substringAfterLast('.', "").lowercase() in exts) st[1]++
            } else if (f.isDirectory) purge(f, st) else st[2]++
        }
        if (!dir.delete()) st[3]++
    }

    // นับรูป/ไฟล์ทั้งหมดจากรายชื่อไฟล์อย่างเดียว (ไม่อ่านรายละเอียดทีละไฟล์ จึงเร็ว)
    private fun countAll(d: File): Pair<Int, Int> {
        var imgs = 0; var all = 0
        val names = d.list() ?: return Pair(0, 0)
        for (nm in names) {
            if (nm.contains('.')) {
                all++
                if (nm.substringAfterLast('.', "").lowercase() in exts) imgs++
            } else {
                val f = File(d, nm)
                if (f.isDirectory) { val (i, a) = countAll(f); imgs += i; all += a } else all++
            }
        }
        return Pair(imgs, all)
    }

    private fun isProtected(d: File): Boolean {
        val base = Environment.getExternalStorageDirectory().absolutePath
        val parent = d.parentFile?.absolutePath ?: return true
        if (d.absolutePath == base || parent == "/storage" || parent == "/") return true
        return parent == base && d.name.lowercase() in protectedNames
    }

    private fun findDirs(dir: File, name: String, depth: Int, out: MutableList<String>, deadline: Long) {
        if (depth > 3 || out.size >= 20 || System.currentTimeMillis() > deadline) return
        val names = dir.list() ?: return
        for (nm in names) {
            if (nm.contains('.') || nm == "Android") continue   // ข้ามไฟล์/โฟลเดอร์ซ่อน เพื่อความเร็ว
            val d = File(dir, nm)
            if (!d.isDirectory) continue
            if (nm.equals(name, true) && d.absolutePath !in out) out.add(d.absolutePath)
            findDirs(d, name, depth + 1, out, deadline)
        }
    }

    private fun countImages(d: File) = d.list()?.count { it.substringAfterLast('.', "").lowercase() in exts } ?: 0

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
