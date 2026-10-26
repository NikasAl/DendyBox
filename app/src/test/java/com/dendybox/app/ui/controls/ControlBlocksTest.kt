package com.dendybox.app.ui.controls

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import com.dendybox.app.settings.GameConfig
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Блоки игровой поверхности: скрытие пар кластера A/B ([specsAb],
 * [extentsAb]), комбо-группы — состав ([comboButtonsOf]), сетка и её
 * обратная задача ([comboExtentsDp], [comboCellAt]), перетаскивание кнопки
 * ([comboDragTo]) и привязки [BlockRef]. JVM: состояние ComboStore/GameConfig
 * восстанавливается в @After.
 */
class ControlBlocksTest {

    private fun user(id: String, name: String) =
        ComboButton(id, name, listOf(ComboStep("A", 0)))

    @After
    fun tearDown() {
        ComboStore.disabledProvider = { emptySet() }
        GameConfig.applyParsed(GameConfig.Parsed(true, emptyList(), null))
        ComboStore.applySections(emptyMap())
    }

    // ------------------------------------------------------------------
    // Скрытие пар A/B
    // ------------------------------------------------------------------

    @Test
    fun `specsAb убирает только скрытую пару`() {
        assertEquals(4, specsAb(emptySet()).size)
        assertEquals(listOf("TB", "TA"), specsAb(setOf(LayoutStore.AB_MAIN)).map { it.id })
        assertEquals(listOf("B", "A"), specsAb(setOf(LayoutStore.AB_TURBO)).map { it.id })
        assertTrue(specsAb(setOf(LayoutStore.AB_MAIN, LayoutStore.AB_TURBO)).isEmpty())
    }

    @Test
    fun `скрытие основной пары подтягивает турбо-ряд без дырки`() {
        val specs = specsAb(setOf(LayoutStore.AB_MAIN))
        // турбо-ряд опустился на место пары A/B: dy = 0, dx прежние
        assertEquals(listOf(0f, 0f), specs.map { it.dy })
        assertEquals(listOf(-36f, 36f), specs.map { it.dx })
        assertEquals(listOf(64f, 64f), specs.map { it.size })
        // рамка/зона касаний сжимаются симметрично вокруг якоря —
        // пустого места на месте убранных кнопок нет:
        // нижний край рамки = нижний край кнопок (0 + 32)
        val e = extentsAb(setOf(LayoutStore.AB_MAIN))
        assertEquals(32f, e.bottom, 1e-6f)
        assertEquals(32f, e.top, 1e-6f)
        // и рамка совпадает с рамкой одной основной пары
        assertEquals(extentsAb(setOf(LayoutStore.AB_TURBO)), e)
    }

    @Test
    fun `extentsAb обнимает видимую половину`() {
        // обе пары видны: турбо-ряд сверху, основные снизу
        assertEquals(Extents(68f, 104f, 68f, 32f), extentsAb(emptySet()))
        // турбо скрыто: осталась нижняя пара, верх прижат к ней
        assertEquals(Extents(68f, 32f, 68f, 32f), extentsAb(setOf(LayoutStore.AB_TURBO)))
        // основные скрыты: турбо-ряд ПОДТЯНУТ к якорю (dy = 0) — рамка как у одной пары
        assertEquals(Extents(68f, 32f, 68f, 32f), extentsAb(setOf(LayoutStore.AB_MAIN)))
        // скрыто всё — квадратик для захвата в редакторе
        assertEquals(
            Extents(24f, 24f, 24f, 24f),
            extentsAb(setOf(LayoutStore.AB_MAIN, LayoutStore.AB_TURBO))
        )
    }

    @Test
    fun `BlockRef имена биндов уникальны на блок`() {
        assertTrue(BlockRef(LayoutStore.GroupId.AB).let { !it.isCombo && it.bindName == "AB" })
        val combo = BlockRef(LayoutStore.GroupId.COMBO, 2)
        assertTrue(combo.isCombo)
        assertEquals("COMBO:2", combo.bindName)
        // GroupId.COMBO без индекса — не комбо-блок (легаси-путь)
        assertFalse(BlockRef(LayoutStore.GroupId.COMBO).isCombo)
    }

    // ------------------------------------------------------------------
    // Сетка комбо-кнопок
    // ------------------------------------------------------------------

    @Test
    fun `comboExtentsDp — сетка по числу кнопок, пустой блок 24dp`() {
        assertEquals(Extents(24f, 24f, 24f, 24f), comboExtentsDp(0))
        assertEquals(Extents(28f, 28f, 28f, 28f), comboExtentsDp(1))
        assertEquals(Extents(60f, 28f, 60f, 28f), comboExtentsDp(2))
        assertEquals(Extents(60f, 60f, 60f, 60f), comboExtentsDp(4))
        assertEquals(Extents(92f, 60f, 92f, 60f), comboExtentsDp(5))
    }

    @Test
    fun `comboCellAt — обратная задача сетки`() {
        // 2×2: ячейки (-32,-32), (32,-32), (-32,32), (32,32)
        assertEquals(0, comboCellAt(4, -32f, -32f))
        assertEquals(1, comboCellAt(4, 32f, -32f))
        assertEquals(2, comboCellAt(4, -32f, 32f))
        assertEquals(3, comboCellAt(4, 32f, 32f))
        // далеко за краями — зажим в границы
        assertEquals(1, comboCellAt(4, 1000f, -1000f))
        assertEquals(3, comboCellAt(4, 1f, 1f))
        // одна кнопка и пустой блок
        assertEquals(0, comboCellAt(1, 0f, 0f))
        assertEquals(0, comboCellAt(0, 100f, 100f))
    }

