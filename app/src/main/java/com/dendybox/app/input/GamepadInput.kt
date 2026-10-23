package com.dendybox.app.input

import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import androidx.compose.runtime.snapshots.SnapshotStateMap

/**
 * Клей: сырые MotionEvent активности → [TouchRouterCore] → InputState.
 *
 * ВАЖНО: маршрутизация экранных кнопок (A/B/Select/Start/турбо) идёт МИМО
 * Compose — MainActivity.dispatchTouchEvent вызывает [onMotionEvent] ДО
 * раздачи событий в дерево. Сырое событие содержит все пальцы, поэтому:
 *  - A+B двумя пальцами работают гарантированно (оба пальца в каждом событии);
 *  - press-событие уходит в InputState синхронно с диспетчем — эмулятор
 *    читает его в первом же кадре (никаких задержек/гашения быстрых серий).
 *
 * «Пятно касания» (ко-нажатие двух кнопок по эллипсу контакта) УДАЛЕНО:
 * AXIS_TOUCH_MAJOR на устройствах недостоверен (0 в момент касания /
 * нереальные значения, пока контакт формируется) — пятно давало «нажалась
 * и сразу отжалась», что выглядело как неработающее ко-нажатие и запаздывающая
 * подсветка. Нажатие — только ЦЕНТРОМ пальца.
 *
 * Крестовина, верхнее меню и редактор раскладки продолжают обрабатываться
 * Compose-обработчиками как раньше: этот класс НЕ потребляет и не блокирует
 * события — только попутно нажимает свои кнопки по координатам.
 *
 * Жизненный цикл: ControlsLayer (игровой режим) привязывает/отвязывает свои
 * группы через [bindGroup]/[unbindGroup] (DisposableEffect) — ушедший с
 * экрана слой автоматически отжимает свои кнопки. Пока групп нет (меню,
 * пауза, редактор), [onMotionEvent] стоит один пустой вызов.
 *
 * Все вызовы — с главного потока. Диагностика в logcat (tag «DendyBox»):
 *  - «touch:» — каждый НОВЫЙ палец: координата, кнопка под ним, dt доставки;
 *  - «adopt:» — палец взят в работу движением (его касание не дошло до ядра);
 *  - «slow:»  — событие, доставленное приложению дольше чем за [SLOW_MS] мс:
 *    диспетч здесь синхронный, значит задержка — ВЫШЕ по стеку (система/
 *    драйвер); если «медленный» второй палец существует, это видно сразу;
 *  - «press:» — каждое нажатие/отжатие: время события и dt доставки.
 */
object GamepadInput {
    private const val TAG = "DendyBox"

    /** Порог «подозрительной» задержки доставки события, мс. */
    private const val SLOW_MS = 30L

    private val core = TouchRouterCore(onAdopt = { pid, group, button, tMs ->
        Log.d(TAG, "adopt: pid=$pid -> $group[$button] t=$tMs (касание не дошло до ядра)")
    })

    // Подсветка нажатых кнопок по группам (SnapshotStateMap — рекомпозиция).
    private val pressedByGroup = HashMap<String, SnapshotStateMap<String, Boolean>>()

    init {
        core.onPress { group, button, down, port, tMs ->
            pressedByGroup[group]?.put(button, down)
            handlePress(button, down, port)
            // dt — задержка доставки: от времени события до момента обработки
            // (диспетч синхронный, обычно 1-3 мс; скачки — повод смотреть,
            // куда «пропадают» быстрые нажатия)
            val dt = SystemClock.uptimeMillis() - tMs
            Log.d(TAG, "press: $button ${if (down) "DOWN" else "UP"} port=$port t=$tMs dt=${dt}ms")
        }
    }

    /**
     * Обработчик программируемых комбо-кнопок (id БЕЗ префикса «K»):
     * выставляется игровым экраном; down — запустить проигрывание
     * последовательности, up — игнорируется (комбо доигрывается до конца).
     */
    var onComboPress: ((id: String, down: Boolean, port: Int) -> Unit)? = null

