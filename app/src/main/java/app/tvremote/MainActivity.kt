package app.tvremote

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.graphics.Bitmap
import android.graphics.Color
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

class MainActivity : Activity() {

    private lateinit var addrView: TextView
    private lateinit var codeView: TextView
    private lateinit var qrView: ImageView
    private var qrFor = ""
    private lateinit var infoView: TextView
    private lateinit var voiceBtn: Button
    private fun voiceOn() = getSharedPreferences("tvremote", MODE_PRIVATE).getBoolean("voice_on", false)
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(40), dp(28), dp(40), dp(28))
        root.setBackgroundColor(0xFF101418.toInt())

        root.addView(label("ТВ-пульт", 30f, 0xFFFFFFFF.toInt()))
        root.addView(label("1. Откройте в браузере телефона (он должен быть в том же Wi-Fi):", 18f, 0xFF9AA4AF.toInt()))
        addrView = label("", 36f, 0xFFFFFFFF.toInt())
        root.addView(addrView)
        root.addView(label("2. Введите код:", 18f, 0xFF9AA4AF.toInt()))
        codeView = label("", 44f, 0xFF4DA3FF.toInt())
        root.addView(codeView)
        root.addView(label("Или наведите камеру телефона на QR-код:", 18f, 0xFF9AA4AF.toInt()))
        qrView = ImageView(this)
        qrView.layoutParams = LinearLayout.LayoutParams(dp(220), dp(220))
        root.addView(qrView)
        infoView = label("", 18f, 0xFFDDE3EA.toInt())
        root.addView(infoView)

        val first = button("Перезапустить службу") { restartService(false) }
        root.addView(first)
        root.addView(button("Включить спец. возможности через ADB") { enableAccessibility() })
        root.addView(button("Открыть настройки спец. возможностей") { openAccessibilitySettings() })
        voiceBtn = button("") { toggleVoice() }
        root.addView(voiceBtn)
        root.addView(button("Сгенерировать новый код") { restartService(true) })

        val scroll = ScrollView(this)
        scroll.addView(root)
        setContentView(scroll)
        first.requestFocus()

        startControl()
    }

    override fun onResume() {
        super.onResume()
        handler.post(tick)
    }

    override fun onPause() {
        handler.removeCallbacks(tick)
        super.onPause()
    }

    private fun label(text: String, sizeSp: Float, color: Int): TextView {
        val tv = TextView(this)
        tv.text = text
        tv.textSize = sizeSp
        tv.setTextColor(color)
        val pad = (6 * resources.displayMetrics.density).toInt()
        tv.setPadding(0, pad, 0, pad)
        return tv
    }

    private fun button(text: String, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = text
        b.isFocusable = true
        b.setOnClickListener { onClick() }
        return b
    }

    private fun refresh() {
        voiceBtn.text = if (voiceOn()) "Голос с телефона: ВКЛЮЧЁН (нажмите, чтобы выключить)"
        else "Включить голосовой ввод через микрофон телефона"
        val code = Config.pretty(Config.code(this))
        codeView.text = code
        val ips = Net.localIps()
        val port = if (Status.port > 0) Status.port else WebServer.FIRST_PORT
        addrView.text = if (ips.isEmpty()) "нет сети: подключите ТВ к Wi-Fi"
        else ips.joinToString("\n") { Net.url(it, port) }
        updateQr(if (ips.isEmpty()) "" else Net.url(ips[0], port) + "/#" + Config.code(this))
        val acc = if (RemoteAccessibilityService.instance != null) "включены" else "выключены"
        val adb = if (Status.adbOk) "подключено"
        else "нет (не обязательно: громкость и медиа работают без него)"
        infoView.text = "Сервер: " + Status.server +
            (if (Status.port > 0) " (порт " + Status.port + ")" else "") + "\n" +
            "Подключено телефонов: " + Status.clients + "\n" +
            "ADB: " + adb + "\n" +
            "Быстрые кнопки: " + (if (Status.fast) "да" else "нет (пока запускается или недоступно)") + "\n" +
            "Спец. возможности: " + acc + "\n" +
            "Последняя команда: " + Status.lastCmd + "\n" +
            "Голос с телефона: " + VoiceBridge.last + "\n" +
            "Ввод текста: " + Status.lastText + "\n" +
            "Сторож в системе ТВ: " + (if (Status.supervisor) "да" else "нет (нужен ADB)") + "\n" +
            "Запущено: " + (if (Status.startedAt > 0)
                java.text.SimpleDateFormat("dd.MM HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(Status.startedAt))
            else "—") + " — " + Status.startReason + "\n" +
            "Последняя проверка: " + Status.lastWake + ", перезапусков сервера: " + Status.webRestarts
    }

    /** Рисует QR со ссылкой вида http://ip:порт/#код; перерисовывает только при смене содержимого. */
    private fun updateQr(content: String) {
        if (content == qrFor) return
        qrFor = content
        if (content.isEmpty()) {
            qrView.setImageDrawable(null)
            return
        }
        try {
            val hints = mapOf(EncodeHintType.MARGIN to 2)
            val m = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 440, 440, hints)
            val px = IntArray(m.width * m.height)
            for (y in 0 until m.height) for (x in 0 until m.width) {
                px[y * m.width + x] = if (m.get(x, y)) Color.BLACK else Color.WHITE
            }
            qrView.setImageBitmap(Bitmap.createBitmap(px, m.width, m.height, Bitmap.Config.ARGB_8888))
        } catch (_: Throwable) {
            qrView.setImageDrawable(null)
        }
    }

    private fun startControl() {
        val svc = Intent(this, ControlService::class.java)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(svc) else startService(svc)
    }

    private fun restartService(newCode: Boolean) {
        stopService(Intent(this, ControlService::class.java))
        if (newCode) Config.regenerate(this)
        Status.server = "перезапуск…"
        startControl()
        refresh()
    }

    private fun enableAccessibility() {
        val appCtx = applicationContext
        Thread {
            val ok = try {
                AdbClient(appCtx).enableAccessibility()
            } catch (_: Throwable) {
                false
            }
            runOnUiThread {
                Toast.makeText(
                    this,
                    if (ok) "Спец. возможности включены" else "Не вышло: нет доступа к ADB",
                    Toast.LENGTH_LONG
                ).show()
            }
        }.start()
    }

    private fun toggleVoice() {
        val appCtx = applicationContext
        val want = !voiceOn()
        Thread {
            val ok = try {
                AdbClient(appCtx).setVoice(want)
            } catch (_: Throwable) {
                false
            }
            runOnUiThread {
                Toast.makeText(
                    this,
                    if (!ok) "Не вышло: нет доступа к ADB"
                    else if (want) "Готово: голосовой поиск в приложениях теперь слушает телефон"
                    else "Голосовой ввод вернулся к настройкам ТВ",
                    Toast.LENGTH_LONG
                ).show()
                refresh()
            }
        }.start()
    }

    private fun openAccessibilitySettings() {
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } catch (_: Throwable) {
            Toast.makeText(this, "На этом ТВ нет такого экрана — используйте кнопку выше", Toast.LENGTH_LONG).show()
        }
    }
}
