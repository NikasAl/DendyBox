package com.dendybox.app.ui.controls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Отключённые комбо-кнопки в конфиге раскладки (LayoutStore): хранение
 * в prefs (переживает «перезапуск» — новое хранилище над теми же prefs),
 * экспорт/импорт JSON, сброс к заводским. JVM, FakePrefs без Android.
 */
class LayoutStoreTest {

    @Test
    fun `отключение сохраняется в prefs и переживает перезапуск`() {
        val prefs = FakePrefs()
        val store = LayoutStore(prefs)
        assertTrue(store.combosOff.isEmpty())

        store.setComboEnabled("pres:Суперудар", false)
        store.setComboEnabled("u:u1abc", false)
        assertEquals(setOf("pres:Суперудар", "u:u1abc"), store.combosOff)
        assertFalse(store.isComboEnabled("pres:Суперудар"))
        assertTrue(store.isComboEnabled("pres:Ультра"))

        // «Перезапуск»: новое хранилище над теми же prefs читает то же
        assertEquals(
            setOf("pres:Суперудар", "u:u1abc"),
            LayoutStore(prefs).combosOff
        )
    }

    @Test
    fun `повторное отключение и включение — идемпотентно`() {
        val store = LayoutStore(FakePrefs())
        store.setComboEnabled("pres:X", false)
        store.setComboEnabled("pres:X", false) // уже выключена — без изменений
        assertEquals(setOf("pres:X"), store.combosOff)
        store.setComboEnabled("pres:X", true)
        assertTrue(store.combosOff.isEmpty())
        store.setComboEnabled("pres:X", true) // уже включена — без изменений
        assertTrue(store.combosOff.isEmpty())
    }

    @Test
    fun `экспорт-импорт переносит отключённые кнопки`() {
        val prefs2 = FakePrefs()
        val imported = LayoutStore(prefs2)
        imported.setComboEnabled("pres:Зарядка B", false)
        imported.setComboEnabled("u:u9", false)
        val json = imported.toJson()

        assertTrue(imported.applyJson(json))
        assertEquals(setOf("pres:Зарядка B", "u:u9"), imported.combosOff)
        // и обратно в prefs — переживает ещё один «перезапуск»
        assertEquals(setOf("pres:Зарядка B", "u:u9"), LayoutStore(prefs2).combosOff)
    }

    @Test
    fun `старый экспорт без раздела импортируется — все включены`() {
        val store = LayoutStore(FakePrefs())
        store.setComboEnabled("pres:X", false)
        // раскладка прежней версии: только группы, раздела combosOff нет
        val old = """
            { "dpad": {"x":0.1,"y":0.2,"s":1.5},
              "ab": {"x":0.8,"y":0.7,"s":1.4},
              "meta": {"x":0.5,"y":0.3,"s":1.0} }
        """.trimIndent()
        assertTrue(store.applyJson(old))
        assertTrue(store.combosOff.isEmpty())
        // группы при этом применились
        assertEquals(0.1f, store.dpad.x, 1e-6f)
        // битый раздел — как отсутствие
        assertTrue(
            store.applyJson(
                """{ "dpad": {"x":0.1,"y":0.2,"s":1.5},
                     "ab": {"x":0.8,"y":0.7,"s":1.4},
                     "meta": {"x":0.5,"y":0.3,"s":1.0},
                     "combosOff": "мусор" }"""
            )
        )
        assertTrue(store.combosOff.isEmpty())
    }

    @Test
    fun `resetAll возвращает все кнопки включёнными`() {
        val prefs = FakePrefs()
        val store = LayoutStore(prefs)
        store.setComboEnabled("pres:Суперудар", false)
        store.updateGroup(LayoutStore.GroupId.COMBO, LayoutStore.Group(0.3f, 0.4f, 1.2f))

        store.resetAll()
        assertTrue(store.combosOff.isEmpty())
        assertEquals(LayoutStore.DEF_COMBO_GROUP, store.comboGroups[0])
        // сброс сохранён — «перезапуск» его видит
        assertTrue(LayoutStore(prefs).combosOff.isEmpty())
    }

    @Test
    fun `битые prefs — пустой набор отключений, без исключения`() {
        val prefs = FakePrefs()
        prefs.edit().putString("layout_v3", "это не json").commit()
        assertTrue(LayoutStore(prefs).combosOff.isEmpty())
    }

