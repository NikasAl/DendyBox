package com.dendybox.app.ui.controls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Комбо-группы в конфиге раскладки ([LayoutStore.comboGroups]) и скрытие
 * подблоков кластера A/B ([LayoutStore.abOff]): хранение в prefs
 * (переживает «перезапуск» — новое хранилище над теми же prefs),
 * миграция старого конфига с полем «combo», экспорт/импорт, сброс.
 * JVM, FakePrefs без Android.
 */
class LayoutGroupsTest {

    // ------------------------------------------------------------------
    // Комбо-группы: миграция и перезапуск
    // ------------------------------------------------------------------

    @Test
    fun `старый конфиг с полем combo — одна группа с той позицией`() {
        val prefs = FakePrefs()
        prefs.edit().putString(
            "layout_v3",
            """{ "dpad": {"x":0.1,"y":0.2,"s":1.5},
                 "ab": {"x":0.8,"y":0.7,"s":1.4},
                 "meta": {"x":0.5,"y":0.3,"s":1.0},
                 "combo": {"x":0.4,"y":0.7,"s":1.3} }"""
        ).commit()
        val store = LayoutStore(prefs)
        assertEquals(1, store.comboGroups.size)
        assertEquals("Комбо-кнопки", store.comboGroups[0].name)
        assertEquals(0.4f, store.comboGroups[0].x, 1e-6f)
        assertEquals(0.7f, store.comboGroups[0].y, 1e-6f)
        assertEquals(1.3f, store.comboGroups[0].scale, 1e-6f)
        // доступ через легаси-аксессор GroupId.COMBO — та же группа
        assertEquals(0.4f, store.group(LayoutStore.GroupId.COMBO).x, 1e-6f)
    }

    @Test
    fun `группы переживают перезапуск`() {
        val prefs = FakePrefs()
        val store = LayoutStore(prefs)
        val idx = store.addComboGroup("Атаки")
        store.renameComboGroup(idx, "Супердвижения")
        store.updateComboGroupPos(idx, 0.2f, 0.3f, 0.8f)
        store.setComboGroupKeys(idx, listOf("pres:Суперудар", "u:u1", "u:u2"))
        store.setComboGroupKeys(0, listOf("u:u3"))

        val reborn = LayoutStore(prefs)
        assertEquals(
            listOf("Супердвижения", listOf("pres:Суперудар", "u:u1", "u:u2")),
            listOf(reborn.comboGroups[1].name, reborn.comboGroups[1].keys)
        )
        assertEquals(listOf("u:u3"), reborn.comboGroups[0].keys)
        assertEquals(0.2f, reborn.comboGroups[1].x, 1e-6f)
        assertEquals(0.8f, reborn.comboGroups[1].scale, 1e-6f)
    }

    // ------------------------------------------------------------------
    // Состав групп: ключи, дубликаты, поиск группы
    // ------------------------------------------------------------------

    @Test
    fun `setComboGroupKeys чистит пустые и дубликаты`() {
        val store = LayoutStore(FakePrefs())
        store.setComboGroupKeys(0, listOf("u:a", "", "u:a", "u:b", " "))
        assertEquals(listOf("u:a", "u:b"), store.comboGroups[0].keys)
    }

    @Test
    fun `comboGroupOfKey — первая содержащая группа, непрописанные — группа 0`() {
        val store = LayoutStore(FakePrefs())
        store.addComboGroup("Вторая")
        store.setComboGroupKeys(1, listOf("u:x"))
        assertEquals(1, store.comboGroupOfKey("u:x"))
        assertEquals(0, store.comboGroupOfKey("u:нигде"))
    }

    @Test
    fun `updateGroup COMBO переправляется в группу 0`() {
        val store = LayoutStore(FakePrefs())
        store.addComboGroup("Вторая")
        store.updateComboGroupPos(1, 0.9f, 0.9f, 2f)
        store.updateGroup(LayoutStore.GroupId.COMBO, LayoutStore.Group(0.3f, 0.4f, 1.2f))
        assertEquals(LayoutStore.Group(0.3f, 0.4f, 1.2f), store.group(LayoutStore.GroupId.COMBO))
        // группа 1 не задета
        assertEquals(0.9f, store.comboGroups[1].x, 1e-6f)
    }

    // ------------------------------------------------------------------
    // Удаление групп
    // ------------------------------------------------------------------

    @Test
    fun `последнюю группу удалить нельзя`() {
        val store = LayoutStore(FakePrefs())
        assertFalse(store.removeComboGroup(0))
        assertEquals(1, store.comboGroups.size)
    }

    @Test
    fun `удалённая группа отдаёт кнопки первой оставшейся`() {
        val store = LayoutStore(FakePrefs())
        store.setComboGroupKeys(0, listOf("u:a"))
        val i1 = store.addComboGroup("Вторая")
        store.setComboGroupKeys(i1, listOf("u:b", "u:c"))
        val i2 = store.addComboGroup("Третья")
        store.setComboGroupKeys(i2, listOf("u:d"))

        assertTrue(store.removeComboGroup(i1))
        assertEquals(2, store.comboGroups.size)
        // «Вторая» исчезла, её ключи — в конце группы 0
        assertEquals(listOf("u:a", "u:b", "u:c"), store.comboGroups[0].keys)
        assertEquals(listOf("u:d"), store.comboGroups[1].keys)
        // удаление группы 0: ключи переезжают в новую первую
        assertTrue(store.removeComboGroup(0))
        assertEquals(listOf("u:d", "u:a", "u:b", "u:c"), store.comboGroups[0].keys)
    }

