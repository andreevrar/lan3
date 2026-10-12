package app.tvremote

import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.os.SystemClock
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Постоянный помощник для мгновенных нажатий.
 *
 * Запускается ОДИН РАЗ через ADB от имени shell (`app_process`) и остаётся в памяти.
 * Приложение пишет ему строки в stdin, он вставляет события клавиш прямо во входную
 * подсистему Android. Это в десятки раз быстрее, чем запускать `input keyevent` на каждое нажатие
 * (каждый такой запуск — новый Java-процесс на ТВ).
 *
 * Команды (по одной в строке):
 *   k <код>      — нажать и отпустить клавишу
 *   ctrl <код>   — нажать Ctrl+клавишу (для Ctrl+A / Ctrl+V)
 *   s <текст>    — напечатать ASCII-текст клавишами
 */
object Injector {
    private const val SOURCE_KEYBOARD = InputDevice.SOURCE_KEYBOARD
    private const val CTRL_LEFT = 113

    private var sender: ((InputEvent) -> Unit)? = null

    @JvmStatic
    fun main(args: Array<String>) {
        sender = findSender()
        val kcm = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD)
        val r = BufferedReader(InputStreamReader(System.`in`, Charsets.UTF_8))
        while (true) {
            val line = r.readLine() ?: break
            try {
                run(line, kcm)
            } catch (e: Throwable) {
                println("ERR $e")
            }
        }
    }

    /** Находит скрытый метод injectInputEvent: на Android 14+ он в InputManagerGlobal, раньше в InputManager. */
    private fun findSender(): (InputEvent) -> Unit {
        var last: Throwable? = null
        for (cn in listOf("android.hardware.input.InputManagerGlobal", "android.hardware.input.InputManager")) {
            try {
                val cls = Class.forName(cn)
                val inst = cls.getMethod("getInstance").invoke(null)
                val m = cls.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
                return { e -> m.invoke(inst, e, 0) } // 0 = не ждать результата
            } catch (t: Throwable) {
                last = t
            }
        }
        throw IllegalStateException("нет доступа к InputManager: $last")
    }

    private fun send(action: Int, code: Int, meta: Int) {
        val t = SystemClock.uptimeMillis()
        val e = KeyEvent(t, t, action, code, 0, meta, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, SOURCE_KEYBOARD)
        sender!!.invoke(e)
    }

    private fun press(code: Int, meta: Int = 0) {
        send(KeyEvent.ACTION_DOWN, code, meta)
        send(KeyEvent.ACTION_UP, code, meta)
    }

    private fun run(line: String, kcm: KeyCharacterMap) {
        val sp = line.indexOf(' ')
        val cmd = if (sp < 0) line else line.substring(0, sp)
        val arg = if (sp < 0) "" else line.substring(sp + 1)
        when (cmd) {
            "k" -> press(arg.trim().toInt())
            "ctrl" -> {
                val code = arg.trim().toInt()
                val meta = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
                send(KeyEvent.ACTION_DOWN, CTRL_LEFT, meta)
                press(code, meta)
                send(KeyEvent.ACTION_UP, CTRL_LEFT, 0)
            }
            "s" -> {
                val evs = kcm.getEvents(arg.toCharArray()) ?: return
                for (ev in evs) send(ev.action, ev.keyCode, ev.metaState)
            }
        }
    }
}
