package com.dendybox.app.settings

import com.dendybox.app.ui.controls.ComboButton
import com.dendybox.app.ui.controls.ComboStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Разбор game.json (roms/<flavor>.json: netplay, байт урона, общие пресеты
 * комбо) и переключение активных пресетов при загрузке рома сборника
 * ([GameConfig.selectRom]) — чистые функции/состояние, JVM.
 */
class GameConfigTest {

    private val flavorPresets = listOf(
        ComboButton("preset", "Общий", listOf(ComboStep("A", 0)))
    )
    private val romPresets = listOf(
        ComboButton("preset", "Свой", listOf(ComboStep("B", 0, holdMs = 500)))
    )

    // ------------------------------------------------------------------
    // Разбор game.json
    // ------------------------------------------------------------------

    @Test
    fun `пустой конфиг — дефолты`() {
        val p = GameConfig.parseGameJson("{}")
        assertTrue(p.netplay)
        assertTrue(p.combos.isEmpty())
        assertNull(p.damage)
    }

    @Test
    fun `битый json — дефолты без исключения`() {
        val p = GameConfig.parseGameJson("это не json")
        assertTrue(p.netplay)
        assertTrue(p.combos.isEmpty())
        assertNull(p.damage)
    }

    @Test
    fun `netplay, пресеты комбо и урон разбираются`() {
        val p = GameConfig.parseGameJson(
            """
            { "netplay": false,
              "damage": { "addr": "0x00F0", "mode": "dec", "value": 0 },
              "combos": [
                { "name": "Суперудар", "steps": [ { "b": "A", "d": 0 }, { "b": "B", "d": 100 } ] },
                { "name": "Зарядка", "steps": [ { "b": "B", "d": 0, "h": 500 } ] },
                { "name": "Битая", "steps": [ { "b": "ТУРБО", "d": 0 } ] }
              ] }
            """.trimIndent()
        )
        assertTrue(!p.netplay)
        // Битая запись отброшена; границы сетки 50 строги
        assertEquals(listOf("Суперудар", "Зарядка"), p.combos.map { it.name })
        val d = p.damage!!
        assertEquals(0xF0, d.addr)
        assertEquals(1, d.mode)
    }

    @Test
    fun `адрес урона вне ram и без адреса — без вибрации`() {
        assertNull(
            GameConfig.parseGameJson("""{ "damage": { "addr": "0x2000", "mode": "dec" } }""").damage
        )
        assertNull(
            GameConfig.parseGameJson("""{ "damage": { "mode": "dec" } }""").damage
        )
        // Десятичный адрес тоже разбирается
        assertEquals(
            240,
            GameConfig.parseGameJson("""{ "damage": { "addr": 240, "mode": "inc" } }""").damage!!.addr
        )
    }

    // ------------------------------------------------------------------
    // Переключение активных пресетов при смене рома
    // ------------------------------------------------------------------

    @Test
    fun `selectRom подставляет пресеты рома и подпись`() {
        GameConfig.applyParsed(GameConfig.Parsed(true, flavorPresets, null))
        assertEquals(flavorPresets, GameConfig.comboPresets)
        assertNull(GameConfig.activeRomTitle)

        // Выбрана игра сборника со СВОИМ разделом combos
        GameConfig.selectRom(romPresets, "Черепашки Ниндзя II")
        assertEquals(romPresets, GameConfig.comboPresets)
        assertEquals("Черепашки Ниндзя II", GameConfig.activeRomTitle)

        // Возврат в меню сборника — обратно общие пресеты, подпись сброшена
        GameConfig.selectRom(null, null)
        assertEquals(flavorPresets, GameConfig.comboPresets)
        assertNull(GameConfig.activeRomTitle)
    }

    @Test
    fun `явно пустой список пресетов рома сильнее общих`() {
        GameConfig.applyParsed(GameConfig.Parsed(true, flavorPresets, null))
        // "combos": [] в записи игры — пресетов у рома заведомо нет
        GameConfig.selectRom(emptyList(), "Игра без пресетов")
        assertTrue(GameConfig.comboPresets.isEmpty())
        assertEquals("Игра без пресетов", GameConfig.activeRomTitle)
        GameConfig.selectRom(null, null)
    }
}
