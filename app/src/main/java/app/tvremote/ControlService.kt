package app.tvremote

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import android.net.wifi.WifiManager
import android.os.PowerManager
import android.media.AudioManager
import android.view.KeyEvent
import android.view.View
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Фоновая служба: поднимает на ТВ маленький веб-сервер (страница пульта + WebSocket),
 * принимает команды из домашней сети и выполняет их (ADB `input keyevent` или служба спец. возможностей).
 * Интернет и посредники не нужны.
 */
class ControlService : Service() {

    companion object {
        /** true, пока служба работает; по этому флагу служба спец. возможностей понимает, что пора перезапустить. */
        @Volatile var alive = false
        @Volatile private var current: ControlService? = null

        /** Отправить сообщение всем открытым страницам пульта (используется голосовым вводом). */
        fun pages(json: String) {
            try {
                current?.web?.broadcastRaw(json)
            } catch (_: Throwable) {
            }
        }

        /** Запускает службу из любого места (старт ТВ, служба спец. возможностей, экран). Ошибки не страшны. */
        fun ensure(c: Context, why: String = "приложение") {
            val i = Intent(c, ControlService::class.java)
            i.putExtra("why", why)
            try {
                if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i) else c.startService(i)
            } catch (_: Throwable) {
                try {
                    c.startService(i)
                } catch (_: Throwable) {
                }
            }
        }
    }

    private val worker = Executors.newSingleThreadExecutor()
    private val sched = Executors.newSingleThreadScheduledExecutor()
    // отдельный поток для веб-сервера: ожидание ADB-разрешения не должно его задерживать
    private val webSched = Executors.newSingleThreadScheduledExecutor()
    private val pending = AtomicInteger(0)

    private lateinit var adb: AdbClient
    @Volatile private var web: WebServer? = null
    private var started = false
    @Volatile private var stopped = false
    private var wake: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()
        alive = true
        current = this
        val why = when (intent?.getStringExtra("why")) {
            null -> "перезапуск системой"
            "sup" -> "сторож в системе ТВ"
            else -> intent.getStringExtra("why") ?: "—"
        }
        // плановые проверки (будильник, задание, сторож) в журнал не пишем, пока служба жива, иначе он забьётся ими
        val routine = why == "будильник" || why == "задание системы" || why == "сторож в системе ТВ" || why == "служба спец. возможностей"
        if (!started || !routine) EventLog.add("служба: запрос на запуск — $why" + (if (started) " (уже работает)" else ""))
        if (!started) {
            started = true
            Status.startReason = why
            Status.startedAt = System.currentTimeMillis()
            init()
        } else {
            // уже работаем: просто проверяем, что сервер и ADB живы (ТВ мог проснуться)
            Status.lastWake = why
            sched.execute { try { wakeCheck() } catch (_: Throwable) {} }
        }
        KeepAlive.schedule(applicationContext)
        return START_STICKY
    }

    override fun onDestroy() {
        stopped = true
        alive = false
        if (current === this) current = null
        EventLog.add("служба остановлена")
        try {
            unregisterReceiver(wakeReceiver)
        } catch (_: Throwable) {
        }
        // если нас остановила система, будильник поднимет службу снова
        KeepAlive.schedule(applicationContext)
        try {
            web?.stop()
        } catch (_: Throwable) {
        }
        Status.server = "остановлен"
        Status.port = 0
        Status.clients = 0
        sched.shutdownNow()
        webSched.shutdownNow()
        worker.shutdownNow()
        try {
            wake?.release()
        } catch (_: Throwable) {
        }
        try {
            wifiLock?.release()
        } catch (_: Throwable) {
        }
        super.onDestroy()
    }

    // ---------- запуск ----------

    private fun startForegroundCompat() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "remote"
        val builder: Notification.Builder
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(channelId, "ТВ-пульт", NotificationManager.IMPORTANCE_LOW)
            )
            builder = Notification.Builder(this, channelId)
        } else {
            builder = Notification.Builder(this)
        }
        val n = builder
            .setContentTitle("ТВ-пульт работает")
            .setContentText("Код: " + Config.pretty(Config.code(this)))
            .setSmallIcon(R.drawable.ic_launcher)
            .build()
        startForeground(1, n)
    }

    private fun init() {
        adb = AdbClient(applicationContext)
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "tvremote:service")
            wake?.acquire()
        } catch (_: Throwable) {
        }
        // чтобы Wi-Fi не засыпал, пока ТВ ждёт команд
        try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            else WifiManager.WIFI_MODE_FULL_HIGH_PERF
            wifiLock = wm.createWifiLock(mode, "tvremote:wifi")
            wifiLock?.acquire()
        } catch (_: Throwable) {
        }
        // ТВ проснулся из ждущего режима / сменилась сеть — проверяем сервер и ADB
        try {
            val f = IntentFilter()
            f.addAction(Intent.ACTION_SCREEN_ON)
            f.addAction(Intent.ACTION_USER_PRESENT)
            f.addAction(Intent.ACTION_POWER_CONNECTED)
            f.addAction("android.net.conn.CONNECTIVITY_CHANGE")
            f.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION)
            registerReceiver(wakeReceiver, f)
        } catch (_: Throwable) {
        }
        sched.execute { probeAdb() }
        webSched.execute { startWeb() }
        // счётчик подключённых страниц и самопроверка сервера
        webSched.scheduleWithFixedDelay({
            try {
                Status.clients = web?.clientCount ?: 0
            } catch (_: Throwable) {
            }
        }, 2L, 2L, TimeUnit.SECONDS)
        webSched.scheduleWithFixedDelay({
            try {
                checkWeb()
            } catch (_: Throwable) {
            }
        }, 20L, 20L, TimeUnit.SECONDS)
    }

    private val wakeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            Status.lastWake = "событие: " + (intent.action ?: "?").substringAfterLast('.')
            try {
                sched.execute { try { wakeCheck() } catch (_: Throwable) {} }
            } catch (_: Throwable) {
            }
        }
    }

    /** Быстрая проверка после пробуждения: сервер отвечает? ADB жив? Сторож на месте? */
    private fun wakeCheck() {
        if (stopped) return
        webSched.execute { try { checkWeb() } catch (_: Throwable) {} }
        var ok = adb.shell("echo ok")
        if (!ok) {
            // соединение могло «протухнуть» за время сна: начинаем заново
            adb.reset()
            ok = adb.shell("echo ok")
            EventLog.add("после пробуждения ADB " + (if (ok) "переподключён" else "недоступен"))
        }
        setAdb(ok)
        if (ok) {
            adb.warmUp()
            if (!Status.supervisor) Status.supervisor = adb.ensureSupervisor()
        }
        publishStatus()
    }

    private fun setAdb(ok: Boolean) {
        if (Status.adbOk != ok) EventLog.add("ADB: " + (if (ok) "подключён" else "нет связи"))
        Status.adbOk = ok
    }

    // ---------- самопроверка веб-сервера ----------

    /** true, если по адресу host:port сервер отвечает на запрос страницы. */
    private fun ping(host: String, port: Int): Boolean = try {
        java.net.Socket().use { s ->
            s.connect(java.net.InetSocketAddress(host, port), 2000)
            s.soTimeout = 2000
            s.getOutputStream().write("GET /favicon.ico HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n".toByteArray())
            s.getOutputStream().flush()
            val line = java.io.BufferedReader(java.io.InputStreamReader(s.getInputStream())).readLine()
            line != null && line.contains("204")
        }
    } catch (_: Throwable) {
        false
    }

    /**
     * Стучимся на свой же порт и на адрес в Wi-Fi. Если сервер не отвечает (сокет «умер» при засыпании ТВ
     * или смене сети) — поднимаем заново.
     */
    private fun checkWeb() {
        if (stopped) return
        val w = web
        if (w == null) {
            startWeb()
            return
        }
        fun healthy(): Boolean {
            if (!ping("127.0.0.1", w.port)) return false
            val lan = Net.localIps().firstOrNull() ?: return true
            return ping(lan, w.port)
        }
        var ok = healthy()
        if (!ok) {
            sleepMs(1500) // повторная проверка, чтобы не перезапускать из-за случайного сбоя
            ok = healthy()
        }
        if (!ok) {
            Status.webRestarts++
            EventLog.add("сервер не отвечал — перезапуск")
            try {
                w.stop()
            } catch (_: Throwable) {
            }
            web = null
            Status.server = "перезапуск…"
            startWeb()
        }
    }

    // ---------- ADB ----------

    private var accEnsured = false

    private fun probeAdb() {
        if (stopped) return
        var ok = adb.shell("echo ok")
        if (!ok && Status.adbOk) {
            adb.reset()
            ok = adb.shell("echo ok")
        }
        setAdb(ok)
        if (ok) {
            adb.warmUp() // быстрый помощник для кнопок; если он завершился, поднимется снова
            if (!accEnsured) {
                accEnsured = true
                adb.enableAccessibility()
                adb.keepAlive()
            }
            // сторож живёт в системе ТВ и поднимает приложение, даже если его закрыли
            if (!Status.supervisor) Status.supervisor = adb.ensureSupervisor()
        }
        publishStatus()
        if (!stopped) {
            sched.schedule({ probeAdb() }, if (ok) 60L else 30L, TimeUnit.SECONDS)
        }
    }

    // ---------- веб-сервер ----------

    private fun startWeb() {
        if (stopped) return
        val w = WebServer(
            applicationContext,
            Config.code(this),
            { j -> handle(j) },
            { statusJson() }
        )
        try {
            w.start()
            web = w
            Status.server = "работает"
            Status.port = w.port
            EventLog.add("сервер: порт ${w.port}, адреса " + Net.localIps().joinToString(" "))
        } catch (e: Throwable) {
            Status.server = "ошибка: " + (e.message ?: e.javaClass.simpleName)
            if (!stopped) webSched.schedule({ startWeb() }, 10L, TimeUnit.SECONDS)
        }
    }

    private fun statusJson(): String =
        JSONObject()
            .put("online", true)
            .put("adb", Status.adbOk)
            .put("acc", RemoteAccessibilityService.instance != null)
            .put("fast", Status.fast)
            .put("txt", Status.lastText)
            .put("sup", Status.supervisor)
            .put("rsn", Status.startReason)
            .put("lk", Status.lastKey)
            .put("ips", org.json.JSONArray(Net.localIps().map { it + ":" + Status.port }))
            .put("ev", org.json.JSONArray(EventLog.tail(6)))
            .toString()

    private fun publishStatus() {
        try {
            web?.broadcastStatus()
        } catch (_: Throwable) {
        }
    }

    // ---------- команды ----------

    // Все команды исполняются в одном потоке worker, поэтому эти поля трогает только он.
    /** Что, по нашему мнению, набрано в поле (нужно, когда текст вводится клавишами). */
    private var typed = ""
    /** До какого момента не пользоваться вводом через спец. возможности (если поле их игнорирует). */
    private var accBadUntil = 0L
    private val pendingText = AtomicReference<JSONObject?>(null)
    private val injKick = AtomicBoolean(false)

    private fun handle(j: JSONObject) {
        val t = j.optString("t")
        if (t == "voice") {
            VoiceBridge.fromPhone(j)
            return
        }
        // «Заменить текст целиком»: важно только последнее состояние поля, промежуточные не копим.
        if (t == "text" && j.optString("m") == "replace") {
            pendingText.set(j)
            try {
                worker.execute {
                    val x = pendingText.getAndSet(null) ?: return@execute
                    try {
                        runCommand(x)
                    } catch (_: Throwable) {
                    }
                }
            } catch (_: Throwable) {
            }
            return
        }
        // если очередь забита (зажата стрелка), лишние нажатия навигации пропускаем,
        // а текст и удаление символов никогда не теряем
        if (pending.get() > 6 && t == "key" && j.optString("k") != "DEL") return
        pending.incrementAndGet()
        try {
            worker.execute {
                try {
                    runCommand(j)
                } catch (_: Throwable) {
                } finally {
                    pending.decrementAndGet()
                }
            }
        } catch (_: Throwable) {
            pending.decrementAndGet()
        }
    }

    private fun runCommand(j: JSONObject) {
        when (j.optString("t")) {
            "key" -> key(j.optString("k"))
            "text" -> text(j.optString("v"), j.optString("m") != "replace")
        }
    }

    /** Просит перезапустить быстрый помощник в фоне, не задерживая нажатия. */
    private fun kickInjector() {
        if (injKick.compareAndSet(false, true)) {
            sched.execute {
                try {
                    adb.warmUp()
                } finally {
                    injKick.set(false)
                }
            }
        }
    }

    private fun key(name: String) {
        val code = Keys.codes[name] ?: return
        Status.lastCmd = name
        when (name) {
            "DEL" -> typed = typed.dropLast(1)
            "UP", "DOWN", "LEFT", "RIGHT", "OK", "ENTER", "BACK", "HOME", "SEARCH", "MENU" -> typed = ""
        }
        if (Status.adbOk) {
            if (adb.inject("k $code")) {
                Status.lastKey = "$name: помощник"
                return // быстрый путь: помощник уже запущен
            }
            kickInjector()
            if (adb.shell("input keyevent $code")) { // медленный запасной путь
                Status.lastKey = "$name: через input"
                return
            }
            // соединение могло протухнуть: сбрасываем и пробуем ещё раз
            adb.reset()
            if (adb.shell("input keyevent $code")) {
                Status.lastKey = "$name: через input после сброса ADB"
                EventLog.add("ADB был «мёртвым», переподключён")
                return
            }
            setAdb(false)
            publishStatus()
        }
        Status.lastKey = "$name: без ADB"
        // режим без ADB: громкость и медиа-кнопки обрабатываются самой системой
        if (softKey(name, code)) return
        // остальное через службу специальных возможностей
        val acc = RemoteAccessibilityService.instance ?: return
        when (name) {
            "POWER" -> acc.powerDialog()
            "SETTINGS" -> acc.openSettings()
            "BACK" -> acc.back()
            "HOME" -> acc.home()
            "UP" -> acc.move(View.FOCUS_UP)
            "DOWN" -> acc.move(View.FOCUS_DOWN)
            "LEFT" -> acc.move(View.FOCUS_LEFT)
            "RIGHT" -> acc.move(View.FOCUS_RIGHT)
            "OK", "ENTER" -> acc.click()
            "DEL" -> acc.deleteLast()
        }
    }

    /** Кнопки, которые работают без ADB и без спец. возможностей. true — кнопка обработана. */
    private fun softKey(name: String, code: Int): Boolean {
        val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        try {
            when (name) {
                "VOL_UP" -> audio.adjustStreamVolume(
                    AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI
                )
                "VOL_DOWN" -> audio.adjustStreamVolume(
                    AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI
                )
                "MUTE" -> if (Build.VERSION.SDK_INT >= 23) {
                    audio.adjustStreamVolume(
                        AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, AudioManager.FLAG_SHOW_UI
                    )
                } else {
                    return false
                }
                "PLAY_PAUSE", "NEXT", "PREV", "REWIND", "FF" -> {
                    audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
                    audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
                }
                else -> return false
            }
        } catch (_: Throwable) {
            return false
        }
        return true
    }

    // ---------- ввод текста ----------
    //
    // Способы, от лучшего к запасному. После каждого (если служба спец. возможностей жива) проверяем,
    // что текст реально появился в поле; не появился — пробуем следующий:
    //  1) служба спец. возможностей кладёт текст в поле целиком (SET_TEXT);
    //  2) буфер обмена + «вставить» через службу спец. возможностей (без ADB);
    //  3) быстрый помощник: ASCII настоящими клавишами, остальное Ctrl+A / Ctrl+V;
    //  4) ADB-команды: удалить старое клавишами и вставить (KEYCODE_PASTE) или `input text`.
    // Чем закончилась попытка, видно на экране ТВ и на вкладке «Клавиатура» в пульте (Status.lastText).

    private fun short(s: String) = if (s.length > 24) s.take(24) + "…" else s

    private fun text(v: String, append: Boolean) {
        Status.lastCmd = "текст"
        val target = if (append) typed + v else v
        val acc = RemoteAccessibilityService.instance
        val log = StringBuilder()
        fun done(how: String) {
            typed = target
            Status.lastText = how + ": «" + short(target) + "»"
        }
        fun fieldIs(): Boolean? = acc?.fieldText()?.let { it == target } // null — проверить нельзя

        if (acc == null) {
            log.append("спец. возможности выключены; ")
        } else if (System.currentTimeMillis() >= accBadUntil) {
            when (acc.setText(v, append)) {
                RemoteAccessibilityService.TEXT_OK -> {
                    done("ок, SET_TEXT")
                    return
                }
                RemoteAccessibilityService.TEXT_NO_FIELD -> log.append("поле ввода не видно спец. возможностям; ")
                else -> {
                    // поле молча игнорирует SET_TEXT: какое-то время не пробуем его
                    accBadUntil = System.currentTimeMillis() + 20000
                    log.append("поле не приняло SET_TEXT; ")
                }
            }
        } else {
            log.append("SET_TEXT пропущен (поле его игнорирует); ")
        }

        val ascii = target.all { it.code in 32..126 }

        // 2) буфер обмена + вставка через спец. возможности
        if (acc != null && target.isNotEmpty() && setClip(target)) {
            if (acc.paste()) {
                sleepMs(160)
                if (fieldIs() != false) {
                    done("ок, вставка (спец. возможности)")
                    return
                }
                log.append("вставка не сработала; ")
            } else {
                log.append("вставка недоступна; ")
            }
        }

        if (!Status.adbOk) {
            Status.lastText = "НЕ удалось — " + log + "ADB нет"
            return
        }

        // 3) быстрый помощник
        if (Status.fast) {
            if (ascii) {
                val common = if (append) typed.length else commonPrefix(typed, target)
                repeat(typed.length - common) { adb.inject("k 67") } // 67 = Backspace
                val add = target.substring(common)
                if (add.isEmpty() || adb.inject("s $add")) {
                    sleepMs(200)
                    if (fieldIs() != false) {
                        done("ок, клавиши (помощник)")
                        return
                    }
                    log.append("клавиши не дошли; ")
                }
            } else if (setClip(target)) {
                adb.inject("ctrl 29") // Ctrl+A
                adb.inject("ctrl 50") // Ctrl+V
                sleepMs(250)
                if (fieldIs() != false) {
                    done("ок, Ctrl+V (помощник)")
                    return
                }
                log.append("Ctrl+V не сработал; ")
            }
        } else {
            log.append("помощник не запущен; ")
            kickInjector()
        }

        // 4) обычные ADB-команды (медленнее, зато не зависят от помощника)
        val del = StringBuilder("input keyevent 123") // 123 = MOVE_END
        repeat(minOf(typed.length + 2, 60)) { del.append(" 67") }
        adb.shell(del.toString())
        if (ascii) {
            adbText(target)
            sleepMs(200)
            if (fieldIs() != false) {
                done("ок, input text")
                return
            }
        } else if (setClip(target)) {
            adb.shell("input keyevent 279") // 279 = KEYCODE_PASTE
            sleepMs(250)
            if (fieldIs() != false) {
                done("ок, KEYCODE_PASTE")
                return
            }
        }
        Status.lastText = "НЕ удалось — " + log + "поле не приняло ни один способ"
    }

    private fun sleepMs(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
        }
    }

    private fun commonPrefix(a: String, b: String): Int {
        var i = 0
        while (i < a.length && i < b.length && a[i] == b[i]) i++
        return i
    }

    private fun setClip(t: String): Boolean = try {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("tv", t))
        true
    } catch (_: Throwable) {
        false
    }

    private fun adbText(v: String) {
        val sb = StringBuilder()
        for (ch in v) {
            when {
                ch == ' ' -> sb.append("%s")
                ch.code < 128 && ch.isLetterOrDigit() -> sb.append(ch)
                ".,-_@:/+=".indexOf(ch) >= 0 -> sb.append(ch)
            }
        }
        if (sb.isNotEmpty()) adb.shell("input text '$sb'")
    }
}
