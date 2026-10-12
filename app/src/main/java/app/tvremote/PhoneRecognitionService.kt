package app.tvremote

import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionService
import android.speech.RecognizerIntent

/**
 * «Служба распознавания речи», которая слушает не микрофон ТВ, а микрофон телефона.
 * Приложения на ТВ, использующие обычное распознавание речи Android (SpeechRecognizer),
 * получат текст с телефона. Включается кнопкой в приложении (системная настройка «голосовой ввод»).
 */
class PhoneRecognitionService : RecognitionService() {

    private class CbSink(val c: Callback) : VoiceSink {
        override fun ready() = c.readyForSpeech(Bundle())
        override fun begin() = c.beginningOfSpeech()
        override fun end() = c.endOfSpeech()
        override fun partial(text: String) = c.partialResults(VoiceBridge.bundle(text))
        override fun result(text: String) = c.results(VoiceBridge.bundle(text))
        override fun error(code: Int, why: String) = c.error(code)
    }

    override fun onStartListening(recognizerIntent: Intent?, listener: Callback) {
        EventLog.init(this)
        EventLog.add("голос: SpeechRecognizer вызвал нашу службу")
        ControlService.ensure(applicationContext, "голосовой ввод")
        val lang = recognizerIntent?.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE)
        VoiceBridge.start(CbSink(listener), lang)
    }

    override fun onStopListening(listener: Callback?) {
        VoiceBridge.stop()
    }

    override fun onCancel(listener: Callback?) {
        VoiceBridge.cancel()
    }
}
