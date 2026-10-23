package com.dendybox.app.input

import com.dendybox.app.ui.controls.ComboButton
import com.dendybox.app.ui.controls.ComboStep
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Событие таймлайна комбо: в момент [tMs] от нажатия комбо-кнопки бит [bit]
 * джойстика переводится в состояние [down].
 */
data class ComboEvent(val tMs: Long, val bit: Int, val down: Boolean)

/**
 * Исполнитель программируемых комбо-кнопок: превращает последовательность
 * шагов в нажатия/отпускания через ТОТ ЖЕ путь ввода, что и физические
 * кнопки ([InputState.press] — эмулятор читает биты в первом же кадре).
 *
 * Ритм (шаг задержки 100 мс): нажать кнопку → держать [HOLD_MS] → отпустить →
 * пауза шага → следующая кнопка. То есть пауза шага «A +100 мс · B» — это
 * время между отпусканием A и нажатием B; «Суперудар» (A, пауза 100, B)
 * занимает 300 мс от нажатия A до отпускания B.
 *
 * Повторное нажатие комбо-кнопки ПЕРЕзапускает её последовательность с
 * начала. Отмена (уход с экрана, пауза) гарантированно отжимает все биты,
 * которые успели нажаться, — залипания невозможны.
 *
 * [timeline] — чистая функция (покрыта юнит-тестами с явным временем);
 * [ComboRunner.play] исполняет её через delay() главной корутины.
 */
object ComboTimeline {

    /** Сколько мс каждая кнопка держится нажатой. */
    const val HOLD_MS = 100L

    /**
     * Таймлайн последовательности: точная сетка моментов (мс) от нажатия
     * комбо-кнопки. Неизвестная кнопка в шаге — IllegalArgumentException
     * (валидация выполняется раньше, в ComboJson).
     */
    fun timeline(steps: List<ComboStep>, holdMs: Long = HOLD_MS): List<ComboEvent> {
        val events = ArrayList<ComboEvent>(steps.size * 2)
        var t = 0L
        for (s in steps) {
            t += s.delayMs
            val bit = bitFor(s.btn) ?: throw IllegalArgumentException("Кнопка не поддержана: ${s.btn}")
            events.add(ComboEvent(t, bit, true))
            t += holdMs
            events.add(ComboEvent(t, bit, false))
        }
        return events
    }

    /** Бит InputState для имени кнопки шага (стрелки, A, B, START, SELECT). */
    fun bitFor(btn: String): Int? = when (btn) {
        "A" -> InputState.A
        "B" -> InputState.B
        "START" -> InputState.START
        "SELECT" -> InputState.SELECT
        "UP" -> InputState.UP
        "DOWN" -> InputState.DOWN
        "LEFT" -> InputState.LEFT
        "RIGHT" -> InputState.RIGHT
        else -> null
    }
}

/**
 * Один исполняющий экземпляр на игровой экран: [start] по id кнопки из
 * [buttons] (эффективный набор: пресеты игры + пользовательские), порт —
 * чей джойстик (P1 или сторона в netplay).
 */
class ComboRunner(
    private val scope: CoroutineScope,
    private val buttons: () -> List<ComboButton>
) {
    private val jobs = HashMap<String, Job>()

    /** Нажата комбо-кнопка [id] — (пере)запустить проигрывание. Отпускание игнорируется. */
    fun start(id: String, port: Int) {
        val btn = buttons().firstOrNull { it.id == id } ?: return
        if (btn.steps.isEmpty()) return
        jobs.remove(id)?.cancel()
        jobs[id] = scope.launch { play(btn, port) }
    }

    /** Отменить все проигрывания (уход с экрана/пауза) с отжатием нажатых битов. */
    fun cancelAll() {
        jobs.values.forEach { it.cancel() }
        jobs.clear()
    }

    suspend fun play(btn: ComboButton, port: Int) {
        val held = HashSet<Int>()
        try {
            var prev = 0L
            for (e in ComboTimeline.timeline(btn.steps)) {
                delay(e.tMs - prev)
                prev = e.tMs
                if (e.down) held.add(e.bit) else held.remove(e.bit)
                InputState.press(e.bit, e.down, port)
            }
        } finally {
            // Отмена посреди шага не оставляет кнопку нажатой
            for (b in held) InputState.press(b, false, port)
        }
    }
}
