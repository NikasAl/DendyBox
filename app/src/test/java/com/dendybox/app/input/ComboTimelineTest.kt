package com.dendybox.app.input

import com.dendybox.app.ui.controls.ComboStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Таймлайн комбо-кнопки: каждый шаг нажимает свой набор кнопок одновременно
 * и держит holdMs мс (по умолчанию 100), затем отпускает; пауза шага — от
 * отпускания предыдущего (или от его нажатия при fromPress). Проверяются
 * моменты событий в мс от нажатия комбо-кнопки (чистая функция, JVM).
 */
class ComboTimelineTest {

    private fun tl(vararg steps: ComboStep) = ComboTimeline.timeline(steps.toList())

    @Test
    fun `суперудар A пауза100 B даёт сетку 0-100-200-300`() {
        val events = tl(ComboStep("A", 0), ComboStep("B", 100))
        assertEquals(
            listOf(
                ComboEvent(0, InputState.A, true),
                ComboEvent(100, InputState.A, false),
                ComboEvent(200, InputState.B, true),
                ComboEvent(300, InputState.B, false)
            ),
            events
        )
    }

    @Test
    fun `нулевая пауза — следующая кнопка сразу после отпускания предыдущей`() {
        val events = tl(ComboStep("A", 0), ComboStep("B", 0))
        assertEquals(
            listOf(
                ComboEvent(0, InputState.A, true),
                ComboEvent(100, InputState.A, false),
                ComboEvent(100, InputState.B, true),
                ComboEvent(200, InputState.B, false)
            ),
            events
        )
    }

    @Test
    fun `ультра из пресета TMNT — стрелки и B по сетке`() {
        val events = tl(
            ComboStep("DOWN", 0), ComboStep("RIGHT", 100), ComboStep("B", 100)
        )
        assertEquals(
            listOf(
                ComboEvent(0, InputState.DOWN, true),
                ComboEvent(100, InputState.DOWN, false),
                ComboEvent(200, InputState.RIGHT, true),
                ComboEvent(300, InputState.RIGHT, false),
                ComboEvent(400, InputState.B, true),
                ComboEvent(500, InputState.B, false)
            ),
            events
        )
    }

    @Test
    fun `пауза 500 и направление мапятся в правильные биты`() {
        // Пауза ПЕРВОГО шага — задержка от нажатия комбо-кнопки до первого нажатия
        val events = tl(ComboStep("LEFT", 500), ComboStep("START", 0), ComboStep("SELECT", 0))
        assertEquals(500L, events.first().tMs)
        assertEquals(InputState.LEFT, events.first().bit)
        // LEFT отпущена на 600, START нажата сразу (пауза 0)
        assertEquals(ComboEvent(600, InputState.LEFT, false), events[1])
        assertEquals(ComboEvent(600, InputState.START, true), events[2])
        assertEquals(ComboEvent(700, InputState.SELECT, true), events[4])
    }

    @Test
    fun `одиночный шаг — нажать и отпустить`() {
        val events = tl(ComboStep("UP", 0))
        assertEquals(
            listOf(ComboEvent(0, InputState.UP, true), ComboEvent(100, InputState.UP, false)),
            events
        )
    }

    @Test
    fun `неизвестная кнопка отвергается`() {
        assertThrows(IllegalArgumentException::class.java) {
            tl(ComboStep("TURBO", 0))
        }
    }

    @Test
    fun `пустая последовательность — пустой таймлайн`() {
        assertEquals(emptyList<ComboEvent>(), tl())
    }

    // ------------------------------------------------------------------
    // Удержания и одновременность (новая модель шага)
    // ------------------------------------------------------------------

