package app.tvremote

import android.content.Intent
import android.speech.RecognitionService
import android.speech.RecognizerIntent

/**
 * «Служба распознавания речи», которая слушает не микрофон ТВ, а микрофон телефона.
 * Приложения на ТВ, использующие обычное распознавание речи Android, получат текст с телефона.
 * Включается кнопкой в приложении (системная настройка «голосовой ввод»).
 */
class PhoneRecognitionService : RecognitionService() {

    override fun onStartListening(recognizerIntent: Intent?, listener: Callback) {
        ControlService.ensure(applicationContext, "голосовой ввод")
        val lang = recognizerIntent?.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE)
        VoiceBridge.start(listener, lang)
    }

    override fun onStopListening(listener: Callback?) {
        VoiceBridge.stop()
    }

    override fun onCancel(listener: Callback?) {
        VoiceBridge.cancel()
    }
}
