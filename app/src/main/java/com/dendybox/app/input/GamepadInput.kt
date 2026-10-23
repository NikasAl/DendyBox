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
 *    читает его в первом же кадре (никаких задержек/гашения быстрых серий);
 *  - геометрия контакта (AXIS_TOUCH_MAJOR) читается из ТОГО ЖЕ события —
 *    устаревание данных исключено по построению.
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
 * Все вызовы — с главного потока. Логи: tag «DendyBox».
 */
object GamepadInput {
    private const val TAG = "DendyBox"
    private val core = TouchRouterCore()

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

    /** Кнопка [id] нажата на порту [port] (турбо — только P1). */
    private fun handlePress(id: String, down: Boolean, port: Int) {
        when (id) {
            "A" -> InputState.press(InputState.A, down, port)
            "B" -> InputState.press(InputState.B, down, port)
            "TA" -> if (port == 0) InputState.turboActiveA = down
            "TB" -> if (port == 0) InputState.turboActiveB = down
            "SELECT" -> InputState.press(InputState.SELECT, down, port)
            "START" -> InputState.press(InputState.START, down, port)
        }
    }

    /**
     * Привязать группу кнопок: [hitTest]/[patchTest] — геометрия в
     * координатах окна (та же математика, что у отрисовки кнопок), [port] —
     * джойстик, [pressed] — карта подсветки (Compose-наблюдаемая).
     */
    fun bindGroup(
        gid: String,
        hitTest: (Float, Float) -> String?,
        patchTest: ((Float, Float, Float) -> Set<String>)?,
        port: () -> Int,
        pressed: SnapshotStateMap<String, Boolean>
    ) {
        pressedByGroup[gid] = pressed
        core.bindGroup(gid, hitTest, patchTest, port)
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
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = ev.actionIndex
                logTouch(ev, i)
                core.down(ev.getPointerId(i).toLong(), ev.getX(i), ev.getY(i), touchMajor(ev, i), t)
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until ev.pointerCount) {
                    core.move(ev.getPointerId(i).toLong(), ev.getX(i), ev.getY(i), touchMajor(ev, i), t)
                }
            }
            MotionEvent.ACTION_POINTER_UP -> core.up(ev.getPointerId(ev.actionIndex).toLong(), t)
            // UP — подняты все пальцы; CANCEL — жест забрала система
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> core.liftAll(t)
        }
    }

    private fun touchMajor(ev: MotionEvent, i: Int): Float =
        ev.getAxisValue(MotionEvent.AXIS_TOUCH_MAJOR, i)

    /** Диагностика нового пальца: координата, радиусы эллипса, что накрыто. */
    private fun logTouch(ev: MotionEvent, i: Int) {
        val x = ev.getX(i)
        val y = ev.getY(i)
        val major = touchMajor(ev, i)
        val minor = ev.getAxisValue(MotionEvent.AXIS_TOUCH_MINOR, i)
        val info = core.probe(x, y, major)
        Log.d(
            TAG,
            "touch: pid=${ev.getPointerId(i)} (${x.toInt()},${y.toInt()}) " +
                "major=${"%.1f".format(major)} minor=${"%.1f".format(minor)} " +
                "patchR=${"%.1f".format(info.patchR)} -> ${info.group ?: "мимо"}${info.buttons}"
        )
    }
}
