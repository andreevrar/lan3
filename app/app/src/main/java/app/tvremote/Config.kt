package app.tvremote

import android.content.Context
import android.content.SharedPreferences
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.SecureRandom

object Config {
    const val ACC_ID = "app.tvremote/app.tvremote.RemoteAccessibilityService"

    private fun prefs(c: Context): SharedPreferences =
        c.getSharedPreferences("cfg", Context.MODE_PRIVATE)

    /** Секретный код ТВ: страница пульта вводит его при подключении. */
    fun code(c: Context): String {
        val saved = prefs(c).getString("code", null)
        return saved ?: regenerate(c)
    }

    fun regenerate(c: Context): String {
        val chars = "abcdefghjkmnpqrstuvwxyz23456789"
        val rnd = SecureRandom()
        val sb = StringBuilder()
        repeat(12) { sb.append(chars[rnd.nextInt(chars.length)]) }
        val v = sb.toString()
        prefs(c).edit().putString("code", v).apply()
        return v
    }

    fun pretty(code: String): String = code.chunked(4).joinToString("-")
}

/** Общее состояние для экрана приложения. */
object Status {
    @Volatile var server: String = "не запущен"
    @Volatile var port: Int = 0
    @Volatile var clients: Int = 0
    @Volatile var adbOk: Boolean = false
    @Volatile var fast: Boolean = false
    @Volatile var lastCmd: String = "—"
    /** Чем закончился последний ввод текста (способ и результат), для диагностики. */
    @Volatile var lastText: String = "ещё не вводили"
    /** Почему и когда запущена служба (при включении ТВ, сторожем и т. д.). */
    @Volatile var startReason: String = "—"
    @Volatile var startedAt: Long = 0L
    /** Работает ли сторож в системе ТВ (перезапускает приложение, если его закрыли). */
    @Volatile var supervisor: Boolean = false
    /** Последняя проверка «ТВ проснулся / будильник» и её причина. */
    @Volatile var lastWake: String = "—"
    /** Сколько раз самопроверка перезапускала веб-сервер. */
    @Volatile var webRestarts: Int = 0
    /** Чем закончилась последняя кнопка (помощник / input / спец. возможности) и когда помощник отвечал в последний раз. */
    @Volatile var lastKey: String = "—"
    @Volatile var lastAck: Long = 0L
}

/** Адреса ТВ в домашней сети. */
object Net {
    /** IPv4-адреса активных не-loopback интерфейсов; домашние (192.168.x, 10.x, 172.16–31.x) идут первыми. */
    fun localIps(): List<String> {
        val found = ArrayList<Inet4Address>()
        try {
            val ifs = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
            for (nif in ifs) {
                if (!nif.isUp || nif.isLoopback) continue
                for (a in nif.inetAddresses) {
                    if (a is Inet4Address && !a.isLoopbackAddress && !a.isLinkLocalAddress) found.add(a)
                }
            }
        } catch (_: Throwable) {
        }
        return found.sortedBy { if (it.isSiteLocalAddress) 0 else 1 }.mapNotNull { it.hostAddress }
    }

    fun url(ip: String, port: Int): String = "http://$ip:$port"
}

/** Разрешённые кнопки и их коды Android KeyEvent. Ничего кроме этого список ТВ не выполняет. */
object Keys {
    val codes: Map<String, Int> = mapOf(
        "UP" to 19, "DOWN" to 20, "LEFT" to 21, "RIGHT" to 22, "OK" to 23,
        "ENTER" to 66, "BACK" to 4, "HOME" to 3, "MENU" to 82,
        "VOL_UP" to 24, "VOL_DOWN" to 25, "MUTE" to 164,
        "CH_UP" to 166, "CH_DOWN" to 167,
        "POWER" to 26, "SLEEP" to 223, "WAKEUP" to 224,
        "PLAY_PAUSE" to 85, "REWIND" to 89, "FF" to 90, "NEXT" to 87, "PREV" to 88,
        "DEL" to 67, "SPACE" to 62, "SEARCH" to 84, "SETTINGS" to 176, "INFO" to 165
    ) + (0..9).associate { it.toString() to (7 + it) }
}