    /** Кнопка [id] нажата на порту [port] (турбо — только P1). */
    private fun handlePress(id: String, down: Boolean, port: Int) {
        when {
            id == "A" -> InputState.press(InputState.A, down, port)
            id == "B" -> InputState.press(InputState.B, down, port)
            id == "TA" -> if (port == 0) InputState.turboActiveA = down
            id == "TB" -> if (port == 0) InputState.turboActiveB = down
            id == "SELECT" -> InputState.press(InputState.SELECT, down, port)
            id == "START" -> InputState.press(InputState.START, down, port)
            // «K<u…>»/«Kp<N>» — программируемая комбо-кнопка (см. ComboStore)
            id.length > 1 && id[0] == 'K' -> onComboPress?.invoke(id.substring(1), down, port)
        }
    }

    /**
     * Привязать группу кнопок: [hitTest] — геометрия в координатах окна
     * (та же математика, что у отрисовки кнопок), [port] — джойстик,
     * [pressed] — карта подсветки (Compose-наблюдаемая).
     */
    fun bindGroup(
        gid: String,
        hitTest: (Float, Float) -> String?,
        port: () -> Int,
        pressed: SnapshotStateMap<String, Boolean>
    ) {
        pressedByGroup[gid] = pressed
        core.bindGroup(gid, hitTest, port)
        Log.d(TAG, "touchbind: $gid")
    }

    fun unbindGroup(gid: String) {
        if (core.unbindGroup(gid)) {
            pressedByGroup.remove(gid)
            Log.d(TAG, "touchunbind: $gid")
        }
    }

    /** Обработать сырое событие (вызывается из dispatchTouchEvent ДО Compose). */
    fun onMotionEvent(ev: MotionEvent) {
        if (!core.hasGroups()) return
        val t = ev.eventTime
        val dt = SystemClock.uptimeMillis() - t
        if (dt > SLOW_MS) {
            // Событие дошло до приложения с запозданием: наш диспетч
            // синхронный, значит источник задержки — выше по стеку
            // (система/драйвер). Лог для любого типа события.
            Log.w(TAG, "slow: ${actionName(ev.actionMasked)} dt=${dt}ms pc=${ev.pointerCount}")
        }
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = ev.actionIndex
                logTouch(ev, i, dt)
                core.down(ev.getPointerId(i).toLong(), ev.getX(i), ev.getY(i), t)
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until ev.pointerCount) {
                    core.move(ev.getPointerId(i).toLong(), ev.getX(i), ev.getY(i), t)
                }
            }
            MotionEvent.ACTION_POINTER_UP -> core.up(ev.getPointerId(ev.actionIndex).toLong(), t)
            // UP — подняты все пальцы; CANCEL — жест забрала система
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> core.liftAll(t)
        }
    }

    /** Диагностика нового пальца: координата, кнопка под ним, dt доставки. */
    private fun logTouch(ev: MotionEvent, i: Int, dt: Long) {
        val x = ev.getX(i)
        val y = ev.getY(i)
        val info = core.probe(x, y)
        Log.d(
            TAG,
            "touch: pid=${ev.getPointerId(i)} (${x.toInt()},${y.toInt()}) " +
                "dt=${dt}ms -> ${info.group ?: "мимо"}" +
                (info.button?.let { "[$it]" } ?: "")
        )
    }

    private fun actionName(action: Int) = when (action) {
        MotionEvent.ACTION_DOWN -> "DOWN"
        MotionEvent.ACTION_POINTER_DOWN -> "POINTER_DOWN"
        MotionEvent.ACTION_MOVE -> "MOVE"
        MotionEvent.ACTION_POINTER_UP -> "POINTER_UP"
        MotionEvent.ACTION_UP -> "UP"
        MotionEvent.ACTION_CANCEL -> "CANCEL"
        else -> "ACTION_$action"
    }
}
