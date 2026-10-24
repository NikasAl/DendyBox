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
 * Интервал одного шага на шкале времени: набор кнопок [buttons] зажат
 * от [startMs] до [endMs]. Единый источник правды для исполнения
 * ([ComboTimeline.timeline]) и наглядной хронологии в конфигураторе
 * (полосы «старт + длительность»).
 */
data class ComboSegment(
    val index: Int,
    val buttons: List<String>,
    val startMs: Long,
    val endMs: Long
)

/**
 * Исполнитель программируемых комбо-кнопок: превращает последовательность
 * шагов в нажатия/отпускания через ТОТ ЖЕ путь ввода, что и физические
 * кнопки ([InputState.press] — эмулятор читает биты в первом же кадре).
 *
 * Ритм: каждый шаг нажимает СВОЙ набор кнопок (одновременно — см. [ComboStep.with])
 * и ДЕРЖИТ его [ComboStep.holdMs] мс (по умолчанию 100 — прежний «клик»);
 * press-and-hold, а не повторные клики. Пауза шага отсчитывается от ОТПУСКАНИЯ
 * предыдущего шага (как раньше) или от его НАЖАТИЯ ([ComboStep.fromPress]) —
 * так шаги перекрываются во времени. «Суперудар» (A, пауза 100, B): A 0–100,
 * B 200–300. Перекрывающийся сценарий: A держится 500 (0–500); B со сдвигом
 * +50 от нажатия A и удержанием 500 (50–550).
 *
 * Повторное нажатие комбо-кнопки ПЕРЕзапускает её последовательность с
 * начала. Отмена (уход с экрана, пауза) гарантированно отжимает все биты,
 * которые успели нажаться, — залипания невозможны.
 *
 * [timeline] — чистая функция (покрыта юнит-тестами с явным временем);
 * [ComboRunner.play] исполняет её через delay() главной корутины (в тестах
 * задержка подменяется через параметр [ComboRunner.play]).
 */
object ComboTimeline {

    /**
     * Интервалы шагов: когда начинается каждый набор и сколько держится.
     * Пауза первого шага — от нажатия комбо-кнопки; у остальных — от
     * ОТПУСКАНИЯ предыдущего шага или от его НАЖАТИЯ ([ComboStep.fromPress]).
     * Шаги могут перекрываться во времени (fromPress) — сегменты это
     * показывают напрямую.
     */
    fun segments(steps: List<ComboStep>): List<ComboSegment> {
        val out = ArrayList<ComboSegment>(steps.size)
        var prevStart = 0L
        var prevEnd = 0L
        var first = true
        steps.forEachIndexed { i, s ->
            val start = when {
                first -> s.delayMs.toLong()               // от нажатия комбо-кнопки
                s.fromPress -> prevStart + s.delayMs      // от НАЖАТИЯ предыдущего шага
                else -> prevEnd + s.delayMs               // от ОТПУСКАНИЯ предыдущего шага
            }
            val end = start + s.holdMs
            out.add(ComboSegment(i, listOf(s.btn) + s.with, start, end))
            prevStart = start
            prevEnd = end
            first = false
        }
        return out
    }

    /**
     * Таймлайн последовательности: точные моменты (мс) от нажатия комбо-кнопки,
     * ОТСОРТИРОВАННЫЕ по времени. Перекрывающиеся шаги (fromPress) порождают
     * события не по порядку генерации — стабильная сортировка выстраивает их
     * по времени, сохраняя порядок при равных tMs (отжатие раньше нажатия —
     * как в старой сетке). Неизвестная кнопка в шаге — IllegalArgumentException
     * (валидация выполняется раньше, в ComboJson).
     */
    fun timeline(steps: List<ComboStep>): List<ComboEvent> {
        val events = ArrayList<ComboEvent>(steps.size * 2)
        for (seg in segments(steps)) {
            for (b in seg.buttons) {
                val bit = bitFor(b) ?: throw IllegalArgumentException("Кнопка не поддержана: $b")
                events.add(ComboEvent(seg.startMs, bit, true))
                events.add(ComboEvent(seg.endMs, bit, false))
            }
        }
        return events.sortedBy { it.tMs }
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

    suspend fun play(btn: ComboButton, port: Int, sleep: suspend (Long) -> Unit = { delay(it) }) {
        val held = HashSet<Int>()
        try {
            var prev = 0L
            for (e in ComboTimeline.timeline(btn.steps)) {
                sleep(e.tMs - prev)
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
