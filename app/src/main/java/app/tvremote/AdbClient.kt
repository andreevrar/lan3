package app.tvremote

import android.content.Context
import dadb.AdbKeyPair
import dadb.AdbShellStream
import dadb.Dadb
import java.io.File

/**
 * Подключение к ADB самого телевизора (127.0.0.1:5555).
 * При первом подключении на экране ТВ появится запрос «Разрешить отладку по USB/ADB?» —
 * нужно нажать «Всегда разрешать».
 *
 * Кнопки отправляются через постоянный помощник ([Injector]), запущенный один раз:
 * это мгновенно. Обычный `shell("input keyevent …")` остаётся запасным путём.
 */
class AdbClient(private val ctx: Context) {
    private var dadb: Dadb? = null

    private val injLock = Any()
    @Volatile private var inj: AdbShellStream? = null
    @Volatile private var injBlockedUntil = 0L

    // Соединение создаётся лениво и без сетевых операций, поэтому блокировка короткая.
    @Synchronized
    private fun connect(): Dadb? {
        dadb?.let { return it }
        return try {
            val priv = File(ctx.filesDir, "adbkey")
            val pub = File(ctx.filesDir, "adbkey.pub")
            if (!priv.exists() || !pub.exists()) {
                AdbKeyPair.generate(priv, pub)
            }
            val keyPair = AdbKeyPair.read(priv, pub)
            Dadb.create("127.0.0.1", 5555, keyPair).also { dadb = it }
        } catch (e: Throwable) {
            null
        }
    }

    @Synchronized
    private fun drop() {
        try {
            dadb?.close()
        } catch (_: Throwable) {
        }
        dadb = null
        inj = null
        Status.fast = false
    }

    /** Выполнить команду. true — если выполнена без ошибок. */
    fun shell(cmd: String): Boolean {
        val d = connect() ?: return false
        return try {
            d.shell(cmd).exitCode == 0
        } catch (e: Throwable) {
            drop()
            false
        }
    }

    /** Выполнить команду и вернуть вывод (null — при ошибке). */
    fun shellOut(cmd: String): String? {
        val d = connect() ?: return null
        return try {
            d.shell(cmd).output
        } catch (e: Throwable) {
            drop()
            null
        }
    }

    // ---------- быстрые нажатия ----------

    /**
     * Запускает помощника, если он ещё не работает. Может ждать ответа ADB, поэтому вызывать
     * только из фонового потока и не из потока обработки кнопок.
     */
    fun warmUp() {
        if (inj != null) return
        if (System.currentTimeMillis() < injBlockedUntil) return
        synchronized(injLock) {
            if (inj != null) return
            val d = connect() ?: return
            try {
                val apk = ctx.applicationInfo.sourceDir
                val started = System.currentTimeMillis()
                val st = d.openShell(
                    "CLASSPATH='$apk' exec app_process /system/bin --nice-name=tvremote-input app.tvremote.Injector"
                )
                inj = st
                Status.fast = true
                // читаем вывод помощника, пока он жив; когда процесс завершится, помечаем его мёртвым
                val t = Thread {
                    try {
                        while (true) {
                            val p = st.read()
                            if (p.javaClass.simpleName == "Exit") break
                        }
                    } catch (_: Throwable) {
                    }
                    if (inj === st) {
                        inj = null
                        Status.fast = false
                    }
                    try {
                        st.close()
                    } catch (_: Throwable) {
                    }
                    // умер сразу после запуска (например, нет доступа к InputManager) — не перезапускаем бесконечно
                    if (System.currentTimeMillis() - started < 5000) {
                        injBlockedUntil = System.currentTimeMillis() + 60000
                    }
                }
                t.isDaemon = true
                t.start()
            } catch (e: Throwable) {
                inj = null
                Status.fast = false
                injBlockedUntil = System.currentTimeMillis() + 30000
            }
        }
    }

