package com.dendybox.app.ui.controls

import com.dendybox.app.input.GamepadInput
import com.dendybox.app.ui.controls.LayoutStore.GroupId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Системные группы «Пауза+Mute» ([GroupId.PAUSE_MUTE]) и
 * «Редактор+Save+Load» ([GroupId.EDIT_SAVE_LOAD]) — заменяют прежние
 * неподвижные кнопки верхней панели. Ключевые инварианты:
 *  - кнопки-ДЕЙСТВИЯ: рисуются/нажимаются как обычные (тот же hit-test,
 *    подсветка, скин), но в InputState не попадают — [GamepadInput]
 *    .onSystemPress выполняет действие на нажатие (id-ветка проверяется
 *    фактом в [GamepadInput.SYSTEM_IDS]);
 *  - ряды пилюль того же размера, что SELECT/START (46dp, шаг 58dp):
 *    в гаджете — фото-пилюли с каптионами, у прочих скинов — параметрика;
 *  - группы показываются ВСЕГДА (не зависят от скина) и перемещаемы
 *    как остальные: персистентность переживает prefs-перезапуск,
 *    экспорт/импорт и сброс (см. LayoutStoreTest).
 */
class ControlSystemTest {

    private fun store() = LayoutStore(FakePrefs())

    // ------------------------------------------------------------------
    // Состав рядов
    // ------------------------------------------------------------------

    @Test
    fun `группа Пауза+Mute — две пилюли PAUSE MUTE`() {
        assertEquals(listOf("PAUSE", "MUTE"), SPECS_PAUSE_MUTE.map { it.id })
        assertEquals(listOf(46f, 46f), SPECS_PAUSE_MUTE.map { it.size })
        // один ряд, шаг 58 — как META
        assertEquals(listOf(0f, 0f), SPECS_PAUSE_MUTE.map { it.dy })
        assertEquals(listOf(-29f, 29f), SPECS_PAUSE_MUTE.map { it.dx })
        assertEquals(SPECS_PAUSE_MUTE, specsFor(store(), BlockRef(GroupId.PAUSE_MUTE)))
    }

    @Test
    fun `группа Редактор+Save+Load — три пилюли EDIT SAVE LOAD`() {
        assertEquals(listOf("EDIT", "SAVE", "LOAD"), SPECS_EDIT_SAVE_LOAD.map { it.id })
        assertEquals(listOf(46f, 46f, 46f), SPECS_EDIT_SAVE_LOAD.map { it.size })
        assertEquals(listOf(0f, 0f, 0f), SPECS_EDIT_SAVE_LOAD.map { it.dy })
        assertEquals(listOf(-58f, 0f, 58f), SPECS_EDIT_SAVE_LOAD.map { it.dx })
        assertEquals(SPECS_EDIT_SAVE_LOAD, specsFor(store(), BlockRef(GroupId.EDIT_SAVE_LOAD)))
    }

    @Test
    fun `все системные кнопки — пилюли`() {
        for (id in listOf("PAUSE", "MUTE", "EDIT", "SAVE", "LOAD")) {
            assertTrue("$id в PILL_IDS", id in PILL_IDS)
        }
    }

    // ------------------------------------------------------------------
    // Границы: рамка и зажим обнимают ряды
    // ------------------------------------------------------------------

    @Test
    fun `границы системных групп обнимают свои ряды`() {
        val pm = extentsDp(GroupId.PAUSE_MUTE, 0f)
        assertEquals(52f, pm.left, 1e-6f) // 29 + 23
        assertEquals(52f, pm.right, 1e-6f)
        assertEquals(23f, pm.top, 1e-6f)
        assertEquals(23f, pm.bottom, 1e-6f)
        assertEquals(pm, blockExtents(store(), BlockRef(GroupId.PAUSE_MUTE), 0f))

        val esl = extentsDp(GroupId.EDIT_SAVE_LOAD, 0f)
        assertEquals(81f, esl.left, 1e-6f) // 58 + 23
        assertEquals(81f, esl.right, 1e-6f)
        assertEquals(23f, esl.top, 1e-6f)
        assertEquals(23f, esl.bottom, 1e-6f)
        assertEquals(esl, blockExtents(store(), BlockRef(GroupId.EDIT_SAVE_LOAD), 0f))
    }

    @Test
    fun `в рядах нет пересечений пилюль`() {
        fun noOverlap(specs: List<CtrlSpec>) {
            val sorted = specs.sortedBy { it.dx }
            for (i in 0 until sorted.size - 1) {
                assertTrue(
                    "пересечение ${sorted[i].id}/${sorted[i + 1].id}",
                    sorted[i].dx + sorted[i].size / 2f <= sorted[i + 1].dx - sorted[i + 1].size / 2f
                )
            }
        }
        noOverlap(SPECS_PAUSE_MUTE)
        noOverlap(SPECS_EDIT_SAVE_LOAD)
    }

