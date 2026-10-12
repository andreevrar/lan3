package app.tvremote

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import org.json.JSONObject

/**
 * Голосовой ввод через микрофон телефона.
 *
 * Когда приложение на ТВ просит распознать речь (SpeechRecognizer), [PhoneRecognitionService] зовёт сюда:
 * мы шлём странице на телефоне {"t":"listen"}, телефон слушает и распознаёт речь, присылает
 * {"t":"voice","id":…,"v":"текст","final":true}, а мы отдаём текст приложению на ТВ как результат распознавания.
 * Всё в главном потоке, чтобы не было гонок.
 */
object VoiceBridge {
    private val main = Handler(Looper.getMainLooper())
    private var cb: RecognitionService.Callback? = null
    private var id = 0
    private var began = false
    private var timeout: Runnable? = null

    @Volatile var last: String = "ещё не использовали"

    private fun bundle(text: String): Bundle {
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
        cb = null
        began = false
    }

    private fun fail(code: Int, why: String) {
        val c = cb
        val wasId = id
        clear()
        last = "ошибка: $why"
        try {
            c?.error(code)
        } catch (_: Throwable) {
        }
        ControlService.pages(JSONObject().put("t", "listen_cancel").put("id", wasId).toString())
    }

    fun start(c: RecognitionService.Callback, lang: String?) {
        main.post {
            if (cb != null) clear()
            if (Status.clients == 0) {
                last = "ошибка: пульт на телефоне не открыт"
                try {
                    c.error(SpeechRecognizer.ERROR_NETWORK)
                } catch (_: Throwable) {
                }
                return@post
            }
            cb = c
            id++
            began = false
            last = "слушает телефон…"
            try {
                c.readyForSpeech(Bundle())
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
            if (cb == null) return@post
            ControlService.pages(JSONObject().put("t", "listen_stop").put("id", id).toString())
            armTimeout(8000)
        }
    }

    fun cancel() {
        main.post {
            if (cb == null) return@post
            val wasId = id
            clear()
            last = "отменено"
            ControlService.pages(JSONObject().put("t", "listen_cancel").put("id", wasId).toString())
        }
    }

    /** Сообщение от страницы на телефоне. */
    fun fromPhone(j: JSONObject) {
        main.post {
            val c = cb ?: return@post
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
                    c.beginningOfSpeech()
                }
                if (j.optBoolean("final", false)) {
                    c.endOfSpeech()
                    clear()
                    last = "ок: «" + (if (text.length > 30) text.take(30) + "…" else text) + "»"
                    c.results(bundle(text))
                } else {
                    armTimeout(40000)
                    c.partialResults(bundle(text))
                }
            } catch (_: Throwable) {
            }
        }
    }
}
