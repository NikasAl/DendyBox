package com.dendybox.app.ui.controls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Слайдер «Видимость» обязан двигать ВСЕ экранные контролы согласованно:
 * рамки кнопок, подписи и вся крестовина больше не захардкожены, а
 * масштабируются вместе с заливками (см. [ControlAlphas]).
 *
 * Ключевые инварианты:
 *  - при базовом значении слайдера (0.55) все альфы равны прежним константам
 *    дизайна — вид по умолчанию не изменился;
 *  - каждая альфа монотонно растёт с opacity и не выходит за пределы [0..1]
 *    на всём ходе слайдера (0.2..0.9) и за его краями;
 *  - рамка основной кнопки при любом значении совпадает с самим opacity —
 *    так же, как заливка (слайдер честно двигает и рамку).
 */
class ControlAlphasTest {

    /** Все альфы одним списком — для монотонности и границ. */
    private fun ControlAlphas.all(): List<Float> = listOf(
        borderMain, borderTurbo, label,
        dpadFill, dpadStroke, dpadArrow, dpadArrowActive, dpadDot
    )

    private val base = ControlAlphas.of(ControlAlphas.OPACITY_BASE)

    // ------------------------------------------------------------------
    // Дефолтный вид не изменился
    // ------------------------------------------------------------------

    @Test
    fun `при базовом слайдере альфы равны прежним константам дизайна`() {
        assertEquals(0.55f, base.borderMain, 1e-6f)
        assertEquals(0.35f, base.borderTurbo, 1e-6f)
        assertEquals(0.9f, base.label, 1e-6f)
        assertEquals(0.12f, base.dpadFill, 1e-6f)
        assertEquals(0.55f, base.dpadStroke, 1e-6f)
        assertEquals(0.42f, base.dpadArrow, 1e-6f)
        assertEquals(0.95f, base.dpadArrowActive, 1e-6f)
        assertEquals(0.5f, base.dpadDot, 1e-6f)
    }

    // ------------------------------------------------------------------
    // Слайдер двигает ВСЁ и согласованно
    // ------------------------------------------------------------------

    @Test
    fun `любая альфа растёт вместе со слайдером`() {
        val min = ControlAlphas.of(0.2f).all()
        val mid = base.all()
        val max = ControlAlphas.of(0.9f).all()
        for (i in min.indices) {
            assertTrue("альфа #$i: min<base", min[i] < mid[i])
            assertTrue("альфа #$i: base<max", mid[i] < max[i])
        }
    }

    @Test
    fun `рамка основной кнопки равна значению слайдера как и заливка`() {
        for (op in listOf(0.2f, 0.35f, 0.55f, 0.7f, 0.9f)) {
            assertEquals(op, ControlAlphas.of(op).borderMain, 1e-5f)
        }
    }

    @Test
    fun `относительные веса дизайна сохраняются на любом положении слайдера`() {
        for (op in listOf(0.2f, 0.4f, 0.55f, 0.75f, 0.9f)) {
            val a = ControlAlphas.of(op)
            val k = op / ControlAlphas.OPACITY_BASE
            assertEquals(0.35f / 0.55f, a.borderTurbo / a.borderMain, 1e-4f)
            assertEquals(0.12f / 0.55f, a.dpadFill / a.dpadStroke, 1e-4f)
            assertEquals(0.42f * k, a.dpadArrow, 1e-5f)
            assertEquals(0.5f * k, a.dpadDot, 1e-5f)
        }
    }

    // ------------------------------------------------------------------
    // Границы: ничего не уходит за 0..1, активная стрелка остаётся ярче
    // ------------------------------------------------------------------

    @Test
    fun `альфы в пределах от 0 до 1 на всём ходе слайдера и за его краями`() {
        for (op in listOf(-1f, 0f, 0.2f, 0.5f, 0.9f, 1f, 5f)) {
            for ((i, v) in ControlAlphas.of(op).all().withIndex()) {
                assertTrue("альфа #$i при opacity=$op: $v", v in 0f..1f)
            }
        }
    }

    @Test
    fun `активная стрелка крестовины всегда не тусклее неактивной`() {
        for (op in listOf(0.2f, 0.55f, 0.9f)) {
            val a = ControlAlphas.of(op)
            assertTrue(a.dpadArrowActive >= a.dpadArrow)
        }
    }

    @Test
    fun `крайние положения слайдера дают осмысленные крайние виды`() {
        // Минимум — всё заметно растворилось (ни одна альфа не «ярче» 0.35)
        for (v in ControlAlphas.of(0.2f).all()) assertTrue(v < 0.35f)
        // Максимум — рамка и обводка крестовины сравнялись с заливкой (0.9);
        // плотность 1.0 достигается только за краем слайдера (opacity ≥ 1)
        assertEquals(0.9f, ControlAlphas.of(0.9f).borderMain, 1e-6f)
        assertEquals(0.9f, ControlAlphas.of(0.9f).dpadStroke, 1e-6f)
        assertEquals(1f, ControlAlphas.of(1f).dpadStroke, 1e-6f)
    }
}
