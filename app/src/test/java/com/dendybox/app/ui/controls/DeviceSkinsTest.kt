package com.dendybox.app.ui.controls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Скины «корпуса» ([DeviceSkin]/[DeviceSkins]) — конфиг декоративного
 * слоя-гаджета вокруг кадра игры. Ключевые инварианты:
 *  - дефолт — «Нет»: слой не рисуется, классический вид не затронут;
 *    «Нет» же — запасной скин для неизвестного id;
 *  - слой ЧИСТО визуальный: конфиг не содержит геометрии контролов и
 *    колбэков ввода — роутинг/зоны/перемещаемость групп не зависят от
 *    скина (как у ControlSkin);
 *  - «Гаджет» включён и отличается от «Нет»; все цвета непрозрачны —
 *    прозрачность игрового «окна» задаётся геометрией пути (even-odd),
 *    а не альфой краски.
 */
class DeviceSkinsTest {

    @Test
    fun `в наборе два скина с уникальными идентификаторами`() {
        assertEquals(2, DeviceSkins.ALL.size)
        assertEquals(2, DeviceSkins.ALL.map { it.id }.toSet().size)
        assertEquals(setOf("none", "gadget"), DeviceSkins.ALL.map { it.id }.toSet())
    }

    @Test
    fun `дефолт — нет корпуса и он же запасной для неизвестного идентификатора`() {
        val none = DeviceSkins.byId(DeviceSkins.NONE_ID)
        assertEquals("none", DeviceSkins.NONE_ID)
        assertFalse(none.enabled)
        assertSame(DeviceSkins.ALL.first(), none)
        assertSame(none, DeviceSkins.DEFAULT)
        assertSame(none, DeviceSkins.byId("неизвестный"))
        assertSame(none, DeviceSkins.byId(""))
        // старая строка настроек скин-кнопок не должна включить корпус
        assertSame(none, DeviceSkins.byId("classic"))
    }

    @Test
    fun `корпус — часть скина кнопок и есть только у гаджета`() {
        // скин «Гаджет» подразумевает корпус (единый дизайн), остальные — нет
        assertSame(
            DeviceSkins.byId("gadget"),
            DeviceSkins.forControlSkin(ControlSkins.GADGET_ID)
        )
        for (skin in ControlSkins.ALL.filter { it.id != ControlSkins.GADGET_ID }) {
            assertFalse(
                "${skin.id}: корпус должен отсутствовать",
                DeviceSkins.forControlSkin(skin.id).enabled
            )
            assertSame(
                "${skin.id}: фолбэк — «нет корпуса»",
                DeviceSkins.DEFAULT,
                DeviceSkins.forControlSkin(skin.id)
            )
        }
        // неизвестный/пустой id — «нет корпуса»
        assertFalse(DeviceSkins.forControlSkin("неизвестный").enabled)
        assertFalse(DeviceSkins.forControlSkin("").enabled)
    }

    @Test
    fun `гаджет включён и отличается от нет`() {
        val gadget = DeviceSkins.byId("gadget")
        assertTrue(gadget.enabled)
        val none = DeviceSkins.DEFAULT
        assertNotEquals(gadget.bodyColor, none.bodyColor)
        assertNotEquals(gadget.bezelColor, none.bodyColor)
        assertTrue(gadget.colors().zip(none.colors()).any { it.first != it.second })
    }

    @Test
    fun `динамики у гаджета убраны — отверстия в полях мешали`() {
        // Отверстия динамиков не читались и спорили с фото-кнопками —
        // колонки убраны (speakerCount = 0 → drawSpeakers не рисует ничего)
        assertEquals(0, DeviceSkins.byId("gadget").speakerCount)
    }

    @Test
    fun `базовые цвета всех скинов корпуса полностью непрозрачны`() {
        for (skin in DeviceSkins.ALL) {
            for ((i, c) in skin.colors().withIndex()) {
                assertEquals("скин ${skin.id}, цвет #$i", 1f, c.alpha, 1e-6f)
            }
        }
    }

    @Test
    fun `геометрия корпуса положительна и в разумных пределах`() {
        for (skin in DeviceSkins.ALL) {
            assertTrue("${skin.id}: margin", skin.bodyMarginDp > 0f)
            assertTrue("${skin.id}: corner", skin.bodyCornerDp > 0f)
            assertTrue("${skin.id}: gap", skin.bezelGapDp >= 0f)
            assertTrue("${skin.id}: bezel", skin.bezelWidthDp > 0f)
            assertTrue("${skin.id}: динамики", skin.speakerCount >= 0)
            assertTrue("${skin.id}: радиус", skin.speakerRadiusDp > 0f)
            assertTrue("${skin.id}: шаг", skin.speakerStepDp > 0f)
        }
    }

    @Test
    fun `у нет корпуса геометрия не влияет — слой не рисуется`() {
        // инвариант дефолта: даже «случайные» значения none не включают слой
        assertFalse(DeviceSkins.DEFAULT.enabled)
        assertFalse(DeviceSkins.byId("none").enabled)
    }
}
