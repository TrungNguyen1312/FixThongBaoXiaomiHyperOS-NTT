package app.fixtbntt

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import java.io.File

enum class Fix { OK, FIXED, FIXED_GMS, NO_PERM }

/** sel = app đang chọn trong UI; applied = app đã lưu (guard giữ whitelist cho nhóm này). */
object Store {
    private fun sp(c: Context) = c.getSharedPreferences("p", Context.MODE_PRIVATE)
    private fun get(c: Context, k: String): Set<String> =
        sp(c).getString(k, "")!!.split(',').filter { it.isNotBlank() }.toSet()

    private fun put(c: Context, k: String, v: Collection<String>) {
        sp(c).edit().putString(k, v.joinToString(",")).apply()
    }

    fun sel(c: Context) = get(c, "sel")
    fun setSel(c: Context, v: Collection<String>) = put(c, "sel", v)
    fun applied(c: Context) = get(c, "applied")
    fun setApplied(c: Context, v: Collection<String>) = put(c, "applied", v)

    /** Các mục do chính app này thêm vào khóa Millet (chỉ những mục này mới bị gỡ khi hoàn tác). */
    fun added(c: Context) = get(c, "added")
    fun setAdded(c: Context, v: Collection<String>) = put(c, "added", v)
}

/** Nhật ký dạng file text, tự cắt khi quá lớn. Không lưu nội dung thông báo. */
object Logs {
    @Synchronized
    fun append(c: Context, name: String, line: String, maxBytes: Long) {
        try {
            val f = File(c.filesDir, name)
            if (f.length() > maxBytes) {
                val l = f.readLines()
                f.writeText(l.takeLast(l.size * 6 / 10).joinToString("\n") + "\n")
            }
            f.appendText(line + "\n")
        } catch (_: Throwable) {
        }
    }

    fun read(c: Context, name: String): List<String> =
        try { File(c.filesDir, name).readLines() } catch (_: Throwable) { emptyList() }
}

object Millet {
    const val KEY = "MILLET_NO_RESTRICT_APP"
    const val GMS = "com.google.android.gms"
    val CORE = listOf(GMS, "com.google.android.gms.persistent", "com.google.android.gsf")

    fun read(c: Context): String? =
        try { Settings.System.getString(c.contentResolver, KEY) } catch (_: Throwable) { null }

    fun tokens(v: String?): List<String> =
        v?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()

    fun wanted(c: Context): List<String> = (CORE + c.packageName + Store.applied(c)).distinct()

    /** Chỉ nối thêm phần thiếu, không đụng các mục có sẵn. Không ghi gì nếu đã đủ. */
    fun ensure(c: Context): Fix {
        val cur = read(c)
        val have = tokens(cur).toHashSet()
        val miss = wanted(c).filter { it !in have }
        if (miss.isEmpty()) return Fix.OK
        if (!Settings.System.canWrite(c)) return Fix.NO_PERM
        val base = cur.orEmpty().trimEnd(',')
        val nv = if (base.isEmpty()) miss.joinToString(",") else base + "," + miss.joinToString(",")
        return try {
            Settings.System.putString(c.contentResolver, KEY, nv)
            Store.setAdded(c, Store.added(c) + miss)
            Logs.append(c, "guard.log", "${System.currentTimeMillis()}|FIX|${miss.joinToString("+")}", 20_000)
            if (GMS in miss) Fix.FIXED_GMS else Fix.FIXED
        } catch (_: Throwable) {
            Fix.NO_PERM
        }
    }

    /** Chỉ gỡ mục do app này thêm; mục có sẵn của hệ thống/người bán được giữ nguyên. */
    fun remove(c: Context, pkgs: Collection<String>) {
        val mine = Store.added(c)
        val del = pkgs.filter { it in mine && it !in CORE && it != c.packageName }.toSet()
        if (del.isEmpty() || !Settings.System.canWrite(c)) return
        val cur = read(c) ?: return
        try {
            Settings.System.putString(c.contentResolver, KEY, tokens(cur).filter { it !in del }.joinToString(","))
            Store.setAdded(c, mine - del)
        } catch (_: Throwable) {
        }
    }

