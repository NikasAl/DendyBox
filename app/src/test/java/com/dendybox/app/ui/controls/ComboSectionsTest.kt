package com.dendybox.app.ui.controls

import com.dendybox.app.settings.GameConfig
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Свои комбо-кнопки, привязанные к игре сборника: секции ComboStore
 * (flavor + по названию рома), переключение вместе с пресетами
 * (selectRom), изоляция наборов между играми, персистентность
 * (разбор/сериализация конфига v2 и миграция старого массива v1).
 * JVM: состояние статических объектов восстанавливается в @After.
 */
class ComboSectionsTest {

    private fun user(id: String, name: String) =
        ComboButton(id, name, listOf(ComboStep("A", 0)))

    private fun userB(id: String, name: String) =
        ComboButton(id, name, listOf(ComboStep("B", 50)))

    @After
    fun tearDown() {
        ComboStore.applySections(emptyMap())
        GameConfig.applyParsed(GameConfig.Parsed(true, emptyList(), null))
    }

    // ------------------------------------------------------------------
    // Конфиг секций: разбор и сериализация
    // ------------------------------------------------------------------

    @Test
    fun `конфиг v2 — сериализация и разбор в обе стороны`() {
        val map = mapOf(
            ComboStore.FLAVOR_KEY to listOf(user("u1", "Общая")),
            "ТМНТ" to listOf(user("u2", "Прыжок"), user("u3", "Добить")),
            "Контра" to listOf(user("u4", "Автоогонь"))
        )
        val parsed = ComboStore.parseSections(ComboStore.sectionsToJson(map))
        assertEquals(map, parsed)
    }

    @Test
    fun `пустые секции не пишутся и не читаются`() {
        // flavor пустой, у «Контры» пустой список — в JSON только ТМНТ
        val json = ComboStore.sectionsToJson(
            mapOf(
                ComboStore.FLAVOR_KEY to emptyList(),
                "ТМНТ" to listOf(user("u1", "Прыжок")),
                "Контра" to emptyList<ComboButton>()
            )
        )
        assertEquals(
            mapOf("ТМНТ" to listOf(user("u1", "Прыжок"))),
            ComboStore.parseSections(json)
        )
        // пустые списки прямо в json — то же самое
        assertEquals(emptyMap<String, List<ComboButton>>(), ComboStore.parseSections("""{"flavor":[],"roms":{"X":[]}}"""))
    }

    @Test
    fun `старый формат v1 — плоский массив становится секцией flavor`() {
        val v1 = """[{"id":"u1","name":"Суперудар","steps":[{"b":"A","d":0}]},
                     {"id":"u2","name":"Дожать","steps":[{"b":"B","d":50}]}]"""
        assertEquals(
            mapOf(
                ComboStore.FLAVOR_KEY to listOf(user("u1", "Суперудар"), userB("u2", "Дожать"))
            ),
            ComboStore.parseSections(v1)
        )
        // пустой старый массив — конфига нет
        assertEquals(emptyMap<String, List<ComboButton>>(), ComboStore.parseSections("[]"))
        assertEquals(emptyMap<String, List<ComboButton>>(), ComboStore.parseSections(null))
        assertEquals(emptyMap<String, List<ComboButton>>(), ComboStore.parseSections(""))
    }

    @Test
    fun `битые кнопки изолируются по элементу, мусор — без исключений`() {
        // hold 33 мимо сетки 50 — шаг битый, кнопка целиком отбрасывается
        val json = """
            {"flavor":[{"id":"u1","name":"Целая","steps":[{"b":"A","d":0}]},
                       {"id":"u2","name":"Битая","steps":[{"b":"A","d":0,"h":33}]}],
             "roms":{"ТМНТ":[{"id":"u3","name":"Без шагов","steps":[]},
                             {"id":"u4","name":"Целая","steps":[{"b":"B","d":50}]}],
                     "Контра":[{"name":"Без id","steps":[{"b":"A","d":0}]}],
                     "Пустая":[]}}
        """.trimIndent()
        assertEquals(
            mapOf(
                ComboStore.FLAVOR_KEY to listOf(user("u1", "Целая")),
                "ТМНТ" to listOf(userB("u4", "Целая"))
            ),
            ComboStore.parseSections(json)
        )
        // мусор — пустая карта, исключений нет
        assertEquals(emptyMap<String, List<ComboButton>>(), ComboStore.parseSections("не json"))
        assertEquals(emptyMap<String, List<ComboButton>>(), ComboStore.parseSections("123"))
        assertEquals(emptyMap<String, List<ComboButton>>(), ComboStore.parseSections("""{"roms":"не объект"}"""))
    }

    // ------------------------------------------------------------------
    // Переключение активной секции — как у пресетов
    // ------------------------------------------------------------------

    @Test
    fun `selectRom переключает набор своих кнопок, секции не смешиваются`() {
        ComboStore.applySections(
            mapOf(
                ComboStore.FLAVOR_KEY to listOf(user("uF", "Общая")),
                "ТМНТ" to listOf(user("uT1", "Прыжок"), user("uT2", "Добить")),
                "Контра" to listOf(user("uC", "Автоогонь"))
            )
        )
        // одиночная игра / меню сборника — секция flavor
        ComboStore.selectRom(null)
        assertEquals(listOf("Общая"), ComboStore.buttons.map { it.name })
        // игра сборника — своя секция
        ComboStore.selectRom("ТМНТ")
        assertEquals(listOf("Прыжок", "Добить"), ComboStore.buttons.map { it.name })
        ComboStore.selectRom("Контра")
        assertEquals(listOf("Автоогонь"), ComboStore.buttons.map { it.name })
        // у игры без своей секции — пусто (не подмешивается чужое)
        ComboStore.selectRom("Супер Контра")
        assertTrue(ComboStore.buttons.isEmpty())
    }

