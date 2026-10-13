package app.tvremote

import android.content.Context
import android.os.SystemClock
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Журнал событий в файле: переживает перезапуск приложения и перезагрузку ТВ.
 * По нему видно, что именно происходило после выключения ТВ: запускался ли процесс, пришёл ли сигнал
 * включения, работал ли ADB. Последние строки показаны на экране ТВ и на странице пульта.
 */
object EventLog {
    @Volatile private var dir: File? = null
    private val fmt = SimpleDateFormat("dd.MM HH:mm:ss", Locale.getDefault())
    private val recent = ArrayDeque<String>()

    fun init(c: Context) {
        if (dir == null) dir = c.applicationContext.filesDir
    }

    @Synchronized
    fun add(msg: String) {
        val line = fmt.format(Date()) + " " + msg
        recent.addLast(line)
        while (recent.size > 40) recent.removeFirst()
        try {
            val f = File(dir ?: return, "events.log")
            if (f.length() > 48 * 1024) {
                val keep = f.readLines().takeLast(120)
                f.writeText(keep.joinToString("\n") + "\n")
            }
            f.appendText(line + "\n")
        } catch (_: Throwable) {
        }
    }

    /** Последние n строк (из файла — включая то, что было до перезапуска приложения). */
    @Synchronized
    fun tail(n: Int): List<String> {
        return try {
            val f = File(dir ?: return recent.toList().takeLast(n), "events.log")
            if (f.exists()) f.readLines().takeLast(n) else emptyList()
        } catch (_: Throwable) {
            recent.toList().takeLast(n)
        }
    }

    /** Сколько секунд прошло с включения ТВ (чтобы отличить перезагрузку от перезапуска приложения). */
    fun uptimeSec(): Long = SystemClock.elapsedRealtime() / 1000
}
