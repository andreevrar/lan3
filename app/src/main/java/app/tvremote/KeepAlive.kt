package app.tvremote

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock

/**
 * Будильник раз в минуту: если систему «почистила» память или ТВ проснулся из ждущего режима,
 * будильник поднимет приложение и службу заново. Каждый сработавший будильник заводит следующий.
 */
object KeepAlive {
    const val ACTION = "app.tvremote.KEEPALIVE"

    fun schedule(c: Context) {
        KeepJobService.schedule(c)
        try {
            val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val i = Intent(c, BootReceiver::class.java).setAction(ACTION)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
            val pi = PendingIntent.getBroadcast(c, 7, i, flags)
            val at = SystemClock.elapsedRealtime() + 60000L
            if (Build.VERSION.SDK_INT >= 23) {
                am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
            } else {
                am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
            }
        } catch (_: Throwable) {
        }
    }
}