    @Test
    fun `add update remove пишут только в активную секцию`() {
        ComboStore.applySections(
            mapOf(
                ComboStore.FLAVOR_KEY to listOf(user("uF", "Общая")),
                "ТМНТ" to listOf(user("uT", "Прыжок")),
                "Контра" to listOf(user("uC", "Автоогонь"))
            ),
            active = "ТМНТ"
        )
        ComboStore.add(user("uNew", "Добить"))
        assertEquals(listOf("Прыжок", "Добить"), ComboStore.buttons.map { it.name })
        // соседние секции не тронуты
        assertEquals(listOf("Общая"), ComboStore.sectionsSnapshot()[ComboStore.FLAVOR_KEY]!!.map { it.name })
        assertEquals(listOf("Автоогонь"), ComboStore.sectionsSnapshot()["Контра"]!!.map { it.name })

        ComboStore.update(user("uT", "Прыжок-удар"))
        assertEquals(listOf("Прыжок-удар", "Добить"), ComboStore.buttons.map { it.name })
        ComboStore.update(user("uЧужой", "Ничего не меняет"))
        assertEquals(listOf("Прыжок-удар", "Добить"), ComboStore.buttons.map { it.name })

        ComboStore.remove("uT")
        assertEquals(listOf("Добить"), ComboStore.buttons.map { it.name })
        ComboStore.remove("uNew")
        // секция опустела — исчезла целиком, чужие кнопки не появились
        assertFalse(ComboStore.sectionsSnapshot().containsKey("ТМНТ"))
        ComboStore.selectRom("ТМНТ")
        assertTrue(ComboStore.buttons.isEmpty())
    }

    @Test
    fun `update и remove в секции, которой нет, безопасны`() {
        ComboStore.applySections(emptyMap(), active = "ТМНТ")
        ComboStore.update(user("u1", "X"))
        ComboStore.remove("u1")
        assertTrue(ComboStore.buttons.isEmpty())
        assertTrue(ComboStore.sectionsSnapshot().isEmpty())
    }

    // ------------------------------------------------------------------
    // Эффективный список: пресеты + своя секция, отключения независимы
    // ------------------------------------------------------------------

    @Test
    fun `эффективный список — пресеты и своя секция активной игры`() {
        GameConfig.applyParsed(
            GameConfig.Parsed(true, listOf(ComboButton("preset", "Суперудар", listOf(ComboStep("A", 0)))), null)
        )
        ComboStore.applySections(
            mapOf(
                "ТМНТ" to listOf(user("uT", "Прыжок")),
                "Контра" to listOf(user("uC", "Автоогонь"))
            )
        )
        ComboStore.selectRom("ТМНТ")
        assertEquals(listOf("Суперудар", "Прыжок"), ComboStore.effective().map { it.name })
        ComboStore.selectRom("Контра")
        assertEquals(listOf("Суперудар", "Автоогонь"), ComboStore.effective().map { it.name })
        ComboStore.selectRom(null)
        assertEquals(listOf("Суперудар"), ComboStore.effective().map { it.name })
    }

    @Test
    fun `отключение своей кнопки переживает переключение ромов`() {
        GameConfig.applyParsed(GameConfig.Parsed(true, emptyList(), null))
        ComboStore.applySections(
            mapOf(
                "ТМНТ" to listOf(user("uT", "Прыжок")),
                "Контра" to listOf(user("uC", "Автоогонь"))
            )
        )
        ComboStore.disabledProvider = { setOf(ComboStore.disabledKey(user("uT", "Прыжок"))) }
        try {
            ComboStore.selectRom("ТМНТ")
            assertTrue(ComboStore.effective().isEmpty())
            // определение цело — включение вернёт кнопку
            assertEquals(listOf("Прыжок"), ComboStore.buttons.map { it.name })
            // в другой игре отключение чужой кнопки не видно
            ComboStore.selectRom("Контра")
            assertEquals(listOf("Автоогонь"), ComboStore.effective().map { it.name })
            // и обратно — кнопка всё ещё спрятана
            ComboStore.selectRom("ТМНТ")
            assertTrue(ComboStore.effective().isEmpty())
        } finally {
            ComboStore.disabledProvider = { emptySet() }
        }
    }

    // ------------------------------------------------------------------
    // Уникальность id по всем секциям
    // ------------------------------------------------------------------

    @Test
    fun `новый id свободен во всех секциях, не только в активной`() {
        ComboStore.applySections(
            mapOf(
                "ТМНТ" to listOf(user("uTTT", "Прыжок")),
                "Контра" to listOf(user("uCCC", "Автоогонь"))
            ),
            active = "ТМНТ"
        )
        // конфликт в АКТИВНОЙ секции
        assertEquals("uTTT_1", ComboStore.uniqueId("uTTT"))
        // конфликт в ДРУГОЙ секции тоже ловится
        assertEquals("uCCC_1", ComboStore.uniqueId("uCCC"))
        // свободная база — как есть
        assertEquals("uСвобод", ComboStore.uniqueId("uСвобод"))
        // newId выдаёт id, которого нет ни в одной секции
        val fresh = ComboStore.newId()
        assertFalse(ComboStore.sectionsSnapshot().values.any { list -> list.any { it.id == fresh } })
    }
}