    @Test
    fun `целевой сценарий — A держится 500, B через 50 после нажатия A держится 500`() {
        val events = ComboTimeline.timeline(
            listOf(
                ComboStep("A", 0, holdMs = 500),
                ComboStep("B", 50, holdMs = 500, fromPress = true)
            )
        )
        // События сортируются по времени: нажатие B (50) раньше отпускания A (500)
        assertEquals(
            listOf(
                ComboEvent(0, InputState.A, true),
                ComboEvent(50, InputState.B, true),
                ComboEvent(500, InputState.A, false),
                ComboEvent(550, InputState.B, false)
            ),
            events
        )
    }

    @Test
    fun `одновременные кнопки — нажимаются и отпускаются вместе`() {
        val events = tl(ComboStep("A", 0, with = listOf("B"), holdMs = 500))
        assertEquals(
            listOf(
                ComboEvent(0, InputState.A, true),
                ComboEvent(0, InputState.B, true),
                ComboEvent(500, InputState.A, false),
                ComboEvent(500, InputState.B, false)
            ),
            events
        )
    }

    @Test
    fun `цепочка сдвигов по нажатию — каждый шаг от старта предыдущего`() {
        // A: 0..500; B: 50..550 (от нажатия A); SELECT: 150..350 (от нажатия B +100)
        val events = ComboTimeline.timeline(
            listOf(
                ComboStep("A", 0, holdMs = 500),
                ComboStep("B", 50, holdMs = 500, fromPress = true),
                ComboStep("SELECT", 100, holdMs = 200, fromPress = true)
            )
        )
        assertEquals(
            listOf(
                ComboEvent(0, InputState.A, true),
                ComboEvent(50, InputState.B, true),
                ComboEvent(150, InputState.SELECT, true),
                ComboEvent(350, InputState.SELECT, false),
                ComboEvent(500, InputState.A, false),
                ComboEvent(550, InputState.B, false)
            ),
            events
        )
    }

    @Test
    fun `удержание без fromPress — следующий шаг по-прежнему от отпускания`() {
        // Старый отсчёт сохраняется: B начинается через 100 мс ПОСЛЕ отпускания A
        val events = tl(ComboStep("A", 0, holdMs = 300), ComboStep("B", 100))
        assertEquals(
            listOf(
                ComboEvent(0, InputState.A, true),
                ComboEvent(300, InputState.A, false),
                ComboEvent(400, InputState.B, true),
                ComboEvent(500, InputState.B, false)
            ),
            events
        )
    }

    @Test
    fun `старые шаги без новых полей — прежняя сетка 0-100-200-300`() {
        // Шаги без новых полей (дефолты Kotlin: hold 100, без with/fromPress) —
        // тот же таймлайн, что и до введения удержаний
        val events = tl(ComboStep("A", 0), ComboStep("B", 100))
        assertEquals(
            listOf(
                ComboEvent(0, InputState.A, true),
                ComboEvent(100, InputState.A, false),
                ComboEvent(200, InputState.B, true),
                ComboEvent(300, InputState.B, false)
            ),
            events
        )
    }

    @Test
    fun `неизвестная кнопка в with тоже отвергается`() {
        assertThrows(IllegalArgumentException::class.java) {
            tl(ComboStep("A", 0, with = listOf("TURBO")))
        }
    }

    @Test
    fun `сегменты шагов — старт и конец каждого набора на шкале`() {
        val segs = ComboTimeline.segments(
            listOf(
                ComboStep("A", 0, holdMs = 500),
                ComboStep("B", 50, with = listOf("RIGHT"), holdMs = 350, fromPress = true),
                ComboStep("SELECT", 100, holdMs = 100)
            )
        )
        // A: 0..500; B+RIGHT: 50..400 (от нажатия A); SELECT: 400+100=500..600
        assertEquals(
            listOf(
                ComboSegment(0, listOf("A"), 0, 500),
                ComboSegment(1, listOf("B", "RIGHT"), 50, 400),
                ComboSegment(2, listOf("SELECT"), 500, 600)
            ),
            segs
        )
    }

    @Test
    fun `сегменты пустой последовательности пусты`() {
        assertEquals(emptyList<ComboSegment>(), ComboTimeline.segments(emptyList()))
    }
}