    // ------------------------------------------------------------------
    // Состав комбо-групп
    // ------------------------------------------------------------------

    @Test
    fun `comboButtonsOf — участники по порядку, непрописанные в группе 0, дремлющие пропускаются`() {
        ComboStore.applySections(
            mapOf(
                ComboStore.FLAVOR_KEY to listOf(
                    user("u1", "Раз"), user("u2", "Два"), user("u3", "Три")
                )
            )
        )
        val store = LayoutStore(FakePrefs())
        store.addComboGroup("Вторая")
        store.setComboGroupKeys(0, listOf("u:u2"))
        // «pres:Нет» — дремлющий ключ (пресета с таким именем сейчас нет)
        store.setComboGroupKeys(1, listOf("pres:Нет", "u:u3"))

        assertEquals(listOf("Два", "Раз"), comboButtonsOf(store, 0).map { it.name })
        assertEquals(listOf("Три"), comboButtonsOf(store, 1).map { it.name })
        // непрописанная кнопка числится за группой 0, дремлющий ключ — за своей группой
        assertEquals(0, store.comboGroupOfKey("u:u1"))
        assertEquals(1, store.comboGroupOfKey("pres:Нет"))
    }

    @Test
    fun `comboButtonsOf вне диапазона групп — пусто`() {
        val store = LayoutStore(FakePrefs())
        assertTrue(comboButtonsOf(store, 5).isEmpty())
    }

    // ------------------------------------------------------------------
    // Перетаскивание кнопки (редактор)
    // ------------------------------------------------------------------

    private fun twoGroupStore(): LayoutStore {
        ComboStore.applySections(
            mapOf(
                ComboStore.FLAVOR_KEY to listOf(
                    user("u1", "Раз"), user("u2", "Два"), user("u3", "Три")
                )
            )
        )
        val store = LayoutStore(FakePrefs())
        // обе группы в предсказуемых местах экрана 1000×1000
        store.updateComboGroupPos(0, 0.5f, 0.5f, 1f)
        store.addComboGroup("Вторая")
        store.updateComboGroupPos(1, 0.25f, 0.25f, 1f)
        return store
    }

    @Test
    fun `перетаскивание меняет порядок внутри группы`() {
        val store = twoGroupStore()
        // группа 0 в (0.5, 0.5) экрана 1000×1000 → якорь (500, 500);
        // сетка из 3 кнопок 2×2: ячейки (-32,-32), (32,-32), (-32,32)
        val density = Density(1f)
        val screen = IntSize(1000, 1000)
        // «Раз» (ячейка 0) тянут на ячейку 2 (-32,+32): порядок [Два, Раз, Три]
        comboDragTo(store, 0, "Ku1", 500f - 32f, 500f + 32f, screen, density)
        assertEquals(listOf("u:u2", "u:u1", "u:u3"), store.comboGroups[0].keys)
    }

    @Test
    fun `перетаскивание на своё место — без изменений`() {
        val store = twoGroupStore()
        val density = Density(1f)
        val screen = IntSize(1000, 1000)
        // «Раз» на своей ячейке и сразу за своей — порядок тот же
        comboDragTo(store, 0, "Ku1", 500f - 32f, 500f - 32f, screen, density)
        comboDragTo(store, 0, "Ku1", 500f + 32f, 500f - 32f, screen, density)
        assertTrue(store.comboGroups[0].keys.isEmpty()) // ничего не материализовалось
    }

    @Test
    fun `перетаскивание переносит кнопку в другую группу`() {
        val store = twoGroupStore()
        val density = Density(1f)
        val screen = IntSize(1000, 1000)
        // якорь «Второй» (250, 250); «Раз» тянут туда → в конце целевой группы
        comboDragTo(store, 0, "Ku1", 250f, 250f, screen, density)
        assertEquals(listOf("u:u1"), store.comboGroups[1].keys)
        assertEquals(listOf("u:u2", "u:u3"), comboButtonsOf(store, 0).map { ComboStore.disabledKey(it) })
    }

    @Test
    fun `перетаскивание мимо всех групп — без изменений`() {
        val store = twoGroupStore()
        val density = Density(1f)
        val screen = IntSize(1000, 1000)
        comboDragTo(store, 0, "Ku1", 10f, 900f, screen, density)
        assertTrue(store.comboGroups[0].keys.isEmpty())
        assertTrue(store.comboGroups[1].keys.isEmpty())
    }

    @Test
    fun `дремлющие ключи группы переживают перетаскивание`() {
        val store = twoGroupStore()
        store.setComboGroupKeys(0, listOf("pres:Дремлет"))
        val density = Density(1f)
        val screen = IntSize(1000, 1000)
        // «Два» (своя ячейка (32,-32)) тянется на ячейку 0 (-32,-32): состав
        // материализуется, дремлющий ключ остаётся в конце списка
        comboDragTo(store, 0, "Ku2", 500f - 32f, 500f - 32f, screen, density)
        val keys = store.comboGroups[0].keys
        assertEquals("u:u2", keys.first())
        assertTrue("pres:Дремлет" in keys)
    }
}
