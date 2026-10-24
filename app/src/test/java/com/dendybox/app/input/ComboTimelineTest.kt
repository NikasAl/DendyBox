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
    fun `целевой сценарий — A держится 1000, B через 100 после нажатия A держится 1000`() {
        val events = ComboTimeline.timeline(
            listOf(
                ComboStep("A", 0, holdMs = 1000),
                ComboStep("B", 100, holdMs = 1000, fromPress = true)
            )
        )
        // События сортируются по времени: нажатие B (100) раньше отпускания A (1000)
        assertEquals(
            listOf(
                ComboEvent(0, InputState.A, true),
                ComboEvent(100, InputState.B, true),
                ComboEvent(1000, InputState.A, false),
                ComboEvent(1100, InputState.B, false)
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
        // A: 0..1000; B: 100..1100 (от нажатия A); SELECT: 300..800 (от нажатия B +200)
        val events = ComboTimeline.timeline(
            listOf(
                ComboStep("A", 0, holdMs = 1000),
                ComboStep("B", 100, holdMs = 1000, fromPress = true),
                ComboStep("SELECT", 200, holdMs = 500, fromPress = true)
            )
        )
        assertEquals(
            listOf(
                ComboEvent(0, InputState.A, true),
                ComboEvent(100, InputState.B, true),
                ComboEvent(300, InputState.SELECT, true),
                ComboEvent(800, InputState.SELECT, false),
                ComboEvent(1000, InputState.A, false),
                ComboEvent(1100, InputState.B, false)
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
        // Backcompat: ComboStep с дефолтами (hold 100, без with/fromPress) —
        // тот же таймлайн, что до введения удержаний
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
}
