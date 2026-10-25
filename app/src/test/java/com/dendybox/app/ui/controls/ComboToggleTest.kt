package com.dendybox.app.ui.controls

import com.dendybox.app.settings.GameConfig
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Отключение отдельных комбо-кнопок: стабильные ключи ([ComboStore.disabledKey]),
 * фильтрация ([ComboStore.withoutDisabled], [ComboStore.effective]) и компактная
 * сетка поверхности (comboSpecs — без дырок). JVM: GameConfig/ComboStore —
 * статические объекты, состояние теста восстанавливается в finally/@After.
 */
class ComboToggleTest {

    private fun preset(name: String) =
        ComboButton("preset", name, listOf(ComboStep("A", 0)))

    private fun user(id: String, name: String) =
        ComboButton(id, name, listOf(ComboStep("B", 0)))

    @After
    fun tearDown() {
        ComboStore.disabledProvider = { emptySet() }
        GameConfig.applyParsed(GameConfig.Parsed(true, emptyList(), null))
    }

    // ------------------------------------------------------------------
    // Ключи отключения
    // ------------------------------------------------------------------

    @Test
    fun `пресет ключуется по имени, своя кнопка — по id`() {
        assertEquals("pres:Суперудар", ComboStore.disabledKey(preset("Суперудар")))
        // id-заглушка «preset» и поверхностные «p0…» дают ОДИН ключ
        assertEquals(
            ComboStore.disabledKey(ComboButton("preset", "Ультра", emptyList())),
            ComboStore.disabledKey(ComboButton("p3", "Ультра", emptyList()))
        )
        assertEquals("u:u1abc", ComboStore.disabledKey(user("u1abc", "Моё")))
    }

    @Test
    fun `id пресета зависит от индекса рома, имя — нет`() {
        // Переключение ROM: «Суперудар» был p0, стал p1 — ключ тот же,
        // отключение переживает смену игры сборника
        val keyOnFirstRom = ComboStore.disabledKey(ComboButton("p0", "Суперудар", emptyList()))
        val keyOnOtherRom = ComboStore.disabledKey(ComboButton("p1", "Суперудар", emptyList()))
        assertEquals(keyOnFirstRom, keyOnOtherRom)
    }

    @Test
    fun `переименование своей кнопки не теряет отключение`() {
        val before = ComboStore.disabledKey(user("u42", "Старое имя"))
        val after = ComboStore.disabledKey(user("u42", "Новое имя"))
        assertEquals(before, after)
    }

    // ------------------------------------------------------------------
    // Фильтрация
    // ------------------------------------------------------------------

    @Test
    fun `withoutDisabled убирает только отключённые, порядок сохранён`() {
        val all = listOf(
            preset("Ультра"),
            user("u1", "Фатality"),
            preset("Суперудар"),
            user("u2", "Дожать")
        )
        val visible = ComboStore.withoutDisabled(all, setOf("pres:Суперудар", "u:u2"))
        assertEquals(listOf("Ультра", "Фатality"), visible.map { it.name })
        // пустой набор — тот же список
        assertEquals(all, ComboStore.withoutDisabled(all, emptySet()))
        // всё отключено — пусто
        assertTrue(
            ComboStore.withoutDisabled(
                all,
                all.map { ComboStore.disabledKey(it) }.toSet()
            ).isEmpty()
        )
    }

    @Test
    fun `эффективный список на поверхности без отключённых, определения целы`() {
        GameConfig.applyParsed(
            GameConfig.Parsed(
                true,
                listOf(preset("Суперудар"), preset("Зарядка B")),
                null
            )
        )
        ComboStore.disabledProvider = { setOf("pres:Суперудар") }

        assertEquals(listOf("Зарядка B"), ComboStore.effective().map { it.name })
        // определение пресета НЕ удалено — включение вернёт кнопку
        assertEquals(2, GameConfig.comboPresets.size)
    }

    @Test
    fun `провайдер по умолчанию пуст — в тестах и до первого захода в игру`() {
        GameConfig.applyParsed(
            GameConfig.Parsed(true, listOf(preset("Ультра")), null)
        )
        assertEquals(listOf("Ультра"), ComboStore.effective().map { it.name })
    }

    // ------------------------------------------------------------------
    // Компактная сетка поверхности (без дырок)
    // ------------------------------------------------------------------

    @Test
    fun `сетка после отключения ужимается без дырок`() {
        // 4 пресета ROM (p0..p3), один отключён — на поверхности 3 кнопки
        GameConfig.applyParsed(
            GameConfig.Parsed(
                true,
                listOf(preset("Раз"), preset("Два"), preset("Три"), preset("Четыре")),
                null
            )
        )
        ComboStore.disabledProvider = { setOf("pres:Два") }

        val specs = comboSpecs(ComboStore.effective())
        assertEquals(3, specs.size)
        // порядок оставшихся сохранён, id — «K» + id кнопки
        assertEquals("Kp0", specs[0].id)
        assertEquals("Kp2", specs[1].id)
        assertEquals("Kp3", specs[2].id)
        // 3 кнопки — сетка 2×2 (comboCols(3)=2), заняты первые 3 ячейки
        val cells = specs.map { it.dx to it.dy }.toSet()
        assertEquals(setOf(-32f to -32f, 32f to -32f, -32f to 32f), cells)
        // границы блока следуют видимой сетке (2 колонки), а не старому набору
        val ext = extentsDp(LayoutStore.GroupId.COMBO, 40f)
        val hw = COMBO_PITCH / 2f + COMBO_SIZE / 2f // (2-1)*64/2 + 56/2 = 60
        assertEquals(hw, ext.left)
        assertEquals(hw, ext.top)
    }
}
