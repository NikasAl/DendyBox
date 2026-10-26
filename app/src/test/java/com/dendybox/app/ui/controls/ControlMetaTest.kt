package com.dendybox.app.ui.controls

import com.dendybox.app.ui.controls.LayoutStore.GroupId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Группа META (Select/Start) — после переноса системных кнопок в отдельные
 * группы «Пауза+Mute» и «Редактор+Save+Load» она БОЛЬШЕ НЕ ЗАВИСИТ от скина:
 * SAVE/LOAD живут в своей группе (см. ControlSystemTest), META — всегда
 * две пилюли SELECT/START. Ключевые инварианты:
 *  - спеки/границы META прежние при ЛЮБОМ скине — дефолтный вид и зоны
 *    нажатий не изменены;
 *  - SELECT/START — пилюли одного размера, один ряд, ровные зазоры.
 */
class ControlMetaTest {

    private fun store() = LayoutStore(FakePrefs())

    @Test
    fun `спеки META прежние — две пилюли независимо от скина`() {
        assertEquals(listOf("SELECT", "START"), SPECS_META.map { it.id })
        assertEquals(listOf(46f, 46f), SPECS_META.map { it.size })
        assertEquals(listOf(-29f, 29f), SPECS_META.map { it.dx })
        assertEquals(listOf(0f, 0f), SPECS_META.map { it.dy })
        val s = store()
        assertEquals(SPECS_META, specsFor(s, BlockRef(GroupId.META)))
        // и на пресете гаджета META не меняется — системные кнопки живут отдельно
        s.applyGadgetPresetOnce()
        assertEquals(SPECS_META, specsFor(s, BlockRef(GroupId.META)))
    }

    @Test
    fun `границы META обнимают две пилюли`() {
        val e = extentsDp(GroupId.META, 0f)
        // 29dp до центра пилюли + 23dp радиус
        assertEquals(52f, e.left, 1e-6f)
        assertEquals(52f, e.right, 1e-6f)
        assertEquals(23f, e.top, 1e-6f)
        assertEquals(23f, e.bottom, 1e-6f)
        // blockExtents даёт то же
        assertEquals(e, blockExtents(store(), BlockRef(GroupId.META), 0f))
    }

    @Test
    fun `ряд META — ровные зазоры между пилюлями`() {
        val sel = SPECS_META.first { it.id == "SELECT" }
        val start = SPECS_META.first { it.id == "START" }
        // шаг 58dp при размере 46 — зазор 12dp, как в других рядах
        assertEquals(58f, start.dx - sel.dx, 1e-6f)
        assertTrue(sel.dx + 23f <= start.dx - 23f)
    }

    // ------------------------------------------------------------------
    // Пилюли и каптионы
    // ------------------------------------------------------------------

    @Test
    fun `SELECT и START — пилюли с картинкой и каптионом только в гаджете`() {
        val gadget = ControlSkins.byId("gadget")
        for (id in listOf("SELECT", "START")) {
            assertTrue("gadget: $id imageable", id in ControlSkin.IMAGEABLE_IDS)
            assertTrue("gadget: $id картинка", gadget.buttonImage(id) != null)
        }
        // у спека SELECT подпись «SEL» — каптион даёт полное имя
        assertEquals("SELECT", gadget.captionFor("SELECT", "SEL"))
        assertEquals("START", gadget.captionFor("START", "START"))
        // параметрические скины: картинок нет — белый рендер с подписью внутри
        val classic = ControlSkins.byId("classic")
        for (id in listOf("SELECT", "START")) {
            assertTrue(classic.buttonImage(id) == null)
        }
    }
}
