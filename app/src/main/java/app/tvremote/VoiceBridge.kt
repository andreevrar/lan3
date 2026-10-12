package app.tvremote

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.SpeechRecognizer
import org.json.JSONObject

/** Кому отдаём результат распознавания: службе распознавания Android, экрану проверки или окну голосового ввода. */
interface VoiceSink {
    fun ready()
    fun begin()
    fun end()
    fun partial(text: String)
    fun result(text: String)
    fun error(code: Int, why: String)
}

/**
 * Голосовой ввод через микрофон телефона.
 *
 * Когда приложение на ТВ просит распознать речь, мы шлём странице на телефоне {"t":"listen"},
 * телефон слушает и распознаёт речь, присылает {"t":"voice","id":…,"v":"текст","final":true},
 * а мы отдаём текст приложению на ТВ как результат распознавания. Всё в главном потоке.
 */
object VoiceBridge {
    private val main = Handler(Looper.getMainLooper())
    private var sink: VoiceSink? = null
    private var id = 0
    private var began = false
    private var timeout: Runnable? = null

    @Volatile var last: String = "ещё не использовали"

    fun bundle(text: String): Bundle {
        val b = Bundle()
        b.putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(text))
        b.putFloatArray(SpeechRecognizer.CONFIDENCE_SCORES, floatArrayOf(1f))
        return b
    }

    private fun armTimeout(ms: Long) {
        timeout?.let { main.removeCallbacks(it) }
        val r = Runnable { fail(SpeechRecognizer.ERROR_SPEECH_TIMEOUT, "телефон не ответил") }
        timeout = r
        main.postDelayed(r, ms)
    }

    private fun clear() {
        timeout?.let { main.removeCallbacks(it) }
        timeout = null
        sink = null
        began = false
    }

    private fun fail(code: Int, why: String) {
        val c = sink
        val wasId = id
        clear()
        last = "ошибка: $why"
        EventLog.add("голос: $why")
        try {
            c?.error(code, why)
        } catch (_: Throwable) {
        }
        ControlService.pages(JSONObject().put("t", "listen_cancel").put("id", wasId).toString())
    }

    fun start(c: VoiceSink, lang: String?) {
        main.post {
            if (sink != null) clear()
            if (Status.clients == 0) {
                last = "ошибка: пульт на телефоне не открыт"
                EventLog.add("голос: запрос есть, но пульт на телефоне не открыт")
                try {
                    c.error(SpeechRecognizer.ERROR_NETWORK, "пульт на телефоне не открыт")
                } catch (_: Throwable) {
                }
                return@post
            }
            sink = c
            id++
            began = false
            last = "слушает телефон…"
            EventLog.add("голос: запрос от приложения, жду телефон")
            try {
                c.ready()
            } catch (_: Throwable) {
            }
            ControlService.pages(
                JSONObject().put("t", "listen").put("id", id).put("lang", lang ?: "ru-RU").toString()
            )
            armTimeout(40000)
        }
    }

    /** Приложение на ТВ закончило слушать: просим телефон выдать результат. */
    fun stop() {
        main.post {
            if (sink == null) return@post
            ControlService.pages(JSONObject().put("t", "listen_stop").put("id", id).toString())
            armTimeout(8000)
        }
    }

    fun cancel() {
        main.post {
            if (sink == null) return@post
            val wasId = id
            clear()
            last = "отменено"
            ControlService.pages(JSONObject().put("t", "listen_cancel").put("id", wasId).toString())
        }
    }

    /** Сообщение от страницы на телефоне. */
    fun fromPhone(j: JSONObject) {
        main.post {
            val c = sink ?: return@post
            if (j.optInt("id", -1) != id) return@post
            val err = j.optString("err")
            if (err.isNotEmpty()) {
                when (err) {
                    "no-speech" -> fail(SpeechRecognizer.ERROR_SPEECH_TIMEOUT, "ничего не услышали")
                    "cancel" -> fail(SpeechRecognizer.ERROR_CLIENT, "отменено на телефоне")
                    else -> fail(SpeechRecognizer.ERROR_NO_MATCH, err)
                }
                return@post
            }
            val text = j.optString("v").trim()
            if (text.isEmpty()) return@post
            try {
                if (!began) {
                    began = true
                    c.begin()
                }
                if (j.optBoolean("final", false)) {
                    c.end()
                    clear()
                    last = "ок: «" + (if (text.length > 30) text.take(30) + "…" else text) + "»"
                    EventLog.add("голос: получен текст с телефона")
                    c.result(text)
                } else {
                    armTimeout(40000)
                    c.partial(text)
                }
            } catch (_: Throwable) {
            }
        }
    }
}