    // ------------------------------------------------------------------
    // Пресет пакета-скина «Гаджет» (applyGadgetPresetOnce)
    // ------------------------------------------------------------------

    @Test
    fun `гаджет-пресет расставляет блоки по корпусу и применяется один раз`() {
        val prefs = FakePrefs()
        val store = LayoutStore(prefs)

        store.applyGadgetPresetOnce()
        assertEquals(0.13f, store.dpad.x, 1e-4f)
        assertEquals(0.75f, store.dpad.y, 1e-4f)
        assertEquals(0.87f, store.ab.x, 1e-4f)
        assertEquals(0.70f, store.ab.y, 1e-4f)
        assertEquals(0.5f, store.meta.x, 1e-4f)
        assertEquals(0.895f, store.meta.y, 1e-4f)
        // заводская комбо-группа уезжает вверх по центру — свободна зона META
        assertEquals(0.115f, store.comboGroups[0].y, 1e-4f)
        assertEquals(0.5f, store.comboGroups[0].x, 1e-4f)

        // «перезапуск» видит пресет (переживает prefs)
        val reloaded = LayoutStore(prefs)
        assertEquals(0.895f, reloaded.meta.y, 1e-4f)

        // повторный вызов раскладку НЕ перекрывает: подвигали крестовину —
        // пресет больше ничего не меняет
        reloaded.updateGroup(LayoutStore.GroupId.DPAD, LayoutStore.Group(0.3f, 0.3f, 1f))
        reloaded.applyGadgetPresetOnce()
        assertEquals(0.3f, reloaded.dpad.x, 1e-6f)
        assertEquals(0.3f, reloaded.dpad.y, 1e-6f)
        assertEquals(0.895f, reloaded.meta.y, 1e-6f)
    }

    @Test
    fun `гаджет-пресет сохраняет масштабы и не трогает передвинутую комбо-группу`() {
        val store = LayoutStore(FakePrefs())
        store.updateGroup(LayoutStore.GroupId.DPAD, LayoutStore.Group(0.2f, 0.8f, 1.7f))
        store.updateGroup(LayoutStore.GroupId.AB, LayoutStore.Group(0.8f, 0.6f, 0.9f))
        store.updateGroup(LayoutStore.GroupId.META, LayoutStore.Group(0.5f, 0.5f, 1.3f))
        // комбо-группа передвинута пользователем — не на заводской точке
        store.updateComboGroupPos(0, 0.7f, 0.4f, 1.1f)

        store.applyGadgetPresetOnce()
        // позиции по корпусу, масштабы — СВОИ (не перекрываются)
        assertEquals(1.7f, store.dpad.scale, 1e-6f)
        assertEquals(0.9f, store.ab.scale, 1e-6f)
        assertEquals(1.3f, store.meta.scale, 1e-6f)
        assertEquals(0.13f, store.dpad.x, 1e-4f)
        assertEquals(0.87f, store.ab.x, 1e-4f)
        // пользовательская комбо-группа не двигается
        assertEquals(0.7f, store.comboGroups[0].x, 1e-6f)
        assertEquals(0.4f, store.comboGroups[0].y, 1e-6f)
        assertEquals(1.1f, store.comboGroups[0].scale, 1e-6f)
        // значения в допустимых границах (санитизация)
        for (g in listOf(store.dpad, store.ab, store.meta)) {
            assertTrue(g.x in 0.02f..0.98f && g.y in 0.02f..0.98f)
            assertTrue(g.scale in LayoutStore.MIN_SCALE..LayoutStore.MAX_SCALE)
        }
    }

    @Test
    fun `гаджет-пресет переживёт экспорт-импорт и сброс раскладки возвращает заводскую`() {
        val prefs = FakePrefs()
        val store = LayoutStore(prefs)
        store.applyGadgetPresetOnce()

        val other = LayoutStore(FakePrefs())
        assertTrue(other.applyJson(store.toJson()))
        assertEquals(0.115f, other.comboGroups[0].y, 1e-4f)
        assertEquals(0.895f, other.meta.y, 1e-4f)

        // сброс — к заводской раскладке (не к гаджет-пресету)
        other.resetAll()
        assertEquals(LayoutStore.DEF_META.y, other.meta.y, 1e-6f)
        assertEquals(LayoutStore.DEF_COMBO_GROUP, other.comboGroups[0])
    }
}
