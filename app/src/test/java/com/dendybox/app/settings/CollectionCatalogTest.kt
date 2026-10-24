package com.dendybox.app.settings

import com.dendybox.app.ui.controls.ComboStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Разбор манифеста сборника (assets/games/games.json): игры, заголовки,
 * фон и — главное — пресеты комбо-кнопок из записей игр (per-ROM пресеты,
 * задаются в roms/games.json и переносятся сборкой). Чистый разбор, JVM.
 */
class CollectionCatalogTest {

    @Test
    fun `манифест разбирается — заголовок, фон, выравнивание, игры`() {
        val data = CollectionCatalog.parse(
            """
            { "title": "Контра: Сборник",
              "background": "background.webp",
              "menuAlign": "left",
              "games": [
                { "file": "0.nes", "title": "Контра" },
                { "file": "1.nes" }
              ] }
            """.trimIndent()
        )!!
        assertEquals("Контра: Сборник", data.title)
        assertEquals("games/background.webp", data.background)
        assertEquals("left", data.menuAlign)
        assertEquals(2, data.games.size)
        assertEquals("Контра", data.games[0].title)
        assertEquals("games/0.nes", data.games[0].assetPath)
        // Без title — имя «Игра N»
        assertEquals("Игра 2", data.games[1].title)
    }

    @Test
    fun `пресеты комбо читаются из записи игры`() {
        val data = CollectionCatalog.parse(
            """
            { "games": [
                { "file": "0.nes", "title": "Черепашки Ниндзя II",
                  "combos": [
                    { "name": "Суперудар", "steps": [ { "b": "A", "d": 0, "w": ["B"] } ] },
                    { "name": "Рывок-удар", "steps": [
                      { "b": "RIGHT", "d": 0 }, { "b": "RIGHT", "d": 0 }, { "b": "B", "d": 0 } ] }
                  ] },
                { "file": "1.nes", "title": "Без пресетов" }
              ] }
            """.trimIndent()
        )!!
        // У первой игры — свои пресеты, у второй — null (нет раздела:
        // показываются общие пресеты конфига сборки)
        val presets = data.games[0].combos
        assertNotNull(presets)
        assertEquals(listOf("Суперудар", "Рывок-удар"), presets!!.map { it.name })
        val first = presets[0].steps.single()
        assertEquals(listOf("A", "B"), listOf(first.btn) + first.with)
        assertEquals(
            listOf(ComboStep("RIGHT", 0), ComboStep("RIGHT", 0), ComboStep("B", 0)),
            presets[1].steps
        )
        assertNull(data.games[1].combos)
    }

    @Test
    fun `явно пустой combos — пустой список, а не отсутствие раздела`() {
        val data = CollectionCatalog.parse(
            """{ "games": [ { "file": "0.nes", "combos": [] }, { "file": "1.nes" } ] }"""
        )!!
        // Явный пустой раздел отключает пресеты у игры (общие не подставляются)
        assertEquals(emptyList<com.dendybox.app.ui.controls.ComboButton>(), data.games[0].combos)
        assertNull(data.games[1].combos)
    }

    @Test
    fun `битые пресеты отбрасываются, запись игры живёт`() {
        val data = CollectionCatalog.parse(
            """
            { "games": [
                { "file": "0.nes", "title": "Игра",
                  "combos": [
                    { "name": "Хорошая", "steps": [ { "b": "A", "d": 0 } ] },
                    { "name": "Плохая", "steps": [ { "b": "ТУРБО", "d": 0 } ] },
                    { "name": "Зарядка", "steps": [ { "b": "B", "d": 0, "h": 500 } ] }
                  ] }
              ] }
            """.trimIndent()
        )!!
        assertEquals(
            listOf("Хорошая", "Зарядка"),
            data.games[0].combos!!.map { it.name }
        )
    }

    @Test
    fun `битый манифест и пути вне папки — null и пропуск`() {
        assertNull(CollectionCatalog.parse("мусор"))
        assertNull(CollectionCatalog.parse("""{ "games": [] }"""))
        // file с выходом за папку сборника — элемент пропускается
        val data = CollectionCatalog.parse(
            """{ "games": [ { "file": "../secret.nes" }, { "file": "0.nes" } ] }"""
        )!!
        assertEquals(1, data.games.size)
        assertEquals("games/0.nes", data.games[0].assetPath)
        assertEquals(1, data.games[0].index) // индекс в манифесте сохранён
    }
}