    // ------------------------------------------------------------------
    // Маршрутизация действий
    // ------------------------------------------------------------------

    @Test
    fun `все кнопки системных групп маршрутизируются в onSystemPress`() {
        assertEquals(
            setOf("PAUSE", "MUTE", "EDIT", "SAVE", "LOAD"),
            GamepadInput.SYSTEM_IDS
        )
        // кнопки этих групп — ровно системный набор
        assertEquals(
            GamepadInput.SYSTEM_IDS,
            (SPECS_PAUSE_MUTE + SPECS_EDIT_SAVE_LOAD).map { it.id }.toSet()
        )
    }

    // ------------------------------------------------------------------
    // Скин: у гаджета системные кнопки — фото-пилюли с каптионами,
    // у остальных скинов — параметрика (картинки нет)
    // ------------------------------------------------------------------

    @Test
    fun `системные кнопки в гаджете с картинкой и каптионом-именем`() {
        val gadget = ControlSkins.byId("gadget")
        for (id in listOf("PAUSE", "MUTE", "EDIT", "SAVE", "LOAD")) {
            assertTrue("$id imageable", id in ControlSkin.IMAGEABLE_IDS)
            assertTrue("gadget: $id картинка", gadget.buttonImage(id) != null)
            // каптион = имя кнопки (над пилюлей, как надпись на корпусе)
            assertEquals(id, gadget.captionFor(id, id))
        }
        // каптионный режим НЕ применяется к комбо-кнопкам
        assertTrue("K1" !in ControlSkin.IMAGEABLE_IDS)
    }

    @Test
    fun `у прочих скинов системные кнопки параметрические`() {
        for (skin in ControlSkins.ALL.filter { it.id != "gadget" }) {
            for (id in listOf("PAUSE", "MUTE", "EDIT", "SAVE", "LOAD")) {
                assertTrue(
                    "${skin.id}: $id без картинки",
                    skin.buttonImage(id) == null
                )
            }
        }
    }

    // ------------------------------------------------------------------
    // Персистентность позиций (кратко; подробнее — LayoutStoreTest)
    // ------------------------------------------------------------------

    @Test
    fun `позиции системных групп переживают пересоздание хранилища`() {
        val prefs = FakePrefs()
        val s = LayoutStore(prefs)
        s.updateGroup(GroupId.PAUSE_MUTE, LayoutStore.Group(0.2f, 0.15f, 1.3f))
        s.updateGroup(GroupId.EDIT_SAVE_LOAD, LayoutStore.Group(0.8f, 0.12f, 0.9f))

        val reborn = LayoutStore(prefs)
        assertEquals(LayoutStore.Group(0.2f, 0.15f, 1.3f), reborn.group(GroupId.PAUSE_MUTE))
        assertEquals(LayoutStore.Group(0.8f, 0.12f, 0.9f), reborn.group(GroupId.EDIT_SAVE_LOAD))
    }

    @Test
    fun `старый конфиг без системных групп — заводские позиции`() {
        val prefs = FakePrefs()
        prefs.edit().putString(
            "layout_v3",
            """{ "dpad": {"x":0.1,"y":0.2,"s":1.5},
                 "ab": {"x":0.8,"y":0.7,"s":1.4},
                 "meta": {"x":0.5,"y":0.3,"s":1.0} }"""
        ).commit()
        val s = LayoutStore(prefs)
        assertEquals(LayoutStore.DEF_SYS1, s.group(GroupId.PAUSE_MUTE))
        assertEquals(LayoutStore.DEF_SYS2, s.group(GroupId.EDIT_SAVE_LOAD))
    }

    @Test
    fun `экспорт-импорт переносит системные группы, сброс возвращает заводские`() {
        val s = LayoutStore(FakePrefs())
        s.updateGroup(GroupId.PAUSE_MUTE, LayoutStore.Group(0.3f, 0.2f, 1.1f))
        s.updateGroup(GroupId.EDIT_SAVE_LOAD, LayoutStore.Group(0.7f, 0.25f, 1.4f))
        val other = LayoutStore(FakePrefs())
        assertTrue(other.applyJson(s.toJson()))
        assertEquals(LayoutStore.Group(0.3f, 0.2f, 1.1f), other.group(GroupId.PAUSE_MUTE))
        assertEquals(LayoutStore.Group(0.7f, 0.25f, 1.4f), other.group(GroupId.EDIT_SAVE_LOAD))

        other.resetAll()
        assertEquals(LayoutStore.DEF_SYS1, other.group(GroupId.PAUSE_MUTE))
        assertEquals(LayoutStore.DEF_SYS2, other.group(GroupId.EDIT_SAVE_LOAD))
    }
}
