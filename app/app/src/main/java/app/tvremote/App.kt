package app.tvremote

import android.app.Application

/** Процесс приложения стартует по любой причине (сигнал ТВ, будильник, задание системы) — записываем это. */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        EventLog.init(this)
        EventLog.add("процесс запущен (ТВ включён ${EventLog.uptimeSec()} с назад)")
    }
}