    /** Best-effort: nhắc GMS nối lại kết nối push. Không đảm bảo trên mọi bản GMS. */
    fun heartbeat(c: Context) {
        for (a in listOf(
            "com.google.android.intent.action.GTALK_HEARTBEAT",
            "com.google.android.intent.action.MCS_HEARTBEAT"
        )) {
            try { c.sendBroadcast(Intent(a).setPackage(GMS)) } catch (_: Throwable) {}
        }
    }
}

/**
 * Không có service thường trực. Chỉ hai job của JobScheduler:
 *  - TRIGGER: chạy khi khóa Millet bị đổi (theo sự kiện, 0 CPU lúc rảnh)
 *  - PERIODIC: 60 phút/lần, lưới an toàn + dựng lại TRIGGER nếu bị mất
 */
object Guard {
    const val J_TRIGGER = 1
    const val J_PERIODIC = 2

    private fun js(c: Context) = c.getSystemService(JobScheduler::class.java)
    private fun comp(c: Context) = ComponentName(c, GuardJob::class.java)

    fun ensureScheduled(c: Context) {
        val s = js(c)
        if (s.getPendingJob(J_PERIODIC) == null) {
            s.schedule(
                JobInfo.Builder(J_PERIODIC, comp(c))
                    .setPeriodic(60 * 60_000L, 20 * 60_000L)
                    .setPersisted(true).build()
            )
        }
        armTrigger(c, false)
    }

    fun armTrigger(c: Context, force: Boolean) {
        val s = js(c)
        if (!force && s.getPendingJob(J_TRIGGER) != null) return
        val uri = Settings.System.getUriFor(Millet.KEY)
        s.schedule(
            JobInfo.Builder(J_TRIGGER, comp(c))
                .addTriggerContentUri(JobInfo.TriggerContentUri(uri, JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS))
                .setTriggerContentUpdateDelay(3000)
                .setTriggerContentMaxDelay(15_000)
                .build()
        )
    }

    fun runCheck(c: Context) {
        if (Millet.ensure(c) == Fix.FIXED_GMS) Millet.heartbeat(c)
    }
}

object Stats {
    fun fmt(ms: Long) = when {
        ms < 1000 -> "${ms}ms"
        ms < 60_000 -> "${ms / 1000}s"
        else -> "${ms / 60_000}p"
    }

    fun latency(c: Context): String {
        val cut = System.currentTimeMillis() - 7 * 86_400_000L
        val m = HashMap<String, MutableList<Long>>()
        for (l in Logs.read(c, "lat.log")) {
            val p = l.split('|')
            if (p.size < 3) continue
            val t = p[0].toLongOrNull() ?: continue
            val d = p[2].toLongOrNull() ?: continue
            if (t >= cut) m.getOrPut(p[1]) { ArrayList() }.add(d)
        }
        if (m.isEmpty()) return "Chưa có dữ liệu độ trễ. Bật \"Đo độ trễ\" rồi dùng máy vài ngày."
        val pm = c.packageManager
        val sb = StringBuilder("Độ trễ 7 ngày (lúc hệ thống nhận - lúc app gửi)\nMỗi app: số tin | trung vị | p90 | tối đa | số tin >60s\n\n")
        m.entries
            .map { e -> val v = e.value; v.sort(); Triple(e.key, v, v[minOf(v.size - 1, v.size * 9 / 10)]) }
            .sortedByDescending { it.third }
            .take(25)
            .forEach { t ->
                val k = t.first
                val v = t.second
                val name = try { pm.getApplicationLabel(pm.getApplicationInfo(k, 0)).toString() } catch (_: Throwable) { k }
                sb.append("$name\n  ${v.size} | ${fmt(v[v.size / 2])} | ${fmt(t.third)} | ${fmt(v.last())} | ${v.count { it > 60_000 }}\n")
            }
        sb.append("\nChỉ đáng tin với app đặt đúng thời điểm gửi (chat, ngân hàng). Sắp xếp theo p90 giảm dần.")
        return sb.toString()
    }
}
