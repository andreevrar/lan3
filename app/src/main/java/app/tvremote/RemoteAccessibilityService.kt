package app.tvremote

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Запасной путь и ввод текста.
 * Ввод текста (в том числе кириллицы) идёт через ACTION_SET_TEXT — через ADB `input text` русские буквы не умеет.
 * Если ADB недоступен, служба ещё умеет Назад/Домой, двигать фокус и нажимать на выбранный элемент.
 */
class RemoteAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: RemoteAccessibilityService? = null

        const val TEXT_OK = 0
        const val TEXT_NO_FIELD = 1
        const val TEXT_NOT_APPLIED = 2
    }

    private val handler = Handler(Looper.getMainLooper())

    /**
     * Систему при включении ТВ сама запускает службы спец. возможностей, поэтому отсюда
     * надёжно стартует и основная служба (BOOT_COMPLETED на многих ТВ не приходит).
     * Сторож раз в 20 секунд проверяет, что она жива.
     */
    private val watchdog = object : Runnable {
        override fun run() {
            if (!ControlService.alive) ControlService.ensure(applicationContext, "служба спец. возможностей")
            KeepAlive.schedule(applicationContext)
            handler.postDelayed(this, 20000)
        }
    }

    override fun onServiceConnected() {
        instance = this
        super.onServiceConnected()
        handler.removeCallbacks(watchdog)
        handler.post(watchdog)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        handler.removeCallbacks(watchdog)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        handler.removeCallbacks(watchdog)
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    // ---------- глобальные действия ----------

    fun back(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)

    fun home(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)

    fun powerDialog(): Boolean = performGlobalAction(GLOBAL_ACTION_POWER_DIALOG)

    fun openSettings(): Boolean = try {
        val i = Intent(Settings.ACTION_SETTINGS)
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(i)
        true
    } catch (_: Throwable) {
        false
    }

    // ---------- фокус и клик ----------

    private fun focused(root: AccessibilityNodeInfo): AccessibilityNodeInfo? =
        root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)

    private fun firstFocusable(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (n.isFocusable && n.isVisibleToUser && n.childCount == 0) return n
        for (i in 0 until n.childCount) {
            val child = n.getChild(i) ?: continue
            val found = firstFocusable(child)
            if (found != null) return found
        }
        return null
    }

    /** direction: View.FOCUS_UP / DOWN / LEFT / RIGHT */
    fun move(direction: Int): Boolean {
        val root = rootInActiveWindow ?: return false
        val cur = focused(root)
        val target = if (cur != null) cur.focusSearch(direction) else firstFocusable(root)
        if (target != null) {
            if (target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)) return true
            if (target.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)) return true
        }
        // фокус двигать некуда — пробуем прокрутить список
        val forward = direction == View.FOCUS_DOWN || direction == View.FOCUS_RIGHT
        var n: AccessibilityNodeInfo? = cur ?: root
        while (n != null) {
            if (n.isScrollable) {
                return n.performAction(
                    if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                    else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                )
            }
            n = n.parent
        }
        return false
    }

    fun click(): Boolean {
        val root = rootInActiveWindow ?: return false
        var n = focused(root)
        while (n != null) {
            if (n.isClickable) return n.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            n = n.parent
        }
        return false
    }

    // ---------- ввод текста ----------

    private fun isInput(n: AccessibilityNodeInfo): Boolean =
        n.isEditable || (n.className?.toString()?.contains("EditText") == true)

    /** Видимые поля ввода внутри окна; поле с фокусом идёт первым. Скрытые поля других экранов не берём. */
    private fun collectInputs(n: AccessibilityNodeInfo, out: MutableList<AccessibilityNodeInfo>, depth: Int = 0) {
        if (depth > 40) return
        if (isInput(n) && n.isVisibleToUser) {
            if (n.isFocused) out.add(0, n) else out.add(n)
        }
        for (i in 0 until n.childCount) {
            val child = n.getChild(i) ?: continue
            collectInputs(child, out, depth + 1)
        }
    }

    private fun findInput(): AccessibilityNodeInfo? {
        val roots = ArrayList<AccessibilityNodeInfo>()
        rootInActiveWindow?.let { roots.add(it) }
        try {
            windows?.forEach { w -> w.root?.let { r -> roots.add(r) } }
        } catch (_: Throwable) {
        }
        for (r in roots) {
            val f = r.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (f != null && isInput(f)) return f
        }
        for (r in roots) {
            val list = ArrayList<AccessibilityNodeInfo>()
            try {
                collectInputs(r, list)
            } catch (_: Throwable) {
            }
            if (list.isNotEmpty()) return list[0]
        }
        return null
    }

    /** Текст активного поля ввода; null — поля нет или это пароль (сверить нельзя). */
    fun fieldText(): String? {
        val n = findInput() ?: return null
        if (n.isPassword) return null
        return currentText(n)
    }

    /** Выделить всё в активном поле и вставить из буфера обмена. */
    fun paste(): Boolean {
        val n = findInput() ?: return false
        try {
            n.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            val len = currentText(n).length
            val a = Bundle()
            a.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
            a.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, len)
            n.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, a)
            return n.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        } catch (_: Throwable) {
            return false
        }
    }

    private fun currentText(n: AccessibilityNodeInfo): String {
        try {
            n.refresh() // забрать свежее состояние, а не кэш
        } catch (_: Throwable) {
        }
        if (Build.VERSION.SDK_INT >= 26 && n.isShowingHintText) return ""
        return n.text?.toString() ?: ""
    }

    private fun setNodeText(n: AccessibilityNodeInfo, value: String): Boolean {
        val args = Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        return n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    /**
     * Дописать (append=true) или заменить текст в активном поле ввода и проверить, что поле его приняло.
     * Возвращает TEXT_OK, TEXT_NO_FIELD (поле ввода не найдено) или TEXT_NOT_APPLIED (поле проигнорировало текст).
     */
    fun setText(text: String, append: Boolean): Int {
        val node = findInput() ?: return TEXT_NO_FIELD
        val value = if (append) currentText(node) + text else text
        if (!setNodeText(node, value)) return TEXT_NOT_APPLIED
        for (i in 0 until 5) {
            try {
                Thread.sleep(60)
            } catch (_: InterruptedException) {
                break
            }
            val n = findInput() ?: continue
            if (n.isPassword) return TEXT_OK // в полях паролей текст скрыт, сверить нельзя
            if (currentText(n) == value) return TEXT_OK
        }
        return TEXT_NOT_APPLIED
    }

    /** Удалить последний символ в активном поле ввода. */
    fun deleteLast(): Boolean {
        val node = findInput() ?: return false
        val cur = currentText(node)
        if (cur.isEmpty()) return true
        return setNodeText(node, cur.dropLast(1))
    }
}
