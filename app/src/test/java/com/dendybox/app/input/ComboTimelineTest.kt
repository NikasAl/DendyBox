package com.dendybox.app.input

import com.dendybox.app.ui.controls.ComboStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Таймлайн комбо-кнопки — точная сетка 100 мс: каждая кнопка нажимается,
 * держится HOLD_MS, отпускается, затем пауза шага. Проверяются моменты
 * событий в мс от нажатия комбо-кнопки (чистая функция, JVM).
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
}
