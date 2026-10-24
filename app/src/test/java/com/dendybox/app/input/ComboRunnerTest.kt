package com.dendybox.app.input

import com.dendybox.app.ui.controls.ComboButton
import com.dendybox.app.ui.controls.ComboStep
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Исполнение таймлайна [ComboRunner.play] через реальный путь ввода
 * (InputState — чистый JVM-объект):
 *  * удержание — press-and-hold: кнопка зажата ВЕСЬ holdMs (а не клик),
 *    перекрывающиеся шаги зажимают свои наборы одновременно;
 *  * отмена посреди удержания гарантированно отжимает нажатые биты.
 *
 * Время в первом тесте виртуальное: sleep-подмена play копит сдвиги
 * задержек без реального ожидания и снимает состояние порта перед каждым
 * событием; во втором — реальный delay + отмена корутины.
 */
class ComboRunnerTest {

    @Test
    fun `удержание — A зажата весь hold, B стартует со сдвигом и держится параллельно`() =
        runBlocking {
            InputState.clearAll()
            // Целевой сценарий: A зажата 500 мс; через 50 мс после нажатия A
            // зажимается B и тоже держится 500 мс
            val btn = ComboButton(
                "t", "Сценарий",
                listOf(
                    ComboStep("A", 0, holdMs = 500),
                    ComboStep("B", 50, holdMs = 500, fromPress = true)
                )
            )
            var t = 0L
            val samples = ArrayList<Pair<Long, Int>>()
            ComboRunner(this, { emptyList() }).play(btn, 0) { dt ->
                t += dt
                samples += t to InputState.raw(0)
            }

            // 4 события — 4 замера: состояние ПЕРЕД каждым событием
            assertEquals(4, samples.size)
            // t=0, перед нажатием A — порт пуст
            assertEquals(0L to 0, samples[0])
            // t=50, перед нажатием B: A ВСЁ ЕЩЁ зажата — press-and-hold, а не клик
            assertEquals(50L to InputState.A, samples[1])
            // t=500, перед отпусканием A: A и B зажаты одновременно
            assertEquals(500L to (InputState.A or InputState.B), samples[2])
            // t=550, перед отпусканием B: B пережила отпускание A
            assertEquals(550L to InputState.B, samples[3])
            // В конце ни одна кнопка не залипла
            assertEquals(0, InputState.raw(0))
        }

    @Test
    fun `одновременные кнопки шага зажимаются вместе и отжимаются вместе`() = runBlocking {
        InputState.clearAll()
        val btn = ComboButton(
            "t", "Вместе",
            listOf(ComboStep("A", 0, with = listOf("B"), holdMs = 500))
        )
        var t = 0L
        val samples = ArrayList<Pair<Long, Int>>()
        ComboRunner(this, { emptyList() }).play(btn, 0) { dt ->
            t += dt
            samples += t to InputState.raw(0)
        }
        // t=500, перед отпусканием: обе зажаты
        assertEquals(500L to (InputState.A or InputState.B), samples[2])
        assertEquals(0, InputState.raw(0))
    }

    @Test
    fun `отмена посреди удержания гарантированно отжимает кнопки`() = runBlocking {
        InputState.clearAll()
        val btn = ComboButton(
            "t", "Долго",
            listOf(ComboStep("A", 0, holdMs = 500))
        )
        val job = launch { ComboRunner(this, { emptyList() }).play(btn, 0) }
        // Ждём фактического нажатия A (реальное время, поллинг)
        var guard = 0
        while (InputState.raw(0) == 0 && guard++ < 400) delay(5)
        assertEquals(InputState.A, InputState.raw(0))
        // Отмена (как пауза/уход с экрана) — A обязана отжаться
        job.cancelAndJoin()
        assertEquals(0, InputState.raw(0))
    }
}
