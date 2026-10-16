package app.fixtbntt

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    data class AppItem(val label: String, val pkg: String) {
        val key = (label + " " + pkg).lowercase()
    }

    private val rec = listOf(
        "com.facebook.katana", "com.facebook.orca", "com.zing.zalo", "com.ss.android.ugc.trill",
        "com.zhiliaoapp.musically", "com.google.android.gm", "org.telegram.messenger", "com.whatsapp",
        "com.instagram.android", "com.viber.voip", "com.mbmobile", "com.mservice.momotransfer"
    )

    private lateinit var status: TextView
    private val apps = ArrayList<AppItem>()
    private val shown = ArrayList<AppItem>()
    private val selected = HashSet<String>()
    private var query = ""
    private val adapter = AppAdapter()

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        selected.addAll(Store.sel(this))
        val lv = ListView(this)
        lv.addHeaderView(buildHeader(), null, false)
        lv.adapter = adapter
        lv.setOnItemClickListener { p, _, pos, _ ->
            (p.getItemAtPosition(pos) as? AppItem)?.let { toggle(it) }
        }
        lv.setOnItemLongClickListener { p, _, pos, _ ->
            (p.getItemAtPosition(pos) as? AppItem)?.let { actions(it) }
            true
        }
        setContentView(lv)
        loadApps()
    }

    override fun onResume() {
        super.onResume()
        try { Guard.ensureScheduled(this) } catch (_: Throwable) {}
        refresh()
    }

    // ---------- UI ----------

    private fun dp(x: Int) = (x * resources.displayMetrics.density).toInt()
    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_LONG).show()

    private fun btn(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun btnRow(vararg b: Button) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        for (x in b) addView(x, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }

    private fun buildHeader(): View {
        val l = LinearLayout(this)
        l.orientation = LinearLayout.VERTICAL
        l.setPadding(dp(16), dp(24), dp(16), dp(8))
        l.addView(TextView(this).apply {
            text = "FixTB NTT"
            textSize = 24f
            setTypeface(typeface, Typeface.BOLD)
        })
        l.addView(TextView(this).apply {
            text = "Giữ thông báo ổn định trên HyperOS nội địa. Không cần Shizuku, trợ năng hay tùy chọn nhà phát triển."
            textSize = 13f
            alpha = 0.7f
        })
        status = TextView(this).apply {
            textSize = 14f
            setPadding(0, dp(12), 0, dp(12))
        }
        l.addView(status)
        l.addView(btnRow(
            btn("Quyền ghi cài đặt") {
                open(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName")))
            },
            btn("Miễn tối ưu pin") { askBattery() }
        ))
        l.addView(btnRow(
            btn("Chọn nhóm đề xuất") { selectRecommended() },
            btn("Lưu & áp dụng") { doApply() }
        ))
        l.addView(btnRow(
            btn("Kiểm tra") { doDiag(false) },
            btn("Hướng dẫn") { showText("Hướng dẫn", GUIDE) }
        ))
        l.addView(btnRow(
            btn("Bật/tắt đo độ trễ") { toggleLatency() },
            btn("Xem độ trễ") { showText("Nhật ký độ trễ", Stats.latency(this)) }
        ))
        l.addView(btnRow(
            btn("Xuất chẩn đoán") { doDiag(true) },
            btn("Hoàn tác") { confirmUndo() }
        ))
        l.addView(TextView(this).apply {
            text = "Chạm để chọn app, nhấn giữ để mở các trang cài đặt riêng của app."
            textSize = 12f
            alpha = 0.7f
            setPadding(0, dp(12), 0, dp(4))
        })
        l.addView(EditText(this).apply {
            hint = "Tìm ứng dụng…"
            isSingleLine = true
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    query = s?.toString().orEmpty()
                    applyFilter()
                }
            })
        })
        return l
    }

    private fun open(i: Intent) {
        try { startActivity(i) } catch (_: Throwable) { toast("Không mở được trang cài đặt này") }
    }

    private fun latencyState(): String {
        val cn = ComponentName(this, LatencyListener::class.java)
        if (packageManager.getComponentEnabledSetting(cn) != PackageManager.COMPONENT_ENABLED_STATE_ENABLED) return "tắt"
        val listed = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
            ?.contains(packageName) == true
        return if (listed) "bật ✓" else "chờ bạn cấp quyền"
    }

    private fun refresh() {
        val cur = Millet.read(this)
        val t = Millet.tokens(cur)
        val keyTxt = when {
            Millet.GMS in t -> "có GMS ✓ (${t.size} mục)"
            cur == null -> "chưa có khóa ✗"
            else -> "thiếu GMS ✗"
        }
        val pw = getSystemService(PowerManager::class.java)
        val bat = try { pw.isIgnoringBatteryOptimizations(packageName) } catch (_: Throwable) { false }
        val last = Logs.read(this, "guard.log").lastOrNull { it.contains("|FIX|") }
            ?.split('|')?.get(0)?.toLongOrNull()
        val lastTxt = if (last == null) "chưa ghi nhận"
        else SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(Date(last))
        status.text = "Khóa Millet: $keyTxt\n" +
            "Quyền ghi cài đặt: ${if (Settings.System.canWrite(this)) "đã cấp ✓" else "chưa cấp ✗"}\n" +
            "Miễn tối ưu pin (app này): ${if (bat) "đã bật ✓" else "chưa ✗"}\n" +
            "Đo độ trễ: ${latencyState()}\n" +
            "Lần gần nhất guard phải sửa khóa: $lastTxt"
    }

    private fun askBattery() {
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (_: Throwable) {
            open(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun toggleLatency() {
        val cn = ComponentName(this, LatencyListener::class.java)
        if (latencyState() == "tắt") {
            AlertDialog.Builder(this).setTitle("Bật đo độ trễ")
                .setMessage(
                    "Tính năng này cần quyền truy cập thông báo (không phải trợ năng, không cần tùy chọn nhà phát triển). " +
                        "App chỉ ghi tên gói và độ trễ, không đọc nội dung.\n\n" +
                        "Một số app ngân hàng hoặc VNeID có thể cảnh báo khi có app đọc thông báo. Nếu bị chặn đăng nhập, bấm lại nút này để tắt."
                )
                .setPositiveButton("Tiếp tục") { _, _ ->
                    packageManager.setComponentEnabledSetting(cn, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
                    open(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                }
                .setNegativeButton("Hủy", null).show()
        } else {
            packageManager.setComponentEnabledSetting(cn, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
            toast("Đã tắt đo độ trễ. App không còn trong danh sách truy cập thông báo.")
            refresh()
        }
    }

    // ---------- Danh sách app ----------

    private fun loadApps() {
        Thread {
            val pm = packageManager
            val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val list = pm.queryIntentActivities(i, 0)
                .map { AppItem(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
                .filter { it.pkg != packageName }
                .distinctBy { it.pkg }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                apps.clear(); apps.addAll(list)
                applyFilter()
            }
        }.start()
    }

    private fun applyFilter() {
        val q = query.trim().lowercase()
        shown.clear()
        shown.addAll(
            apps.filter { q.isEmpty() || it.key.contains(q) }
                .sortedWith(
                    compareByDescending<AppItem> { it.pkg in selected }
                        .thenByDescending { it.pkg in rec }
                        .thenBy { it.label.lowercase() }
                )
        )
        adapter.notifyDataSetChanged()
    }

    private fun toggle(a: AppItem) {
        if (!selected.remove(a.pkg)) selected.add(a.pkg)
        Store.setSel(this, selected)
        adapter.notifyDataSetChanged()
    }

    private fun selectRecommended() {
        val have = apps.map { it.pkg }.toSet()
        val add = rec.filter { it in have }
        selected.addAll(add)
        Store.setSel(this, selected)
        applyFilter()
        toast("Đã chọn ${add.size} app đề xuất đang cài. Bấm Lưu & áp dụng.")
    }

    private fun labelOf(pkg: String) =
        try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() } catch (_: Throwable) { pkg }

    private fun actions(a: AppItem) {
        val items = arrayOf(
            "Thông tin ứng dụng", "Cài đặt thông báo", "Tiết kiệm pin (Xiaomi)",
            "Tự khởi động (Xiaomi)", "Quyền khác (Xiaomi)", "Danh sách miễn tối ưu pin"
        )
        AlertDialog.Builder(this).setTitle(a.label).setItems(items) { _, w ->
            val details = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${a.pkg}"))
            val i = when (w) {
                0 -> details
                1 -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, a.pkg)
                2 -> Intent().setClassName("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity")
                    .putExtra("package_name", a.pkg).putExtra("package_label", a.label)
                3 -> Intent().setClassName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")
                4 -> Intent("miui.intent.action.APP_PERM_EDITOR")
                    .setClassName("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity")
                    .putExtra("extra_pkgname", a.pkg)
                else -> Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            }
            try {
                startActivity(i)
            } catch (_: Throwable) {
                if (w in 2..4) {
                    toast("Không mở được trang riêng của Xiaomi, mở thông tin ứng dụng thay thế.")
                    open(details)
                } else toast("Không mở được trang cài đặt này")
            }
        }.show()
    }

    inner class AppAdapter : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(i: Int): Any = shown[i]
        override fun getItemId(i: Int) = i.toLong()

        override fun getView(i: Int, cv: View?, parent: ViewGroup): View {
            val row = (cv as? LinearLayout) ?: makeRow()
            val a = shown[i]
            (row.getChildAt(0) as CheckBox).isChecked = a.pkg in selected
            val col = row.getChildAt(1) as LinearLayout
            (col.getChildAt(0) as TextView).text = a.label
            (col.getChildAt(1) as TextView).text = a.pkg
            return row
        }

        private fun makeRow(): LinearLayout {
            val r = LinearLayout(this@MainActivity)
            r.orientation = LinearLayout.HORIZONTAL
            r.gravity = Gravity.CENTER_VERTICAL
            r.setPadding(dp(16), dp(8), dp(16), dp(8))
            r.addView(CheckBox(this@MainActivity).apply { isClickable = false; isFocusable = false })
            val col = LinearLayout(this@MainActivity)
            col.orientation = LinearLayout.VERTICAL
            col.addView(TextView(this@MainActivity).apply { textSize = 16f })
            col.addView(TextView(this@MainActivity).apply { textSize = 11f; alpha = 0.6f })
            r.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            return r
        }
    }

    // ---------- Hành động ----------

    private fun doApply() {
        val sel = HashSet(selected)
        val removed = Store.applied(this) - sel
        Store.setApplied(this, sel)
        Millet.remove(this, removed)
        val r = Millet.ensure(this)
        toast(
            if (r == Fix.NO_PERM) "Cần cấp quyền ghi cài đặt trước (nút Quyền ghi cài đặt)."
            else "Đã lưu ${sel.size} app vào whitelist Millet. Guard sẽ giữ danh sách này."
        )
        refresh()
    }

    private fun confirmUndo() {
        AlertDialog.Builder(this).setTitle("Hoàn tác")
            .setMessage("Gỡ các app đã lưu khỏi whitelist Millet và bỏ chọn tất cả? Các mục có sẵn của hệ thống hoặc người bán được giữ nguyên.")
            .setPositiveButton("Hoàn tác") { _, _ ->
                Millet.remove(this, Store.applied(this))
                Store.setApplied(this, emptySet())
                Store.setSel(this, emptySet())
                selected.clear()
                applyFilter()
                refresh()
                toast("Đã hoàn tác")
            }
            .setNegativeButton("Hủy", null).show()
    }

    private fun doDiag(export: Boolean) {
        val toks = Millet.tokens(Millet.read(this)).toSet()
        val pw = getSystemService(PowerManager::class.java)
        val sb = StringBuilder()
        for (pkg in selected + Millet.GMS) {
            val info = try { packageManager.getApplicationInfo(pkg, 0) } catch (_: Throwable) { null }
            if (info == null) { sb.append("• $pkg: không cài\n"); continue }
            val stopped = (info.flags and ApplicationInfo.FLAG_STOPPED) != 0
            val inKey = pkg in toks
            val doze = try { pw.isIgnoringBatteryOptimizations(pkg) } catch (_: Throwable) { false }
            sb.append(if (stopped || !inKey) "⚠ " else "✓ ").append(labelOf(pkg)).append(" (").append(pkg).append(")\n")
            sb.append("   Millet: ").append(if (inKey) "✓" else "✗")
                .append("  Miễn tối ưu pin: ").append(if (doze) "✓" else "✗")
                .append("  Buộc dừng: ").append(if (stopped) "CÓ ⚠ (không nhận thông báo)" else "không").append("\n")
        }
        val d = sb.toString().ifEmpty { "(chưa chọn app nào)" }
        val txt = buildReport(d)
        if (export) copyAndShow(txt) else showText("Kiểm tra", txt)
    }

    private fun buildReport(diag: String): String {
        val gmsVer = try { packageManager.getPackageInfo(Millet.GMS, 0).versionName } catch (_: Throwable) { "không có" }
        val cur = Millet.read(this)
        val guard = Logs.read(this, "guard.log").takeLast(10).joinToString("\n") { l ->
            val f = l.split('|')
            val t = f.getOrNull(0)?.toLongOrNull()
            (if (t != null) SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(Date(t)) else "?") +
                " " + f.drop(1).joinToString(" ")
        }.ifEmpty { "(trống)" }
        return "FixTB NTT 1.1\n" +
            "Máy: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})\n" +
            "Bản hệ thống: ${Build.DISPLAY}\n" +
            "GMS: $gmsVer\n" +
            "Quyền ghi cài đặt: ${Settings.System.canWrite(this)}\n\n" +
            "Khóa Millet:\n${cur ?: "(chưa có)"}\n\n" +
            "Trạng thái từng app:\n$diag\n\n" +
            "Nhật ký guard (lần sửa gần nhất):\n$guard\n\n" +
            Stats.latency(this)
    }

    private fun copyAndShow(t: String) {
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("FixTB NTT", t))
        toast("Đã sao chép báo cáo")
        showText("Báo cáo chẩn đoán", t)
    }

    private fun showText(title: String, text: String) {
        val tv = TextView(this).apply {
            this.text = text
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(dp(20), dp(12), dp(20), dp(12))
        }
        AlertDialog.Builder(this).setTitle(title)
            .setView(ScrollView(this).apply { addView(tv) })
            .setPositiveButton("Đóng", null)
            .setNeutralButton("Sao chép") { _, _ ->
                (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("FixTB NTT", text))
            }.show()
    }

    companion object {
        const val GUIDE = "Làm một lần:\n" +
            "1. Bấm Quyền ghi cài đặt và cấp. Từ lúc này guard GMS chạy.\n" +
            "2. Bấm Miễn tối ưu pin để app này không bị hệ thống đóng băng.\n" +
            "3. Chọn app cần giữ thông báo, bấm Lưu & áp dụng.\n\n" +
            "Với mỗi app bị miss thông báo (nhấn giữ app trong danh sách):\n" +
            "• Tự khởi động: bật.\n" +
            "• Tiết kiệm pin: Không hạn chế.\n" +
            "• Cài đặt thông báo: bật mọi kênh cần thiết, cho phép màn hình khóa.\n" +
            "• Khóa app trong màn hình đa nhiệm, đừng vuốt tắt (vuốt tắt có thể khiến app rơi vào trạng thái buộc dừng và không nhận push).\n\n" +
            "Làm tương tự cho Google Play services.\n\n" +
            "Kiểm tra kết nối push: mở trình quay số, nhập *#*#426#*#* và xem mục Firebase Cloud Messaging.\n\n" +
            "Lưu ý: bản này không dùng Shizuku nên không chỉnh được Doze, standby bucket hay AppOps. Cài đặt riêng của từng app vẫn do bạn bật tay ở các trang trên."
    }
}
