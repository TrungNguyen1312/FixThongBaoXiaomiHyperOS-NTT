package app.fixtbntt

import android.app.Notification
import android.app.job.JobParameters
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class GuardJob : JobService() {
    override fun onStartJob(p: JobParameters): Boolean {
        Guard.runCheck(this)
        // Job theo sự kiện chỉ chạy một lần, nên dựng lại ngay sau khi xong.
        Guard.armTrigger(this, p.jobId == Guard.J_TRIGGER)
        return false
    }

    override fun onStopJob(p: JobParameters) = false
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        Guard.ensureScheduled(c)
        Guard.runCheck(c)
    }
}

/** Mặc định TẮT (xem manifest). Chỉ ghi tên gói + độ trễ (ms), không đọc nội dung thông báo. */
class LatencyListener : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val n = sbn.notification ?: return
        if (sbn.packageName == packageName) return
        val skip = Notification.FLAG_ONGOING_EVENT or Notification.FLAG_FOREGROUND_SERVICE or Notification.FLAG_GROUP_SUMMARY
        if ((n.flags and skip) != 0) return
        val w = n.`when`
        if (w <= 0L) return
        val d = sbn.postTime - w
        if (d < 0L || d > 6 * 3_600_000L) return
        Logs.append(this, "lat.log", "${System.currentTimeMillis()}|${sbn.packageName}|$d", 150_000)
    }
}
