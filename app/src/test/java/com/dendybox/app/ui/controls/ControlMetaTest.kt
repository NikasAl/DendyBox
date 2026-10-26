package com.dendybox.app.ui.controls

import com.dendybox.app.ui.controls.LayoutStore.GroupId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Кнопки корпуса гаджета (SAVE/LOAD) в группе META — этап 4 гаджет-дизайна.
 * Ключевые инварианты:
 *  - БЕЗ корпусного скина группа META прежняя: 2 пилюли SELECT/START,
 *    те же спеки и границы — дефолтный вид и зоны нажатий не изменены;
 *  - С корпусом META превращается в ряд из ЧЕТЫРЁХ пилюль
 *    SAVE | LOAD | SELECT | START (как на корпусе референса) — тот же
 *    якорь/масштаб, рамка и зажим обнимают весь ряд;
 *  - SAVE/LOAD — кнопки-ДЕЙСТВИЯ: размер/зоны считаются теми же формулами,
 *    но в InputState они не попадают (маршрутизация — GamepadInput
 *    .onSystemPress, проверяется фактом id-ветки, см. GamepadInput).
 */
class ControlMetaTest {

    private fun store() = LayoutStore(FakePrefs())

    // ------------------------------------------------------------------
    // Дефолт не затронут
    // ------------------------------------------------------------------

    @Test
    fun `без корпуса спеки META прежние — две пилюли`() {
        assertEquals(listOf("SELECT", "START"), SPECS_META.map { it.id })
        assertEquals(listOf(46f, 46f), SPECS_META.map { it.size })
        val s = store()
        assertEquals(SPECS_META, specsFor(s, BlockRef(GroupId.META)))
        assertEquals(SPECS_META, specsFor(s, BlockRef(GroupId.META), metaGadget = false))
        val e = extentsDp(GroupId.META, 0f)
        assertEquals(52f, e.left, 1e-6f)
        assertEquals(23f, e.top, 1e-6f)
        assertEquals(52f, e.right, 1e-6f)
        assertEquals(23f, e.bottom, 1e-6f)
    }

    // ------------------------------------------------------------------
    // Гаджет-вариант: ряд из четырёх пилюль
    // ------------------------------------------------------------------

    @Test
    fun `с корпусом в META четыре пилюли SAVE LOAD SELECT START`() {
        assertEquals(
            listOf("SAVE", "LOAD", "SELECT", "START"),
            SPECS_META_GADGET.map { it.id }
        )
        // одинаковые пилюли того же размера, что SELECT/START
        assertEquals(listOf(46f, 46f, 46f, 46f), SPECS_META_GADGET.map { it.size })
        // один ряд (dy = 0), шаг 58dp — тот же, что у дефолтного META
        assertEquals(listOf(0f, 0f, 0f, 0f), SPECS_META_GADGET.map { it.dy })
        assertEquals(listOf(-87f, -29f, 29f, 87f), SPECS_META_GADGET.map { it.dx })
        val s = store()
        val specs = specsFor(s, BlockRef(GroupId.META), metaGadget = true)
        assertEquals(SPECS_META_GADGET, specs)
    }

    @Test
    fun `границы META с корпусом обнимают четыре пилюли`() {
        val e = extentsDp(GroupId.META, 0f, metaGadget = true)
        // 87dp до центра крайней пилюли + 23dp радиус
        assertEquals(110f, e.left, 1e-6f)
        assertEquals(110f, e.right, 1e-6f)
        // по вертикали — как у дефолта
        assertEquals(23f, e.top, 1e-6f)
        assertEquals(23f, e.bottom, 1e-6f)
        // blockExtents передаёт флаг сквозь себя
        val be = blockExtents(store(), BlockRef(GroupId.META), 0f, metaGadget = true)
        assertEquals(e, be)
    }

    @Test
    fun `ряд гаджета — SELECT START сдвинуты вправо зазоры ровные`() {
        // Включение корпуса сдвигает SELECT/START на шаг (58dp) вправо —
        // слева освобождается место под SAVE/LOAD; привычные зоны остаются
        // пилюлями того же размера, просто на новых местах
        val defSel = SPECS_META.first { it.id == "SELECT" }
        val gadSel = SPECS_META_GADGET.first { it.id == "SELECT" }
        val defStart = SPECS_META.first { it.id == "START" }
        val gadStart = SPECS_META_GADGET.first { it.id == "START" }
        assertEquals(58f, gadSel.dx - defSel.dx, 1e-6f)
        assertEquals(58f, gadStart.dx - defStart.dx, 1e-6f)
        // SAVE/LOAD лежат строго левее SELECT — пересечений квадратов нет
        val save = SPECS_META_GADGET.first { it.id == "SAVE" }
        val load = SPECS_META_GADGET.first { it.id == "LOAD" }
        assertTrue(save.dx + 23f <= load.dx - 23f)
        assertTrue(load.dx + 23f <= gadSel.dx - 23f)
        assertTrue(gadSel.dx + 23f <= gadStart.dx - 23f)
    }

    // ------------------------------------------------------------------
    // Пилюли и картинки
    // ------------------------------------------------------------------

    @Test
    fun `SAVE и LOAD — пилюли с картинкой и каптионом только в гаджете`() {
        assertEquals(true, "SAVE" in PILL_IDS && "LOAD" in PILL_IDS)
        val gadget = ControlSkins.byId("gadget")
        for (id in listOf("SAVE", "LOAD")) {
            assertTrue("gadget: $id imageable", id in ControlSkin.IMAGEABLE_IDS)
            assertTrue("gadget: $id картинка", gadget.buttonImage(id) != null)
            // каптион = имя кнопки (над пилюлей, как надпись на корпусе)
            assertEquals(id, gadget.captionFor(id, id))
        }
        // параметрические скины: картинок нет — белый рендер с подписью внутри
        val classic = ControlSkins.byId("classic")
        for (id in listOf("SAVE", "LOAD")) {
            assertTrue(classic.buttonImage(id) == null)
        }
    }
}