    /** Отправить команду помощнику. false — помощник не запущен или связь оборвалась. Сам он не запускается, чтобы не ждать. */
    fun inject(line: String): Boolean {
        val st = inj ?: return false
        return try {
            synchronized(injLock) { st.write(line + "\n") }
            true
        } catch (_: Throwable) {
            if (inj === st) {
                inj = null
                Status.fast = false
            }
            false
        }
    }

    // ---------- настройки системы ----------

    /** Включает службу специальных возможностей приложения через ADB, не трогая остальные службы. */
    fun enableAccessibility(): Boolean {
        val cur = (shellOut("settings get secure enabled_accessibility_services") ?: return false).trim()
        if (!cur.contains(Config.ACC_ID)) {
            val list = if (cur.isEmpty() || cur == "null") Config.ACC_ID else "$cur:${Config.ACC_ID}"
            if (!shell("settings put secure enabled_accessibility_services $list")) return false
        }
        return shell("settings put secure accessibility_enabled 1")
    }

    /**
     * Ставит в системе ТВ маленький сторож (shell-скрипт): раз в 30 секунд он запускает службу приложения.
     * Он живёт отдельно от приложения, поэтому переживает его закрытие и «чистку» памяти.
     * До перезагрузки ТВ ставится один раз; после неё приложение поставит его снова.
     */
    fun ensureSupervisor(): Boolean {
        val pid = "/data/local/tmp/tvremote.pid"
        val sh = "/data/local/tmp/tvremote-sup.sh"
        val running = shellOut(
            "p=\$(cat $pid 2>/dev/null); [ -n \"\$p\" ] && grep -q tvremote-sup /proc/\$p/cmdline 2>/dev/null && echo running"
        )
        if (running != null && running.contains("running")) return true
        val script = "echo \$\$ > $pid\n" +
            "while true; do\n" +
            "  am start-foreground-service -n app.tvremote/.ControlService --es why sup >/dev/null 2>&1 || " +
            "am startservice -n app.tvremote/.ControlService --es why sup >/dev/null 2>&1\n" +
            "  sleep 30\n" +
            "done\n"
        val b64 = android.util.Base64.encodeToString(script.toByteArray(), android.util.Base64.NO_WRAP)
        if (!shell("echo $b64 | base64 -d > $sh")) return false
        if (!shell("(setsid sh $sh >/dev/null 2>&1 </dev/null &)")) return false
        val chk = shellOut(
            "sleep 1; p=\$(cat $pid 2>/dev/null); [ -n \"\$p\" ] && grep -q tvremote-sup /proc/\$p/cmdline 2>/dev/null && echo running"
        )
        return chk != null && chk.contains("running")
    }

    /**
     * Голосовой ввод через телефон: делает «службой распознавания речи» системы наше приложение
     * (или возвращает прежнюю). Прежнее значение запоминается, чтобы можно было вернуть.
     */
    fun setVoice(on: Boolean): Boolean {
        val mine = "app.tvremote/app.tvremote.PhoneRecognitionService"
        val sp = ctx.getSharedPreferences("tvremote", Context.MODE_PRIVATE)
        val ok: Boolean
        if (on) {
            val cur = (shellOut("settings get secure voice_recognition_service") ?: return false).trim()
            if (cur != mine) sp.edit().putString("voice_prev", cur).apply()
            ok = shell("settings put secure voice_recognition_service $mine")
        } else {
            val prev = sp.getString("voice_prev", "") ?: ""
            ok = if (prev.isEmpty() || prev == "null" || prev == mine) {
                shell("settings delete secure voice_recognition_service")
            } else {
                shell("settings put secure voice_recognition_service $prev")
            }
        }
        if (ok) sp.edit().putBoolean("voice_on", on).apply()
        return ok
    }

    /** Просит систему не усыплять и не убивать приложение в фоне (чтобы пульт работал после включения ТВ). Что не вышло, пропускаем. */
    fun keepAlive() {
        shell("dumpsys deviceidle whitelist +app.tvremote")
        shell("cmd appops set app.tvremote RUN_IN_BACKGROUND allow")
        shell("cmd appops set app.tvremote RUN_ANY_IN_BACKGROUND allow")
    }
}
