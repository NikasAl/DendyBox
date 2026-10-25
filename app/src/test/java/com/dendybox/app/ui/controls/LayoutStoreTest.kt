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
        assertEquals(LayoutStore.DEF_COMBO, store.combo)
        // сброс сохранён — «перезапуск» его видит
        assertTrue(LayoutStore(prefs).combosOff.isEmpty())
    }

    @Test
    fun `битые prefs — пустой набор отключений, без исключения`() {
        val prefs = FakePrefs()
        prefs.edit().putString("layout_v3", "это не json").commit()
        assertTrue(LayoutStore(prefs).combosOff.isEmpty())
    }
}
