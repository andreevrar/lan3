package app.tvremote

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.Gravity
import android.widget.TextView

/**
 * Окно «Говорите в телефон» для приложений, которые вызывают голосовой ввод через RecognizerIntent
 * (а не через SpeechRecognizer). Включено, только если в приложении включён голосовой ввод через телефон.
 */
class PhoneVoiceActivity : Activity() {
    private lateinit var view: TextView
    private var done = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        EventLog.init(this)
        EventLog.add("голос: открыто окно RecognizerIntent")
        view = TextView(this)
        view.textSize = 28f
        view.gravity = Gravity.CENTER
        view.setTextColor(0xFFFFFFFF.toInt())
        view.setBackgroundColor(0xFF101418.toInt())
        view.setPadding(60, 60, 60, 60)
        view.text = "Говорите в телефон…\n(откройте пульт на телефоне)"
        setContentView(view)

        ControlService.ensure(applicationContext, "голосовой ввод")
        val lang = intent?.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE)
        VoiceBridge.start(object : VoiceSink {
            override fun ready() {}
            override fun begin() {
                view.text = "Слушаю телефон…"
            }
            override fun end() {}
            override fun partial(text: String) {
                view.text = text
            }
            override fun result(text: String) {
                if (done) return
                done = true
                val i = Intent()
                i.putStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS, arrayListOf(text))
                setResult(RESULT_OK, i)
                finish()
            }
            override fun error(code: Int, why: String) {
                if (done) return
                done = true
                setResult(
                    if (code == SpeechRecognizer.ERROR_NO_MATCH || code == SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
                        RecognizerIntent.RESULT_NO_MATCH else RESULT_CANCELED
                )
                finish()
            }
        }, lang)
    }

    override fun onBackPressed() {
        done = true
        VoiceBridge.cancel()
        super.onBackPressed()
    }

    override fun onDestroy() {
        if (!done) VoiceBridge.cancel()
        super.onDestroy()
    }
}