    // ------------------------------------------------------------------
    // Скрытие подблоков A/B
    // ------------------------------------------------------------------

    @Test
    fun `скрытие пар A B и турбо сохраняется и переживает перезапуск`() {
        val prefs = FakePrefs()
        val store = LayoutStore(prefs)
        assertFalse(store.isAbHidden(LayoutStore.AB_MAIN))
        store.setAbHidden(LayoutStore.AB_TURBO, true)
        assertTrue(store.isAbHidden(LayoutStore.AB_TURBO))
        assertFalse(store.isAbHidden(LayoutStore.AB_MAIN))
        // повторное — идемпотентно
        store.setAbHidden(LayoutStore.AB_TURBO, true)
        assertEquals(setOf(LayoutStore.AB_TURBO), store.abOff)

        assertEquals(setOf(LayoutStore.AB_TURBO), LayoutStore(prefs).abOff)

        store.setAbHidden(LayoutStore.AB_TURBO, false)
        assertTrue(store.abOff.isEmpty())
    }

    @Test
    fun `мусор в разделе abOff игнорируется`() {
        val prefs = FakePrefs()
        prefs.edit().putString(
            "layout_v3",
            """{ "dpad": {"x":0.1,"y":0.2,"s":1.5},
                 "ab": {"x":0.8,"y":0.7,"s":1.4},
                 "meta": {"x":0.5,"y":0.3,"s":1.0},
                 "abOff": ["main", "мусор", "turbo"] }"""
        ).commit()
        assertEquals(setOf(LayoutStore.AB_MAIN, LayoutStore.AB_TURBO), LayoutStore(prefs).abOff)
    }

    // ------------------------------------------------------------------
    // Экспорт/импорт и сброс
    // ------------------------------------------------------------------

    @Test
    fun `экспорт-импорт переносит группы и скрытия A B`() {
        val prefs = FakePrefs()
        val store = LayoutStore(prefs)
        val idx = store.addComboGroup("Атаки")
        store.setComboGroupKeys(idx, listOf("u:1", "u:2"))
        store.updateComboGroupPos(idx, 0.25f, 0.35f, 0.9f)
        store.setAbHidden(LayoutStore.AB_MAIN, true)
        val json = store.toJson()

        val targetPrefs = FakePrefs()
        val target = LayoutStore(targetPrefs)
        assertTrue(target.applyJson(json))
        assertEquals(2, target.comboGroups.size)
        assertEquals("Атаки", target.comboGroups[1].name)
        assertEquals(listOf("u:1", "u:2"), target.comboGroups[1].keys)
        assertEquals(0.25f, target.comboGroups[1].x, 1e-6f)
        assertEquals(setOf(LayoutStore.AB_MAIN), target.abOff)
        // импорт записан в prefs — переживает ещё один «перезапуск»
        val reborn = LayoutStore(targetPrefs)
        assertEquals(2, reborn.comboGroups.size)
        assertEquals(setOf(LayoutStore.AB_MAIN), reborn.abOff)
    }

    @Test
    fun `старый экспорт без новых разделов импортируется`() {
        val store = LayoutStore(FakePrefs())
        store.addComboGroup("Лишняя")
        store.setAbHidden(LayoutStore.AB_TURBO, true)
        val old = """
            { "dpad": {"x":0.1,"y":0.2,"s":1.5},
              "ab": {"x":0.8,"y":0.7,"s":1.4},
              "meta": {"x":0.5,"y":0.3,"s":1.0},
              "combo": {"x":0.6,"y":0.8,"s":1.1} }
        """.trimIndent()
        assertTrue(store.applyJson(old))
        // одна комбо-группа из «combo», скрытий нет
        assertEquals(1, store.comboGroups.size)
        assertEquals(0.6f, store.comboGroups[0].x, 1e-6f)
        assertTrue(store.abOff.isEmpty())
    }

    @Test
    fun `resetAll возвращает одну комбо-группу и видимые пары A B`() {
        val prefs = FakePrefs()
        val store = LayoutStore(prefs)
        store.addComboGroup("Атаки")
        store.setAbHidden(LayoutStore.AB_MAIN, true)
        store.setComboEnabled("u:1", false)

        store.resetAll()
        assertEquals(1, store.comboGroups.size)
        assertEquals(LayoutStore.DEF_COMBO_GROUP, store.comboGroups[0])
        assertTrue(store.abOff.isEmpty())
        assertTrue(store.combosOff.isEmpty())
        // сброс сохранён — «перезапуск» его видит
        val reborn = LayoutStore(prefs)
        assertEquals(1, reborn.comboGroups.size)
        assertTrue(reborn.abOff.isEmpty())
    }
}
