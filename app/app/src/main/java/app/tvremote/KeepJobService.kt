package app.tvremote

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context

/**
 * Запасной запуск, не зависящий от сигнала «ТВ включён»: задание хранится в самой системе
 * (setPersisted) и после перезагрузки или пробуждения ТВ выполняется само. Каждое выполнение
 * поднимает службу и заводит следующее задание.
 */
class KeepJobService : JobService() {
    override fun onStartJob(params: JobParameters?): Boolean {
        EventLog.init(this)
        if (!ControlService.alive) EventLog.add("задание системы: служба не работала, запускаю")
        ControlService.ensure(applicationContext, "задание системы")
        schedule(applicationContext)
        return false
    }

    override fun onStopJob(params: JobParameters?): Boolean = true

    companion object {
        private const val ONE_SHOT = 71
        private const val PERIODIC = 72

        fun schedule(c: Context) {
            try {
                val js = c.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
                val cn = ComponentName(c, KeepJobService::class.java)
                // ближайшее: через минуту; после перезагрузки сработает сразу, как только система готова
                js.schedule(
                    JobInfo.Builder(ONE_SHOT, cn)
                        .setMinimumLatency(60_000L)
                        .setPersisted(true)
                        .build()
                )
                // страховка: раз в 15 минут в любом случае
                if (js.getPendingJob(PERIODIC) == null) {
                    js.schedule(
                        JobInfo.Builder(PERIODIC, cn)
                            .setPeriodic(15 * 60_000L)
                            .setPersisted(true)
                            .build()
                    )
                }
            } catch (_: Throwable) {
            }
        }
    }
}
