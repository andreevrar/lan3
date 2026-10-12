package app.tvremote

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Запускает фоновую службу при включении ТВ, после обновления приложения, при смене времени
 * и по будильнику KeepAlive. Каждый раз заводит следующий будильник.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        EventLog.init(app)
        if (intent.action != KeepAlive.ACTION) EventLog.add("сигнал: " + (intent.action ?: "?"))
        val why = when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> "включение ТВ"
            "android.intent.action.QUICKBOOT_POWERON", "com.htc.intent.action.QUICKBOOT_POWERON" -> "быстрое включение ТВ"
            Intent.ACTION_MY_PACKAGE_REPLACED -> "обновление приложения"
            KeepAlive.ACTION -> "будильник"
            else -> "событие системы"
        }
        ControlService.ensure(app, why)
        KeepAlive.schedule(app)
    }
}
